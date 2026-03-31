package com.example.javaparser.service;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.resource.UMLResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java源码解析服务 - 生成UML 2.0标准格式的XMI文件
 * 使用Eclipse UML2库生成符合OMG UML 2.x规范的XMI
 * 适配UML2 5.0.1版本API
 */
@Slf4j
@Service
public class JavaToUML2Service {

    private ResourceSet resourceSet;
    private Model model;
    private Package rootPackage;
    private Map<String, Package> packageMap;
    private Map<String, Classifier> classifierMap;
    private List<PendingRelation> pendingRelations;
    private Map<String, ClassTextInfo> classTextInfoMap;
    private PrimitiveType stringType;
    private PrimitiveType intType;
    private PrimitiveType booleanType;
    private PrimitiveType doubleType;
    private PrimitiveType longType;
    private PrimitiveType floatType;
    private String currentProcessingPackage; // 当前正在处理的包名，用于占位符类归包

    /**
     * 初始化UML2资源集
     */
    public void initialize() {
        log.info("初始化UML2资源集...");

        // 配置JavaParser使用BLEEDING_EDGE模式，支持最新Java语法但对旧代码更宽容
        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        StaticJavaParser.setConfiguration(parserConfig);

        initializeResourceSet();
    }

    /**
     * 初始化UML2资源集（带Symbol Solver）
     */
    public void initialize(String sourceDirectory) {
        log.info("初始化UML2资源集（带Symbol Solver）...");

        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

        // 集成Symbol Solver实现精确类型解析
        try {
            CombinedTypeSolver combinedSolver = new CombinedTypeSolver();
            combinedSolver.add(new ReflectionTypeSolver());
            combinedSolver.add(new JavaParserTypeSolver(new File(sourceDirectory)));

            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedSolver);
            parserConfig.setSymbolResolver(symbolSolver);
            log.info("Symbol Solver已启用，源码目录: {}", sourceDirectory);
        } catch (Exception e) {
            log.warn("Symbol Solver初始化失败，将使用简单名称匹配: {}", e.getMessage());
        }

        StaticJavaParser.setConfiguration(parserConfig);

