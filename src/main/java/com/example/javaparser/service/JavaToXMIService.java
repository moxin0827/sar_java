package com.example.javaparser.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java源码解析服务 - 使用JavaParser + EMF生成XMI
 * 生成package.xmi和class.xmi文件
 */
@Slf4j
@Service
public class JavaToXMIService {

    private ResourceSet resourceSet;
    private EPackage rootPackage;
    private Map<String, EPackage> packageMap;
    private Map<String, EClass> classMap;

    /**
     * 初始化EMF资源集
     */
    public void initialize() {
        log.info("初始化EMF资源集...");

        resourceSet = new ResourceSetImpl();
        resourceSet.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());

        // 注册Ecore包
        resourceSet.getPackageRegistry()
                .put(EcorePackage.eINSTANCE.getNsURI(), EcorePackage.eINSTANCE);

        packageMap = new HashMap<>();
        classMap = new HashMap<>();

        log.info("EMF资源集初始化完成");
    }

    /**
     * 解析Java项目并生成XMI文件
     *
     * @param sourceDirectory 源码目录
     * @param outputDirectory 输出目录
     * @return 生成的XMI文件路径
     */
    public XMIGenerationResult parseAndGenerateXMI(String sourceDirectory, String outputDirectory)
            throws IOException {

        if (resourceSet == null) {
            initialize();
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

        // 创建根包模型
        rootPackage = EcoreFactory.eINSTANCE.createEPackage();
        rootPackage.setName("RootPackage");
        rootPackage.setNsPrefix("root");
        rootPackage.setNsURI("http://example.com/root");

        // 查找并解析所有Java文件
        List<Path> javaFiles = findJavaFiles(sourceDir.toPath());
        log.info("找到 {} 个Java文件", javaFiles.size());

        for (Path javaFile : javaFiles) {
            try {
                parseJavaFile(javaFile);
            } catch (Exception e) {
                log.error("解析文件失败: {}, 错误: {}", javaFile, e.getMessage(), e);
            }
        }

        log.info("成功解析 {} 个包, {} 个类", packageMap.size(), classMap.size());

        // 生成XMI文件
        String packageXMI = savePackageModel(outputDir);
        String classXMI = saveClassModel(outputDir);

        XMIGenerationResult result = new XMIGenerationResult();
        result.setPackageXMIPath(packageXMI);
        result.setClassXMIPath(classXMI);
        result.setTotalPackages(packageMap.size());
        result.setTotalClasses(classMap.size());

        return result;
    }

    /**
     * 解析单个Java文件
     */
    private void parseJavaFile(Path javaFilePath) throws IOException {
        log.debug("解析文件: {}", javaFilePath);

        CompilationUnit cu = StaticJavaParser.parse(javaFilePath);

        // 获取包名
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("default");

        // 获取或创建EPackage
        EPackage ePackage = getOrCreatePackage(packageName);

        // 处理所有类型声明
        cu.findAll(TypeDeclaration.class).forEach(typeDecl -> {
            try {
                if (typeDecl.isClassOrInterfaceDeclaration()) {
                    ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();
                    createEClass(classDecl, ePackage, packageName);
                } else if (typeDecl.isEnumDeclaration()) {
                    EnumDeclaration enumDecl = typeDecl.asEnumDeclaration();
                    createEEnum(enumDecl, ePackage, packageName);
                }
            } catch (Exception e) {
                log.error("处理类型声明失败: {}", typeDecl.getNameAsString(), e);
            }
        });
    }

    /**
     * 获取或创建EPackage
     */
    private EPackage getOrCreatePackage(String packageName) {
        if (packageMap.containsKey(packageName)) {
            return packageMap.get(packageName);
        }

        EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
        ePackage.setName(packageName.replace(".", "_"));
        ePackage.setNsPrefix(packageName.replace(".", "_"));
        ePackage.setNsURI("http://example.com/" + packageName);

        rootPackage.getESubpackages().add(ePackage);
        packageMap.put(packageName, ePackage);

        log.debug("创建包: {}", packageName);
        return ePackage;
    }

    /**
     * 创建EClass（类或接口）
     */
    private void createEClass(ClassOrInterfaceDeclaration classDecl,
                              EPackage ePackage,
                              String packageName) {

        String className = classDecl.getNameAsString();
        String qualifiedName = packageName + "." + className;

        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(className);
        eClass.setAbstract(classDecl.isAbstract());
        eClass.setInterface(classDecl.isInterface());

        // 添加到包
        ePackage.getEClassifiers().add(eClass);
        classMap.put(qualifiedName, eClass);

        log.debug("创建类: {}", qualifiedName);

        // 处理字段
        classDecl.getFields().forEach(field -> {
            field.getVariables().forEach(var -> {
                createEAttribute(eClass, var, field);
            });
        });

        // 处理方法
        classDecl.getMethods().forEach(method -> {
            createEOperation(eClass, method);
        });

        // 处理继承关系（将在第二遍处理）
        if (classDecl.getExtendedTypes().isNonEmpty()) {
            classDecl.getExtendedTypes().forEach(extendedType -> {
                // 暂存继承关系，稍后解析
                log.debug("类 {} 继承自 {}", className, extendedType.getNameAsString());
            });
        }

        // 处理实现的接口
        if (classDecl.getImplementedTypes().isNonEmpty()) {
            classDecl.getImplementedTypes().forEach(implementedType -> {
                log.debug("类 {} 实现接口 {}", className, implementedType.getNameAsString());
            });
        }
    }

    /**
     * 创建EEnum（枚举）
     */
    private void createEEnum(EnumDeclaration enumDecl, EPackage ePackage, String packageName) {
        String enumName = enumDecl.getNameAsString();

        EEnum eEnum = EcoreFactory.eINSTANCE.createEEnum();
        eEnum.setName(enumName);

        // 添加枚举常量
        int literal = 0;
        for (EnumConstantDeclaration constant : enumDecl.getEntries()) {
            EEnumLiteral eLiteral = EcoreFactory.eINSTANCE.createEEnumLiteral();
            eLiteral.setName(constant.getNameAsString());
            eLiteral.setValue(literal++);
            eLiteral.setLiteral(constant.getNameAsString());
            eEnum.getELiterals().add(eLiteral);
        }

        ePackage.getEClassifiers().add(eEnum);
        log.debug("创建枚举: {}.{}", packageName, enumName);
    }

    /**
     * 创建EAttribute（字段）
     */
    private void createEAttribute(EClass eClass, VariableDeclarator var, FieldDeclaration field) {
        EAttribute eAttribute = EcoreFactory.eINSTANCE.createEAttribute();
        eAttribute.setName(var.getNameAsString());

        // 设置类型
        String typeName = var.getTypeAsString();
        EDataType eType = getEDataType(typeName);
        eAttribute.setEType(eType);

        // 设置可见性
        if (field.isPrivate()) {
            // private
        } else if (field.isPublic()) {
            // public
        } else if (field.isProtected()) {
            // protected
        }

        eClass.getEStructuralFeatures().add(eAttribute);
    }

    /**
     * 创建EOperation（方法）
     */
    private void createEOperation(EClass eClass, MethodDeclaration method) {
        EOperation eOperation = EcoreFactory.eINSTANCE.createEOperation();
        eOperation.setName(method.getNameAsString());

        // 设置返回类型
        if (method.getType() != null) {
            String returnType = method.getTypeAsString();
            EDataType eType = getEDataType(returnType);
            eOperation.setEType(eType);
        }

        // 添加参数
        method.getParameters().forEach(param -> {
            EParameter eParameter = EcoreFactory.eINSTANCE.createEParameter();
            eParameter.setName(param.getNameAsString());

            String paramType = param.getTypeAsString();
            EDataType eType = getEDataType(paramType);
            eParameter.setEType(eType);

            eOperation.getEParameters().add(eParameter);
        });

        eClass.getEOperations().add(eOperation);
    }

    /**
     * 获取EMF数据类型
     */
    private EDataType getEDataType(String typeName) {
        // 移除泛型
        typeName = typeName.replaceAll("<.*>", "").trim();

        switch (typeName) {
            case "int":
            case "Integer":
                return EcorePackage.Literals.EINT;
            case "long":
            case "Long":
                return EcorePackage.Literals.ELONG;
            case "double":
            case "Double":
                return EcorePackage.Literals.EDOUBLE;
            case "float":
            case "Float":
                return EcorePackage.Literals.EFLOAT;
            case "boolean":
            case "Boolean":
                return EcorePackage.Literals.EBOOLEAN;
            case "String":
                return EcorePackage.Literals.ESTRING;
            case "Date":
                return EcorePackage.Literals.EDATE;
            case "void":
                return null;
            default:
                // 对于自定义类型，使用EString
                return EcorePackage.Literals.ESTRING;
        }
    }

    /**
     * 保存包模型为XMI
     */
    private String savePackageModel(File outputDir) throws IOException {
        String packageXMIPath = new File(outputDir, "package.xmi").getAbsolutePath();

        Resource resource = resourceSet.createResource(
                URI.createFileURI(packageXMIPath)
        );

        resource.getContents().add(rootPackage);

        Map<String, Object> options = new HashMap<>();
        options.put(Resource.OPTION_SAVE_ONLY_IF_CHANGED, Resource.OPTION_SAVE_ONLY_IF_CHANGED_MEMORY_BUFFER);

        resource.save(options);

        log.info("包模型已保存到: {}", packageXMIPath);
        return packageXMIPath;
    }

    /**
     * 保存类模型为XMI
     */
    private String saveClassModel(File outputDir) throws IOException {
        String classXMIPath = new File(outputDir, "class.xmi").getAbsolutePath();

        Resource resource = resourceSet.createResource(
                URI.createFileURI(classXMIPath)
        );

        // 将所有类添加到资源
        resource.getContents().addAll(classMap.values());

        Map<String, Object> options = new HashMap<>();
        options.put(Resource.OPTION_SAVE_ONLY_IF_CHANGED, Resource.OPTION_SAVE_ONLY_IF_CHANGED_MEMORY_BUFFER);

        resource.save(options);

        log.info("类模型已保存到: {}", classXMIPath);
        return classXMIPath;
    }

    /**
     * 递归查找所有Java文件
     */
    private List<Path> findJavaFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    /**
     * XMI生成结果
     */
    public static class XMIGenerationResult {
        private String packageXMIPath;
        private String classXMIPath;
        private int totalPackages;
        private int totalClasses;

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