        initializeResourceSet();
    }

    /**
     * 初始化UML2资源集的公共部分
     */
    private void initializeResourceSet() {
        resourceSet = new ResourceSetImpl();

        // 注册UML资源工厂
        resourceSet.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put(UMLResource.FILE_EXTENSION, UMLResource.Factory.INSTANCE);

        // 注册UML包
        resourceSet.getPackageRegistry()
                .put(UMLPackage.eNS_URI, UMLPackage.eINSTANCE);

        packageMap = new HashMap<>();
        classifierMap = new HashMap<>();
        pendingRelations = new ArrayList<>();
        classTextInfoMap = new HashMap<>();

        // 创建UML模型
        model = UMLFactory.eINSTANCE.createModel();
        model.setName("JavaCodeModel");

        // 创建根包
        rootPackage = model.createNestedPackage("root");

        // 初始化基本类型
        initializePrimitiveTypes();

        log.info("UML2资源集初始化完成");
    }

    /**
     * 初始化基本数据类型
     */
    private void initializePrimitiveTypes() {
        // 只创建真正的Java基本类型
        stringType = model.createOwnedPrimitiveType("String");
        intType = model.createOwnedPrimitiveType("Integer");
        booleanType = model.createOwnedPrimitiveType("Boolean");
        doubleType = model.createOwnedPrimitiveType("Double");
        longType = model.createOwnedPrimitiveType("Long");
        floatType = model.createOwnedPrimitiveType("Float");

        log.info("初始化基本类型: String, Integer, Boolean, Double, Long, Float");
        log.info("注意: 项目内的类（如Function, BasicBlock等）将作为Class定义，不会创建为PrimitiveType");
    }

    /**
     * 解析Java项目并生成UML 2.0 XMI文件
     *
     * @param sourceDirectory 源码目录
     * @param outputDirectory 输出目录
     * @return 生成的XMI文件路径
     */
    public XMIGenerationResult parseAndGenerateXMI(String sourceDirectory, String outputDirectory)
            throws IOException {

        if (resourceSet == null) {
            initialize(sourceDirectory);
        }

        File sourceDir = new File(sourceDirectory);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new IllegalArgumentException("无效的源码目录: " + sourceDirectory);
        }

        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        log.info("开始解析Java项目: {}", sourceDirectory);

        // 查找并解析所有Java文件
        List<Path> javaFiles = findJavaFiles(sourceDir.toPath());
        log.info("找到 {} 个Java文件", javaFiles.size());

        // 第一遍：创建所有类和接口
        Set<Path> failedFiles = new HashSet<>();
        for (Path javaFile : javaFiles) {
            try {
                parseJavaFileFirstPass(javaFile);
            } catch (Exception e) {
                failedFiles.add(javaFile);
                log.warn("跳过无法解析的文件(第一遍, 可能使用了不支持的Java语法): {} - {}",
                        javaFile.getFileName(), e.getMessage());
                log.debug("解析失败详情(第一遍): {}", javaFile, e);
            }
        }

        // 第二遍：处理继承关系和关联（跳过第一遍已失败的文件）
        for (Path javaFile : javaFiles) {
            if (failedFiles.contains(javaFile)) {
                continue;
            }
            try {
                parseJavaFileSecondPass(javaFile);
            } catch (Exception e) {
                log.warn("跳过无法解析的文件(第二遍, 可能使用了不支持的Java语法): {} - {}",
                        javaFile.getFileName(), e.getMessage());
                log.debug("解析失败详情(第二遍): {}", javaFile, e);
            }
        }

        if (!failedFiles.isEmpty()) {
            log.info("文件解析完成: 成功 {}/{} 个文件, 跳过 {} 个不支持语法的文件",
                    javaFiles.size() - failedFiles.size(), javaFiles.size(), failedFiles.size());
        }

        // 关系去重：按(source, target)去重，保留优先级最高的类型
        deduplicatePendingRelations();

        // 第三遍：创建关联关系（Association）
        log.info("开始创建关联关系...");
        createAssociations();

        log.info("成功解析 {} 个包, {} 个分类器", packageMap.size(), classifierMap.size());

        // 生成XMI文件
        String modelXMI = saveUMLModel(outputDir, "model.uml");
        String packageXMI = savePackageView(outputDir, "package.uml");
        String classXMI = saveClassView(outputDir, "class.uml");

        XMIGenerationResult result = new XMIGenerationResult();
        result.setModelXMIPath(modelXMI);
        result.setPackageXMIPath(packageXMI);
        result.setClassXMIPath(classXMI);
        result.setTotalPackages(packageMap.size());
        result.setTotalClasses(classifierMap.size());

        return result;
    }

    /**
     * 第一遍解析：创建类和接口结构
     */
    private void parseJavaFileFirstPass(Path javaFilePath) throws IOException {
        log.debug("第一遍解析文件: {}", javaFilePath);

        CompilationUnit cu = StaticJavaParser.parse(javaFilePath);

        // 获取包名
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("default");

        // 获取或创建UML Package
        Package umlPackage = getOrCreatePackage(packageName);

        // 设置当前处理的包名，供占位符类创建时使用
        currentProcessingPackage = packageName;

        // 处理所有类型声明
        cu.findAll(TypeDeclaration.class).forEach(typeDecl -> {
            try {
                if (typeDecl.isClassOrInterfaceDeclaration()) {
                    ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();
                    createUMLClass(classDecl, umlPackage, packageName);
                } else if (typeDecl.isEnumDeclaration()) {
                    EnumDeclaration enumDecl = typeDecl.asEnumDeclaration();
                    createUMLEnumeration(enumDecl, umlPackage, packageName);
                }
            } catch (Exception e) {
                log.error("处理类型声明失败: {}", typeDecl.getNameAsString(), e);
            }
        });
    }

    /**
     * 第二遍解析：处理继承和关联关系
     */
    private void parseJavaFileSecondPass(Path javaFilePath) throws IOException {
        log.debug("第二遍解析文件: {}", javaFilePath);

        CompilationUnit cu = StaticJavaParser.parse(javaFilePath);

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("default");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            try {
                processClassRelationships(classDecl, packageName);
                extractAssociationRelations(classDecl, packageName);
                extractDependencyRelations(classDecl, packageName);
                extractClassTextInfo(classDecl, packageName);
            } catch (Exception e) {
                log.error("处理类关系失败: {}", classDecl.getNameAsString(), e);
            }
        });
    }

    /**
     * 获取或创建UML Package
     */
    private Package getOrCreatePackage(String packageName) {
        if (packageMap.containsKey(packageName)) {
            return packageMap.get(packageName);
        }

        // 处理嵌套包
        String[] parts = packageName.split("\\.");
        Package currentPackage = rootPackage;

        StringBuilder currentPath = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                currentPath.append(".");
            }
            currentPath.append(parts[i]);
            String path = currentPath.toString();

            if (!packageMap.containsKey(path)) {
                Package newPackage = currentPackage.createNestedPackage(parts[i]);
                packageMap.put(path, newPackage);
                currentPackage = newPackage;
                log.debug("创建包: {}", path);
            } else {
                currentPackage = packageMap.get(path);
            }
        }

        return currentPackage;
    }

    /**
     * 创建UML类或接口
     */
    private void createUMLClass(ClassOrInterfaceDeclaration classDecl, Package umlPackage, String packageName) {
        String className = classDecl.getNameAsString();
        String qualifiedName = packageName + "." + className;

        if (classifierMap.containsKey(qualifiedName)) {
            return; // 已存在
        }

        if (classDecl.isInterface()) {
            // 创建接口
            Interface umlInterface = umlPackage.createOwnedInterface(className);

            // 添加方法（接口方法）
            classDecl.getMethods().forEach(method -> {
                createUMLOperationForInterface(umlInterface, method);
            });

            classifierMap.put(qualifiedName, umlInterface);
            log.debug("创建接口: {}", qualifiedName);
        } else {
            // 创建类
            Class umlClass = umlPackage.createOwnedClass(className, classDecl.isAbstract());

            // 添加属性（字段）
            classDecl.getFields().forEach(field -> {
                field.getVariables().forEach(var -> {
                    createUMLProperty(umlClass, var, field);
                });
            });

            // 添加方法
            classDecl.getMethods().forEach(method -> {
                createUMLOperationForClass(umlClass, method);
            });

            classifierMap.put(qualifiedName, umlClass);
            log.debug("创建类: {}", qualifiedName);
        }
    }

    /**
     * 处理类的继承和关联关系
     */
    private void processClassRelationships(ClassOrInterfaceDeclaration classDecl, String packageName) {
        String className = classDecl.getNameAsString();
        String qualifiedName = packageName + "." + className;

        Classifier classifier = classifierMap.get(qualifiedName);
        if (classifier == null) {
            return;
        }

        // 处理继承
        classDecl.getExtendedTypes().forEach(extendedType -> {
            String superTypeName = extendedType.getNameAsString();
            String superQualifiedName = resolveQualifiedName(superTypeName, packageName);

            Classifier superClassifier = classifierMap.get(superQualifiedName);
            if (superClassifier != null && classifier instanceof Class && superClassifier instanceof Class) {
                Generalization generalization = ((Class) classifier).createGeneralization((Class) superClassifier);
                pendingRelations.add(new PendingRelation(qualifiedName, superQualifiedName, "GENERALIZATION"));
                log.debug("创建继承关系: {} -> {}", qualifiedName, superQualifiedName);
            }
        });

        // 处理接口实现
        classDecl.getImplementedTypes().forEach(implementedType -> {
            String interfaceName = implementedType.getNameAsString();
            String interfaceQualifiedName = resolveQualifiedName(interfaceName, packageName);

            Classifier interfaceClassifier = classifierMap.get(interfaceQualifiedName);
            if (interfaceClassifier != null && interfaceClassifier instanceof Interface) {
                if (classifier instanceof Class) {
                    InterfaceRealization realization = ((Class) classifier)
                            .createInterfaceRealization(interfaceName + "_realization", (Interface) interfaceClassifier);
                    pendingRelations.add(new PendingRelation(qualifiedName, interfaceQualifiedName, "REALIZATION"));
                    log.debug("创建接口实现: {} -> {}", qualifiedName, interfaceQualifiedName);
                } else if (classifier instanceof Interface) {
                    // 接口继承接口
                    Generalization generalization = ((Interface) classifier).createGeneralization((Interface) interfaceClassifier);
                    pendingRelations.add(new PendingRelation(qualifiedName, interfaceQualifiedName, "GENERALIZATION"));
                    log.debug("创建接口继承: {} -> {}", qualifiedName, interfaceQualifiedName);
                }
            }
        });
    }

    /**
     * 解析完全限定名
     */
    private String resolveQualifiedName(String typeName, String currentPackage) {
        // 先检查是否已经是完全限定名
        if (classifierMap.containsKey(typeName)) {
            return typeName;
        }

        // 尝试在当前包中查找
        String qualifiedName = currentPackage + "." + typeName;
        if (classifierMap.containsKey(qualifiedName)) {
            return qualifiedName;
        }

        // 尝试在所有包中查找匹配的简单名称
        for (String key : classifierMap.keySet()) {
            if (key.endsWith("." + typeName)) {
                return key;
            }
        }

        // 默认返回简单名称
        return typeName;
    }

    /**
     * 创建UML枚举
     * 针对UML2 5.0.1版本的API
     */
    private void createUMLEnumeration(EnumDeclaration enumDecl, Package umlPackage, String packageName) {
        String enumName = enumDecl.getNameAsString();
        String qualifiedName = packageName + "." + enumName;

        // 明确使用org.eclipse.uml2.uml.Enumeration，避免与java.util.Enumeration冲突
        org.eclipse.uml2.uml.Enumeration umlEnum = umlPackage.createOwnedEnumeration(enumName);

        // UML2 5.0.1版本：使用UMLFactory创建枚举字面量
        for (EnumConstantDeclaration constant : enumDecl.getEntries()) {
            EnumerationLiteral literal = UMLFactory.eINSTANCE.createEnumerationLiteral();
            literal.setName(constant.getNameAsString());
            umlEnum.getOwnedLiterals().add(literal);
            log.debug("创建枚举字面量: {}.{}", enumName, constant.getNameAsString());
        }

        classifierMap.put(qualifiedName, umlEnum);
        log.debug("创建枚举: {}", qualifiedName);
    }

    /**
     * 创建UML属性（字段）
     */
    private void createUMLProperty(Class umlClass, VariableDeclarator var, FieldDeclaration field) {
        String propertyName = var.getNameAsString();
        Property property = umlClass.createOwnedAttribute(propertyName, null);

        // 设置类型
        Type propertyType = getUMLType(var.getTypeAsString());
        property.setType(propertyType);

        // 设置可见性
        if (field.isPrivate()) {
            property.setVisibility(VisibilityKind.PRIVATE_LITERAL);
        } else if (field.isPublic()) {
            property.setVisibility(VisibilityKind.PUBLIC_LITERAL);
        } else if (field.isProtected()) {
            property.setVisibility(VisibilityKind.PROTECTED_LITERAL);
        } else {
            property.setVisibility(VisibilityKind.PACKAGE_LITERAL);
        }

        // 设置静态修饰符
        if (field.isStatic()) {
            property.setIsStatic(true);
        }

        // 设置final修饰符
        if (field.isFinal()) {
            property.setIsReadOnly(true);
        }
    }

    /**
     * 为类创建UML操作（方法）
     * UML2 5.0.1版本API
     */
    private void createUMLOperationForClass(Class umlClass, MethodDeclaration method) {
        String operationName = method.getNameAsString();
        Operation operation = umlClass.createOwnedOperation(operationName, null, null);

        // 设置返回类型
        if (!method.getType().isVoidType()) {
            Type returnType = getUMLType(method.getTypeAsString());
            // 明确使用org.eclipse.uml2.uml.Parameter，避免与com.github.javaparser.ast.body.Parameter冲突
            org.eclipse.uml2.uml.Parameter returnParam = UMLFactory.eINSTANCE.createParameter();
            returnParam.setName("return");
            returnParam.setType(returnType);
            returnParam.setDirection(ParameterDirectionKind.RETURN_LITERAL);
            operation.getOwnedParameters().add(returnParam);
        }

        // 添加参数
        // 这里的param是com.github.javaparser.ast.body.Parameter
        for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
            String paramName = param.getNameAsString();
            Type paramType = getUMLType(param.getTypeAsString());
            // 创建UML2的Parameter
            org.eclipse.uml2.uml.Parameter umlParam = UMLFactory.eINSTANCE.createParameter();
            umlParam.setName(paramName);
            umlParam.setType(paramType);
            umlParam.setDirection(ParameterDirectionKind.IN_LITERAL);
            operation.getOwnedParameters().add(umlParam);
        }

        // 设置可见性
        if (method.isPrivate()) {
            operation.setVisibility(VisibilityKind.PRIVATE_LITERAL);
        } else if (method.isPublic()) {
            operation.setVisibility(VisibilityKind.PUBLIC_LITERAL);
        } else if (method.isProtected()) {
            operation.setVisibility(VisibilityKind.PROTECTED_LITERAL);
        } else {
            operation.setVisibility(VisibilityKind.PACKAGE_LITERAL);
        }

        // 设置抽象和静态修饰符
        if (method.isAbstract()) {
            operation.setIsAbstract(true);
        }
        if (method.isStatic()) {
            operation.setIsStatic(true);
        }
    }

    /**
     * 为接口创建UML操作（方法）
     * UML2 5.0.1版本 - Interface不是BehavioredClassifier，需要单独处理
     */
    private void createUMLOperationForInterface(Interface umlInterface, MethodDeclaration method) {
        String operationName = method.getNameAsString();
        Operation operation = umlInterface.createOwnedOperation(operationName, null, null);

        // 设置返回类型
        if (!method.getType().isVoidType()) {
            Type returnType = getUMLType(method.getTypeAsString());
            // 明确使用org.eclipse.uml2.uml.Parameter
            org.eclipse.uml2.uml.Parameter returnParam = UMLFactory.eINSTANCE.createParameter();
            returnParam.setName("return");
            returnParam.setType(returnType);
            returnParam.setDirection(ParameterDirectionKind.RETURN_LITERAL);
            operation.getOwnedParameters().add(returnParam);
        }

        // 添加参数
        for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
            String paramName = param.getNameAsString();
            Type paramType = getUMLType(param.getTypeAsString());
            // 创建UML2的Parameter
            org.eclipse.uml2.uml.Parameter umlParam = UMLFactory.eINSTANCE.createParameter();
            umlParam.setName(paramName);
            umlParam.setType(paramType);
            umlParam.setDirection(ParameterDirectionKind.IN_LITERAL);
            operation.getOwnedParameters().add(umlParam);
        }

        // 设置可见性（接口方法默认为public）
        operation.setVisibility(VisibilityKind.PUBLIC_LITERAL);

        // 接口方法默认为抽象
        operation.setIsAbstract(true);
    }

    /**
     * 获取UML类型
     */
    private Type getUMLType(String typeName) {
        // 保存原始类型名（包含泛型）用于日志
        String originalTypeName = typeName;

        // 移除泛型
        typeName = typeName.replaceAll("<.*>", "").trim();

        switch (typeName) {
            case "int":
            case "Integer":
                return intType;
            case "long":
            case "Long":
                return longType;
            case "double":
            case "Double":
                return doubleType;
            case "float":
            case "Float":
                return floatType;
            case "boolean":
            case "Boolean":
                return booleanType;
            case "String":
                return stringType;
            case "void":
            case "Void":
                return null; // void类型返回null
            default:
                // 尝试查找已定义的分类器
                Classifier found = findClassifier(typeName);
                if (found != null) {
                    log.debug("找到类型引用: {} -> {}", originalTypeName, found.getQualifiedName());
                    return found;
                }

                // 如果找不到，创建一个占位符类型（而不是返回stringType）
                log.debug("未找到类型定义: {}，创建占位符", typeName);
                return createPlaceholderType(typeName);
        }
    }

    /**
     * 查找分类器（改进的查找逻辑）
     */
    private Classifier findClassifier(String typeName) {
        // 1. 精确匹配完全限定名
        if (classifierMap.containsKey(typeName)) {
            return classifierMap.get(typeName);
        }

        // 2. 匹配简单名称（类名）
        List<Classifier> candidates = new ArrayList<>();
        for (Map.Entry<String, Classifier> entry : classifierMap.entrySet()) {
            String qualifiedName = entry.getKey();
            String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            if (simpleName.equals(typeName)) {
                candidates.add(entry.getValue());
            }
        }

        // 如果只有一个候选，直接返回
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // 如果有多个候选，记录警告
        if (candidates.size() > 1) {
            log.warn("类型 {} 有多个匹配: {}", typeName,
                    candidates.stream().map(c -> c.getQualifiedName()).collect(Collectors.joining(", ")));
            return candidates.get(0); // 返回第一个
        }

        return null;
    }

    /**
     * 创建占位符类型（用于未解析的类型）
     * 对于项目内的类，创建Class占位符并放入当前处理的包中，避免重复定义和default包膨胀
     */
    private Type createPlaceholderType(String typeName) {
        // 在model中查找是否已存在同名PrimitiveType
        for (org.eclipse.uml2.uml.Type type : model.getOwnedTypes()) {
            if (type instanceof PrimitiveType && type.getName().equals(typeName)) {
                // 如果是项目类，不应返回PrimitiveType，继续往下走创建Class占位符
                if (!isProjectClass(typeName)) {
                    return (PrimitiveType) type;
                }
            }
        }

        // 如果是项目内的类，创建Class占位符并放入当前处理的包中
        if (isProjectClass(typeName)) {
            // 使用当前正在处理的包名作为占位符类的归属包
            String packageName = currentProcessingPackage != null ? currentProcessingPackage : "default";
            String qualifiedKey = packageName + "." + typeName;
            Package targetPackage;

            // 检查是否已经用限定名注册过
            if (classifierMap.containsKey(qualifiedKey)) {
                return classifierMap.get(qualifiedKey);
            }

            // 也检查是否在其他包中已存在（通过简单名匹配）
            for (Map.Entry<String, Classifier> entry : classifierMap.entrySet()) {
                if (entry.getKey().endsWith("." + typeName)) {
                    return entry.getValue();
                }
            }

            if (!"default".equals(packageName)) {
                targetPackage = getOrCreatePackage(packageName);
            } else {
                targetPackage = rootPackage;
            }

            log.warn("类型 {} 是项目内的类但在classifierMap中未找到，创建Class占位符到包 {}", typeName, targetPackage.getName());
            Class placeholder = targetPackage.createOwnedClass(typeName, false);
            classifierMap.put(qualifiedKey, placeholder);
            return placeholder;
        }

        // 非项目类，创建PrimitiveType
        PrimitiveType placeholder = model.createOwnedPrimitiveType(typeName);
        log.debug("创建占位符类型: {}", typeName);
        return placeholder;
    }


    /**
     * 判断类型名是否为项目内的类（动态判断版：基于classifierMap查找）
     * 如果类型名在classifierMap中存在（通过简单名匹配），则为项目类
     */
    private boolean isProjectClass(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return false;
        }

        // 排除明确的Java标准库类型
        if (isJavaBasicType(typeName) || isCollectionType(typeName)) {
            return false;
        }

        // 动态查找：检查classifierMap中是否有匹配的简单名
        for (String key : classifierMap.keySet()) {
            String simpleName = key.substring(key.lastIndexOf('.') + 1);
            if (simpleName.equals(typeName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 保存完整的UML模型
     */
    private String saveUMLModel(File outputDir, String filename) throws IOException {
        String filePath = new File(outputDir, filename).getAbsolutePath();

        Resource resource = resourceSet.createResource(URI.createFileURI(filePath));
        resource.getContents().add(model);

        Map<String, Object> saveOptions = new HashMap<>();
        saveOptions.put(UMLResource.OPTION_SAVE_ONLY_IF_CHANGED,
                UMLResource.OPTION_SAVE_ONLY_IF_CHANGED_MEMORY_BUFFER);
        // UML2 5.0.1版本使用标准EMF选项
        saveOptions.put(org.eclipse.emf.ecore.xmi.XMLResource.OPTION_ENCODING, "UTF-8");

        resource.save(saveOptions);

        log.info("UML模型已保存到: {}", filePath);
        return filePath;
    }

    /**
     * 保存包视图
     */
    private String savePackageView(File outputDir, String filename) throws IOException {
        String filePath = new File(outputDir, filename).getAbsolutePath();

        // 先创建包间依赖关系（在拷贝之前，这样依赖会被包含在拷贝中）
        createPackageDependencies();

        // 创建包视图专用 Model
        Model packageViewModel = UMLFactory.eINSTANCE.createModel();
        packageViewModel.setName("PackageView");

        // 深拷贝 rootPackage，避免从主 model 中移除
        Package rootCopy = EcoreUtil.copy(rootPackage);
        packageViewModel.getPackagedElements().add(rootCopy);

        Resource resource = resourceSet.createResource(URI.createFileURI(filePath));
        resource.getContents().add(packageViewModel);

        Map<String, Object> saveOptions = new HashMap<>();
        saveOptions.put(UMLResource.OPTION_SAVE_ONLY_IF_CHANGED,
                UMLResource.OPTION_SAVE_ONLY_IF_CHANGED_MEMORY_BUFFER);
        saveOptions.put(org.eclipse.emf.ecore.xmi.XMLResource.OPTION_ENCODING, "UTF-8");

        resource.save(saveOptions);

        log.info("包视图已保存到: {}", filePath);
        return filePath;
    }

    /**
     * 保存类视图
     */
    private String saveClassView(File outputDir, String filename) throws IOException {
        String filePath = new File(outputDir, filename).getAbsolutePath();

        // 创建类视图专用 Model
        Model classViewModel = UMLFactory.eINSTANCE.createModel();
        classViewModel.setName("ClassView");

        // 构建包层次结构，放置分类器拷贝
        Map<String, Package> classViewPackageMap = new HashMap<>();

        for (Map.Entry<String, Classifier> entry : classifierMap.entrySet()) {
            String qualifiedName = entry.getKey();
            Classifier classifier = entry.getValue();

            // 从限定名提取包名
            int lastDot = qualifiedName.lastIndexOf('.');
            String packageName = lastDot > 0 ? qualifiedName.substring(0, lastDot) : "default";

            // 获取或创建包层次
            Package targetPackage = getOrCreateClassViewPackage(
                    classViewModel, classViewPackageMap, packageName);

            // 深拷贝分类器
            Classifier classifierCopy = EcoreUtil.copy(classifier);
            targetPackage.getPackagedElements().add(classifierCopy);
        }

        // 拷贝 Association 关系
        for (PackageableElement element : model.getPackagedElements()) {
            if (element instanceof Association) {
                Association assocCopy = EcoreUtil.copy((Association) element);
                classViewModel.getPackagedElements().add(assocCopy);
            }
        }

        Resource resource = resourceSet.createResource(URI.createFileURI(filePath));
        resource.getContents().add(classViewModel);

        Map<String, Object> saveOptions = new HashMap<>();
        saveOptions.put(UMLResource.OPTION_SAVE_ONLY_IF_CHANGED,
                UMLResource.OPTION_SAVE_ONLY_IF_CHANGED_MEMORY_BUFFER);
        saveOptions.put(org.eclipse.emf.ecore.xmi.XMLResource.OPTION_ENCODING, "UTF-8");

        resource.save(saveOptions);

        log.info("类视图已保存到: {}", filePath);
        return filePath;
    }

    /**
     * 获取或创建类视图中的包层次结构
     */
    private Package getOrCreateClassViewPackage(
            Model classViewModel,
            Map<String, Package> classViewPackageMap,
            String packageName) {

        if (classViewPackageMap.containsKey(packageName)) {
            return classViewPackageMap.get(packageName);
        }

        String[] parts = packageName.split("\\.");
        Package currentPackage = null;
        StringBuilder currentPath = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) currentPath.append(".");
            currentPath.append(parts[i]);
            String path = currentPath.toString();

            if (!classViewPackageMap.containsKey(path)) {
                Package newPackage;
                if (currentPackage == null) {
                    newPackage = classViewModel.createNestedPackage(parts[i]);
                } else {
                    newPackage = currentPackage.createNestedPackage(parts[i]);
                }
                classViewPackageMap.put(path, newPackage);
                currentPackage = newPackage;
            } else {
                currentPackage = classViewPackageMap.get(path);
            }
        }

        return currentPackage;
    }

    /**
     * 创建关联关系（Association）
     * 基于属性类型创建类之间的关联，使用pendingRelations中的enriched类型设置AggregationKind
     */
    private void createAssociations() {
        int associationCount = 0;
        int skippedPrimitiveCount = 0;
        int skippedCollectionCount = 0;

        // 构建(source|target) -> relationType查找表
        Map<String, String> relationTypeMap = new HashMap<>();
        for (PendingRelation pr : pendingRelations) {
            String key = pr.source + "|" + pr.target;
            relationTypeMap.put(key, pr.type);
        }

        for (Classifier classifier : classifierMap.values()) {
            if (!(classifier instanceof Class)) {
                continue; // 只处理类
            }

            Class umlClass = (Class) classifier;
            String sourceQualifiedName = getQualifiedNameForClassifier(umlClass);

            // 遍历类的所有属性
            for (Property property : umlClass.getOwnedAttributes()) {
                Type propertyType = property.getType();

                // 跳过基本类型（String, Integer等）
                if (propertyType instanceof PrimitiveType) {
                    String typeName = propertyType.getName();
                    if (isJavaBasicType(typeName)) {
                        log.debug("跳过基本类型关联: {}.{} : {}",
                                umlClass.getName(), property.getName(), typeName);
                        skippedPrimitiveCount++;
                        continue;
                    }
                    // 如果PrimitiveType不是基本类型，尝试替换为classifierMap中的Class
                    Classifier classVersion = findClassifier(typeName);
                    if (classVersion != null && classVersion instanceof Class) {
                        propertyType = classVersion;
                        property.setType(propertyType);
                    } else {
                        continue; // 找不到对应Class，跳过
                    }
                }

                // 跳过集合类型（List, Set, Map等）
                if (propertyType != null && isCollectionType(propertyType.getName())) {
                    log.debug("跳过集合类型关联: {}.{} : {}",
                            umlClass.getName(), property.getName(), propertyType.getName());
                    skippedCollectionCount++;
                    continue;
                }

                // 如果属性类型是另一个类，创建关联
                if (propertyType instanceof Classifier && propertyType != umlClass) {
                    Classifier targetClassifier = (Classifier) propertyType;

                    // 验证目标类型是否在classifierMap中注册（排除悬空的占位符类型）
                    if (!classifierMap.containsValue(targetClassifier)) {
                        log.debug("跳过未注册的目标类型关联: {}.{} : {}",
                                umlClass.getName(), property.getName(), targetClassifier.getName());
                        continue;
                    }

                    // 检查是否已存在关联
                    if (!hasAssociation(umlClass, targetClassifier)) {
                        String targetQualifiedName = getQualifiedNameForClassifier(targetClassifier);
                        String lookupKey = sourceQualifiedName + "|" + targetQualifiedName;
                        String relType = relationTypeMap.getOrDefault(lookupKey, "ASSOCIATION");
                        createAssociation(umlClass, targetClassifier, property, relType);
                        associationCount++;
                    }
                }
            }
        }

        log.info("创建了 {} 个关联关系", associationCount);
        log.info("跳过了 {} 个基本类型关联", skippedPrimitiveCount);
        log.info("跳过了 {} 个集合类型关联", skippedCollectionCount);
    }

    /**
     * 判断是否为Java基本类型（扩展版）
     */
    private boolean isJavaBasicType(String typeName) {
        if (typeName == null) return false;
        // 完全限定名以java./javax.开头的直接判定
        if (typeName.startsWith("java.") || typeName.startsWith("javax.")) {
            return true;
        }
        Set<String> basicTypes = new HashSet<>(Arrays.asList(
            "String", "Integer", "Boolean", "Double", "Float", "Long", "Short", "Byte", "Character",
            "int", "boolean", "double", "float", "long", "short", "byte", "char",
            "Object", "String[]", "int[]", "boolean[]", "double[]", "long[]", "byte[]", "char[]",
            "Number", "Void", "void",
            // 数值类型
            "BigDecimal", "BigInteger", "AtomicInteger", "AtomicLong",
            // 日期时间
            "Date", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
            "ZonedDateTime", "OffsetDateTime", "Duration", "Period", "Calendar", "Timestamp",
            // 其他常用类型
            "UUID", "Pattern", "Matcher", "URI", "URL",
            "File", "Path", "InputStream", "OutputStream", "Reader", "Writer",
            "StringBuilder", "StringBuffer",
            "Class", "Enum", "Annotation",
            "Throwable", "Exception", "RuntimeException", "IOException", "Error",
            // 函数式接口
            "Optional", "Stream", "CompletableFuture", "Future",
            "Supplier", "Consumer", "Function", "Predicate", "BiFunction",
            "BiConsumer", "BiPredicate", "UnaryOperator", "BinaryOperator",
            // 迭代器和标记接口
            "Iterator", "Iterable", "Comparable", "Comparator", "Serializable", "Cloneable",
            "Runnable", "Callable"
        ));
        return basicTypes.contains(typeName);
    }

    /**
     * 判断是否为集合类型（扩展版）
     */
    private boolean isCollectionType(String typeName) {
        if (typeName == null) return false;
        Set<String> collectionTypes = new HashSet<>(Arrays.asList(
            "List", "ArrayList", "LinkedList", "CopyOnWriteArrayList",
            "Set", "HashSet", "TreeSet", "LinkedHashSet", "EnumSet", "CopyOnWriteArraySet",
            "Map", "HashMap", "TreeMap", "LinkedHashMap", "ConcurrentHashMap", "EnumMap", "WeakHashMap",
            "Collection", "Queue", "Deque", "Stack", "Vector",
            "ArrayDeque", "PriorityQueue", "ConcurrentLinkedQueue", "ConcurrentLinkedDeque",
            "BlockingQueue", "LinkedBlockingQueue", "ArrayBlockingQueue",
            "NavigableMap", "NavigableSet", "SortedMap", "SortedSet"
        ));
        return collectionTypes.contains(typeName);
    }

    /**
     * 检查两个类之间是否已存在关联
     */
    private boolean hasAssociation(Class source, Classifier target) {
        for (Association assoc : source.getAssociations()) {
            for (Property end : assoc.getMemberEnds()) {
                if (end.getType() == target) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 创建关联关系，使用enriched关系类型设置AggregationKind
     */
    private void createAssociation(Class source, Classifier target, Property sourceProperty, String relationType) {
        try {
            // 创建关联
            Association association = (Association) model.createPackagedElement(
                    source.getName() + "_" + target.getName() + "_Association",
                    UMLPackage.Literals.ASSOCIATION
            );

            // 设置关联的两端
            // 源端（拥有属性的类）
            Property sourceEnd = association.createOwnedEnd(
                    source.getName().toLowerCase(),
                    source
            );
            sourceEnd.setAggregation(AggregationKind.NONE_LITERAL);

            // 目标端（属性类型）
            Property targetEnd = association.createOwnedEnd(
                    sourceProperty.getName(),
                    target
            );

            // 根据enriched关系类型设置聚合类型
            if ("COMPOSITION".equals(relationType)) {
                targetEnd.setAggregation(AggregationKind.COMPOSITE_LITERAL);
            } else if ("AGGREGATION".equals(relationType)) {
                targetEnd.setAggregation(AggregationKind.SHARED_LITERAL);
            } else {
                targetEnd.setAggregation(AggregationKind.NONE_LITERAL);
            }

            // 设置多重性
            setMultiplicity(targetEnd, sourceProperty);

            log.debug("创建关联: {} -> {} ({})",
                    source.getName(), target.getName(), targetEnd.getAggregation());

        } catch (Exception e) {
            log.error("创建关联失败: {} -> {}", source.getName(), target.getName(), e);
        }
    }

    /**
     * 根据Classifier对象反查其在classifierMap中的限定名
     */
    private String getQualifiedNameForClassifier(Classifier classifier) {
        for (Map.Entry<String, Classifier> entry : classifierMap.entrySet()) {
            if (entry.getValue() == classifier) {
                return entry.getKey();
            }
        }
        return classifier.getName();
    }

    /**
     * 关系去重：按(source, target)去重，保留优先级最高的类型
     * 优先级: GENERALIZATION > REALIZATION > COMPOSITION > AGGREGATION > ASSOCIATION > DEPENDENCY
     */
    private void deduplicatePendingRelations() {
        Map<String, Integer> priority = new HashMap<>();
        priority.put("GENERALIZATION", 6);
        priority.put("REALIZATION", 5);
        priority.put("COMPOSITION", 4);
        priority.put("AGGREGATION", 3);
        priority.put("ASSOCIATION", 2);
        priority.put("DEPENDENCY", 1);

        Map<String, PendingRelation> best = new LinkedHashMap<>();
        for (PendingRelation pr : pendingRelations) {
            String key = pr.source + "|" + pr.target;
            PendingRelation existing = best.get(key);
            if (existing == null ||
                priority.getOrDefault(pr.type, 0) > priority.getOrDefault(existing.type, 0)) {
                best.put(key, pr);
            }
        }

        pendingRelations = new ArrayList<>(best.values());
        log.info("关系去重完成，去重后关系数: {}", pendingRelations.size());
    }

    /**
     * 设置属性的多重性（改进版：分析注解推断更精确的多重性）
     * - @NotNull/@NonNull/@Required → 下界为1
     * - @Size(min=1) → 下界为1
     * - Optional<Foo> → 0..1
     * - 数组类型 Foo[] → 0..*
     */
    private void setMultiplicity(Property property, Property sourceProperty) {
        String typeName = sourceProperty.getType() != null ?
                sourceProperty.getType().getName() : "";

        // 收集源属性上的stereotype/注释信息来推断注解
        boolean hasNotNull = false;
        boolean hasSizeMin = false;

        // 检查属性注释中是否包含注解信息
        for (Comment comment : sourceProperty.getOwnedComments()) {
            String body = comment.getBody();
            if (body != null) {
                if (body.contains("@NotNull") || body.contains("@NonNull") || body.contains("@Required")) {
                    hasNotNull = true;
                }
                if (body.contains("@Size") && body.contains("min=1")) {
                    hasSizeMin = true;
                }
            }
        }

        // Optional类型 → 0..1
        if ("Optional".equals(typeName)) {
            property.setLower(0);
            property.setUpper(1);
            return;
        }

        // 数组类型
        if (typeName.endsWith("[]") || typeName.contains("Array")) {
            property.setLower(hasSizeMin ? 1 : 0);
            property.setUpper(-1);
            return;
        }

        // 集合类型
        if (typeName.contains("List") || typeName.contains("Set") ||
            typeName.contains("Collection") || typeName.contains("Queue") ||
            typeName.contains("Deque")) {
            property.setLower(hasSizeMin ? 1 : 0);
            property.setUpper(-1); // -1 表示无限制 (*)
            return;
        }

        // Map类型
        if (typeName.contains("Map")) {
            property.setLower(0);
            property.setUpper(-1);
            return;
        }

        // 单个对象
        if (hasNotNull) {
            // @NotNull → 1..1（必须存在）
            property.setLower(1);
            property.setUpper(1);
        } else if (sourceProperty.isReadOnly()) {
            // final字段通常不为null → 1..1
            property.setLower(1);
            property.setUpper(1);
        } else {
            // 默认 → 0..1
            property.setLower(0);
            property.setUpper(1);
        }
    }

    /**
     * 创建包间依赖关系（改进版：引入依赖权重，过滤弱依赖，检测循环依赖）
     */
    private void createPackageDependencies() {
        log.info("开始分析包间依赖关系...");

        // 统计包间依赖权重（类引用数量）
        Map<String, Map<String, Integer>> packageDepWeights = new HashMap<>();

        // 分析每个类的依赖，推断包间依赖并累加权重
        for (Map.Entry<String, Classifier> entry : classifierMap.entrySet()) {
            String qualifiedName = entry.getKey();
            Classifier classifier = entry.getValue();

            Package sourcePackage = getPackageForClassifier(qualifiedName);
            if (sourcePackage == null) {
                continue;
            }
            String sourceKey = getPackageKey(sourcePackage);
            if (sourceKey == null) continue;

            if (classifier instanceof Class) {
                Class umlClass = (Class) classifier;

                // 从属性类型推断依赖
                for (Property property : umlClass.getOwnedAttributes()) {
                    Type propertyType = property.getType();
                    if (propertyType instanceof Classifier) {
                        Package targetPackage = propertyType.getNearestPackage();
                        addPackageDepWeight(packageDepWeights, sourceKey, sourcePackage, targetPackage);
                    }
                }

                // 从方法参数和返回类型推断依赖
                for (Operation operation : umlClass.getOwnedOperations()) {
                    for (org.eclipse.uml2.uml.Parameter param : operation.getOwnedParameters()) {
                        Type paramType = param.getType();
                        if (paramType instanceof Classifier) {
                            Package targetPackage = paramType.getNearestPackage();
                            addPackageDepWeight(packageDepWeights, sourceKey, sourcePackage, targetPackage);
                        }
                    }
                }

                // 从继承关系推断依赖
                for (Generalization gen : umlClass.getGeneralizations()) {
                    Classifier general = gen.getGeneral();
                    if (general != null) {
                        Package targetPackage = general.getNearestPackage();
                        addPackageDepWeight(packageDepWeights, sourceKey, sourcePackage, targetPackage);
                    }
                }
            }
        }

        // 检测循环依赖（Tarjan SCC）
        Set<String> circularPairs = detectCircularDependencies(packageDepWeights);

        // 最小权重阈值：过滤仅1个类引用的弱依赖
        final int MIN_WEIGHT = 1;

        // 创建包间的Usage依赖
        int dependencyCount = 0;
        int skippedCount = 0;
        int weakFilteredCount = 0;

        for (Map.Entry<String, Map<String, Integer>> entry : packageDepWeights.entrySet()) {
            String sourceKey = entry.getKey();
            Package sourcePackage = packageMap.get(sourceKey);
            if (sourcePackage == null) continue;

            for (Map.Entry<String, Integer> depEntry : entry.getValue().entrySet()) {
                String targetKey = depEntry.getKey();
                int weight = depEntry.getValue();
                Package targetPackage = packageMap.get(targetKey);

                if (targetPackage == null) {
                    skippedCount++;
                    continue;
                }

                // 过滤弱依赖（权重低于阈值）
                if (weight <= MIN_WEIGHT) {
                    log.debug("过滤弱依赖: {} -> {} (权重={})", sourceKey, targetKey, weight);
                    weakFilteredCount++;
                    continue;
                }

                try {
                    Usage usage = (Usage) sourcePackage.createPackagedElement(
                            sourcePackage.getName() + "_uses_" + targetPackage.getName(),
                            UMLPackage.Literals.USAGE
                    );
                    usage.getClients().add(sourcePackage);
                    usage.getSuppliers().add(targetPackage);

                    // 添加权重注释
                    Comment weightComment = usage.createOwnedComment();
                    weightComment.setBody("weight=" + weight);

                    // 标注循环依赖
                    String pairKey = sourceKey + "|" + targetKey;
                    if (circularPairs.contains(pairKey)) {
                        Comment circularComment = usage.createOwnedComment();
                        circularComment.setBody("<<circular>>");
                        log.warn("循环依赖: {} <-> {} (权重={})", sourceKey, targetKey, weight);
                    }

                    dependencyCount++;
                    log.debug("创建包依赖: {} -> {} (权重={})", sourceKey, targetKey, weight);
                } catch (Exception e) {
                    log.warn("创建包依赖失败: {} -> {}, 错误: {}", sourceKey, targetKey, e.getMessage());
                    skippedCount++;
                }
            }
        }

        log.info("创建了 {} 个包间依赖关系", dependencyCount);
        log.info("过滤了 {} 个弱依赖（权重<={}）", weakFilteredCount, MIN_WEIGHT);
        log.info("跳过了 {} 个无效依赖", skippedCount);
        if (!circularPairs.isEmpty()) {
            log.warn("检测到 {} 对循环依赖", circularPairs.size());
        }
    }

    /**
     * 获取包在packageMap中的key
     */
    private String getPackageKey(Package pkg) {
        for (Map.Entry<String, Package> entry : packageMap.entrySet()) {
            if (entry.getValue() == pkg) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 累加包间依赖权重
     */
    private void addPackageDepWeight(Map<String, Map<String, Integer>> weights,
                                     String sourceKey, Package sourcePackage, Package targetPackage) {
        if (targetPackage == null || targetPackage == sourcePackage) return;
        if (isExternalPackage(targetPackage)) return;

        String targetKey = getPackageKey(targetPackage);
        if (targetKey == null || targetKey.equals(sourceKey)) return;

        weights.computeIfAbsent(sourceKey, k -> new HashMap<>())
               .merge(targetKey, 1, Integer::sum);
    }

    /**
     * 检测循环依赖：找出所有互相依赖的包对
     * 使用简单的双向检测（A->B且B->A即为循环）
     */
    private Set<String> detectCircularDependencies(Map<String, Map<String, Integer>> depWeights) {
        Set<String> circularPairs = new HashSet<>();

        for (Map.Entry<String, Map<String, Integer>> entry : depWeights.entrySet()) {
            String source = entry.getKey();
            for (String target : entry.getValue().keySet()) {
                // 检查反向依赖是否存在
                Map<String, Integer> reverseDeps = depWeights.get(target);
                if (reverseDeps != null && reverseDeps.containsKey(source)) {
                    // 用排序后的key避免重复
                    String key1 = source + "|" + target;
                    String key2 = target + "|" + source;
                    circularPairs.add(key1);
                    circularPairs.add(key2);
                }
            }
        }

        return circularPairs;
    }

    /**
     * 判断是否为外部包（如java.lang等）
     */
    private boolean isExternalPackage(Package pkg) {
        if (pkg == null || pkg.getName() == null) {
            return true;
        }

        // 直接用对象引用检查是否在packageMap中（最可靠的方式）
        for (Package mapPkg : packageMap.values()) {
            if (mapPkg == pkg) {
                return false;
            }
        }

        // 也检查是否是rootPackage本身
        if (pkg == rootPackage) {
            return false;
        }

        String simpleName = pkg.getName();

        // 检查是否为Java标准库或外部包
        Set<String> externalNames = new HashSet<>(Arrays.asList(
            "java", "javax", "sun", "org", "JavaCodeModel"
        ));
        if (externalNames.contains(simpleName)) {
            return true;
        }

        // 使用名称匹配作为后备（处理占位符类所在包等情况）
        for (Map.Entry<String, Package> mapEntry : packageMap.entrySet()) {
            if (mapEntry.getValue().getName().equals(simpleName)) {
                return false;
            }
        }

        log.debug("判定为外部包: {} (qualifiedName={})", simpleName, pkg.getQualifiedName());
        return true;
    }

    /**
     * 获取分类器所在的包
     */
    private Package getPackageForClassifier(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot > 0) {
            String packageName = qualifiedName.substring(0, lastDot);
            return packageMap.get(packageName);
        }
        return null;
    }
    /**
     * 提取字段类型引用，区分COMPOSITION/AGGREGATION/ASSOCIATION关系
     * - 集合字段（List<Foo>, Set<Foo>, Map<K,Foo>）→ AGGREGATION
     * - 非集合字段且private final或命名暗示所有权 → COMPOSITION
     * - 其余非集合字段 → ASSOCIATION
     */
    private void extractAssociationRelations(ClassOrInterfaceDeclaration classDecl, String packageName) {
        String sourceQualified = packageName + "." + classDecl.getNameAsString();
        classDecl.getFields().forEach(field -> {
            field.getVariables().forEach(var -> {
                String rawType = var.getTypeAsString();
                String baseType = rawType.replaceAll("<.*>", "").trim();
                boolean isCollection = isCollectionType(baseType);

                // 确定目标类型
                String targetTypeName;
                if (isCollection) {
                    // 提取泛型参数: List<Foo> -> Foo
                    targetTypeName = extractGenericTypeArgument(rawType);
                    if (targetTypeName == null) {
                        return; // 无泛型参数的原始集合类型，跳过
                    }
                } else {
                    targetTypeName = baseType;
                }

                if (isJavaBasicType(targetTypeName)) {
                    return;
                }

                String targetQualified = resolveQualifiedName(targetTypeName, packageName);
                if (!classifierMap.containsKey(targetQualified)) {
                    return;
                }

                // 分类关系类型
                String relationType;
                if (isCollection) {
                    relationType = "AGGREGATION";
                } else if (isCompositionField(field, var)) {
                    relationType = "COMPOSITION";
                } else {
                    relationType = "ASSOCIATION";
                }

                // 推断多重性
                String srcMul = "1";
                String tgtMul;
                if (isCollection) {
                    tgtMul = "0..*";
                } else {
                    // 检查注解和修饰符推断单对象多重性
                    boolean hasNotNull = field.getAnnotations().stream()
                            .anyMatch(a -> {
                                String name = a.getNameAsString();
                                return "NotNull".equals(name) || "NonNull".equals(name) || "Required".equals(name);
                            });
                    boolean isFinal = field.isFinal();
                    if (hasNotNull || isFinal) {
                        tgtMul = "1";
                    } else {
                        tgtMul = "0..1";
                    }
                }

                String fieldName = var.getNameAsString();
                pendingRelations.add(new PendingRelation(
                        sourceQualified, targetQualified, relationType,
                        srcMul, tgtMul, fieldName));
            });
        });
    }

    /**
     * 从参数化类型字符串中提取泛型类型参数
     * "List<Foo>" -> "Foo", "Map<String, Bar>" -> "Bar", "Set<Baz>" -> "Baz"
     */
    private String extractGenericTypeArgument(String typeStr) {
        int ltIdx = typeStr.indexOf('<');
        int gtIdx = typeStr.lastIndexOf('>');
        if (ltIdx < 0 || gtIdx < 0 || gtIdx <= ltIdx) {
            return null;
        }
        String inner = typeStr.substring(ltIdx + 1, gtIdx).trim();

        // 对于Map<K,V>，取值类型（最后一个参数）
        String baseType = typeStr.substring(0, ltIdx).trim();
        if (baseType.equals("Map") || baseType.equals("HashMap") || baseType.equals("TreeMap")
                || baseType.equals("LinkedHashMap") || baseType.equals("ConcurrentHashMap")) {
            String[] parts = inner.split(",");
            if (parts.length >= 2) {
                inner = parts[parts.length - 1].trim();
            }
        }

        // 去除参数自身的嵌套泛型
        inner = inner.replaceAll("<.*>", "").trim();
        return inner.isEmpty() ? null : inner;
    }

    /**
     * 判断字段是否表示组合关系（Composition）
     * 改进版：多信号加权判定，避免将DI注入字段误判为组合
     *
     * | 信号                          | 权重 | 组合 | 聚合 |
     * |-------------------------------|------|------|------|
     * | 字段声明处用new初始化           | 0.4  | ✓    |      |
     * | final且非DI注入                | 0.2  | ✓    |      |
     * | 是内部类                       | 0.3  | ✓    |      |
     * | 通过构造器参数注入              | 0.3  |      | ✓    |
     * | 有@Autowired/@Inject注解       | 0.4  |      | ✓    |
     * | 有setter方法                   | 0.2  |      | ✓    |
     * | 字段可被重新赋值（非final）     | 0.1  |      | ✓    |
     */
    private boolean isCompositionField(FieldDeclaration field, VariableDeclarator var) {
        double compositionScore = 0.0;
        double aggregationScore = 0.0;

        // 检查是否有DI注解（@Autowired, @Inject, @Resource）
        boolean hasDIAnnotation = field.getAnnotations().stream()
                .anyMatch(a -> {
                    String name = a.getNameAsString();
                    return "Autowired".equals(name) || "Inject".equals(name) ||
                           "Resource".equals(name) || "Value".equals(name);
                });

        if (hasDIAnnotation) {
            aggregationScore += 0.4;
        }

        // 检查字段声明处是否用new初始化
        boolean hasNewInit = var.getInitializer()
                .filter(init -> init.isObjectCreationExpr())
                .isPresent();
        if (hasNewInit) {
            compositionScore += 0.4;
        }

        // final且非DI注入
        if (field.isFinal() && !hasDIAnnotation) {
            compositionScore += 0.2;
        }

        // 非final字段可被重新赋值
        if (!field.isFinal()) {
            aggregationScore += 0.1;
        }

        // 命名启发式（暗示强所有权）
        String name = var.getNameAsString().toLowerCase();
        if (name.contains("body") || name.contains("content") ||
            name.contains("child") || name.contains("part") ||
            name.contains("element") || name.contains("item") ||
            name.contains("detail") || name.contains("inner")) {
            compositionScore += 0.2;
        }

        return compositionScore > aggregationScore && compositionScore >= 0.3;
    }

    /**
     * 提取方法参数/返回类型引用作为DEPENDENCY关系
     */
    private void extractDependencyRelations(ClassOrInterfaceDeclaration classDecl, String packageName) {
        String sourceQualified = packageName + "." + classDecl.getNameAsString();
        classDecl.getMethods().forEach(method -> {
            // 返回类型
            if (!method.getType().isVoidType()) {
                String retType = method.getTypeAsString().replaceAll("<.*>", "").trim();
                addDependencyIfResolved(sourceQualified, retType, packageName);
            }
            // 参数类型
            method.getParameters().forEach(param -> {
                String paramType = param.getTypeAsString().replaceAll("<.*>", "").trim();
                addDependencyIfResolved(sourceQualified, paramType, packageName);
            });
        });
    }

    private void addDependencyIfResolved(String source, String typeName, String packageName) {
        if (!isJavaBasicType(typeName) && !isCollectionType(typeName)) {
            String targetQualified = resolveQualifiedName(typeName, packageName);
            if (classifierMap.containsKey(targetQualified) && !targetQualified.equals(source)) {
                pendingRelations.add(new PendingRelation(source, targetQualified, "DEPENDENCY"));
            }
        }
    }

    /**
     * 提取类的文本信息（javadoc、注解、方法名、字段名）
     */
    private void extractClassTextInfo(ClassOrInterfaceDeclaration classDecl, String packageName) {
        String qualifiedName = packageName + "." + classDecl.getNameAsString();
        ClassTextInfo info = new ClassTextInfo();

        // javadoc
        classDecl.getJavadocComment().ifPresent(jd -> info.javadoc = jd.getContent());

        // annotations
        info.annotations = classDecl.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .collect(Collectors.toList());

        // method names
        info.methodNames = classDecl.getMethods().stream()
                .map(MethodDeclaration::getNameAsString)
                .collect(Collectors.toList());

        // field names
        info.fieldNames = classDecl.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getNameAsString())
                .collect(Collectors.toList());

        classTextInfoMap.put(qualifiedName, info);
    }

    // ==================== Getters for new data ====================

    public Map<String, Classifier> getClassifierMap() {
        return classifierMap;
    }

    public Map<String, Package> getPackageMap() {
        return packageMap;
    }

    public List<PendingRelation> getPendingRelations() {
        return pendingRelations;
    }

    public Map<String, ClassTextInfo> getClassTextInfoMap() {
        return classTextInfoMap;
    }

    // ==================== Inner classes ====================

    public static class PendingRelation {
        public final String source;
        public final String target;
        public final String type;
        public final String sourceMultiplicity;
        public final String targetMultiplicity;
        public final String fieldName;

        public PendingRelation(String source, String target, String type) {
            this(source, target, type, null, null, null);
        }

        public PendingRelation(String source, String target, String type,
                               String sourceMultiplicity, String targetMultiplicity, String fieldName) {
            this.source = source;
            this.target = target;
            this.type = type;
            this.sourceMultiplicity = sourceMultiplicity;
            this.targetMultiplicity = targetMultiplicity;
            this.fieldName = fieldName;
        }
    }

    public static class ClassTextInfo {
        public String javadoc;
        public List<String> annotations = new ArrayList<>();
        public List<String> methodNames = new ArrayList<>();
        public List<String> fieldNames = new ArrayList<>();
    }

    private List<Path> findJavaFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        // 过滤macOS资源分支文件（__MACOSX目录和._前缀文件）
                        String pathStr = path.toString().replace('\\', '/');
                        if (pathStr.contains("/__MACOSX/") || pathStr.contains("__MACOSX/")) {
                            return false;
                        }
                        String fileName = path.getFileName().toString();
                        if (fileName.startsWith("._")) {
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * XMI生成结果
     */
    public static class XMIGenerationResult {
        private String modelXMIPath;
        private String packageXMIPath;
        private String classXMIPath;
        private int totalPackages;
        private int totalClasses;

        public String getModelXMIPath() { return modelXMIPath; }
        public void setModelXMIPath(String modelXMIPath) { this.modelXMIPath = modelXMIPath; }
        public String getPackageXMIPath() { return packageXMIPath; }
        public void setPackageXMIPath(String packageXMIPath) { this.packageXMIPath = packageXMIPath; }
        public String getClassXMIPath() { return classXMIPath; }
        public void setClassXMIPath(String classXMIPath) { this.classXMIPath = classXMIPath; }
        public int getTotalPackages() { return totalPackages; }
        public void setTotalPackages(int totalPackages) { this.totalPackages = totalPackages; }
        public int getTotalClasses() { return totalClasses; }
        public void setTotalClasses(int totalClasses) { this.totalClasses = totalClasses; }
    }
}