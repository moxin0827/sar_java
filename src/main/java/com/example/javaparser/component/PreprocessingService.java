package com.example.javaparser.component;

import com.example.javaparser.config.ComponentConfig;
import com.example.javaparser.llm.LLMService;
import com.example.javaparser.model.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;

import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 预处理服务 - 解析Java源码并提取语义信息
 */
@Slf4j
@Service
public class PreprocessingService {

    @Autowired
    private LLMService llmService;

    @Autowired
    private ComponentConfig componentConfig;

    private JavaParser javaParser;

    /**
     * 配置JavaParser，集成Symbol Solver实现精确类型解析
     */
    private void configureParser(String sourceDirectory) {
        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

        // 集成Symbol Solver实现精确类型解析
        try {
            CombinedTypeSolver combinedSolver = new CombinedTypeSolver();
            combinedSolver.add(new ReflectionTypeSolver());  // JDK类型
            combinedSolver.add(new JavaParserTypeSolver(new File(sourceDirectory)));  // 项目源码

            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedSolver);
            parserConfig.setSymbolResolver(symbolSolver);
            log.info("Symbol Solver已启用，源码目录: {}", sourceDirectory);
        } catch (Exception e) {
            log.warn("Symbol Solver初始化失败，将使用简单名称匹配: {}", e.getMessage());
        }

        this.javaParser = new JavaParser(parserConfig);
        StaticJavaParser.setConfiguration(parserConfig);
    }

    /**
     * 预处理Java项目
     */
    public KnowledgeBase preprocessProject(String sourceDirectory) throws IOException {
        log.info("开始预处理Java项目: {}", sourceDirectory);

        // 配置JavaParser，集成Symbol Solver
        configureParser(sourceDirectory);

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setProjectName(new File(sourceDirectory).getName());

        // 1. 查找所有Java文件
        List<Path> javaFiles = findJavaFiles(new File(sourceDirectory).toPath());
        log.info("找到 {} 个Java文件", javaFiles.size());

        // 2. 解析所有Java文件，提取代码实体
        Map<String, CodeEntity> entities = new HashMap<>();
        int successCount = 0;
        int failCount = 0;
        for (Path javaFile : javaFiles) {
            try {
                List<CodeEntity> fileEntities = parseJavaFile(javaFile);
                fileEntities.forEach(entity -> entities.put(entity.getQualifiedName(), entity));
                successCount++;
            } catch (Exception e) {
                failCount++;
                // 记录警告而非错误，因为部分文件解析失败不应阻止整体处理
                log.warn("跳过无法解析的文件 (可能使用了不支持的Java语法): {} - {}",
                        javaFile.getFileName(), e.getMessage());
                log.debug("解析失败详情: {}", javaFile, e);
            }
        }

        log.info("文件解析完成: 成功 {}/{} 个文件, 失败 {} 个, 提取 {} 个代码实体",
                successCount, javaFiles.size(), failCount, entities.size());

        if (entities.isEmpty()) {
            log.error("未能解析任何代码实体，请检查源代码是否为有效的Java项目");
            throw new RuntimeException("未能解析任何代码实体");
        }
        knowledgeBase.setEntities(entities);

        // 3. 构建依赖关系图
        buildDependencyGraph(entities, knowledgeBase.getDependencyGraph());

        // 4. 使用LLM提取语义信息（如果启用）
        if (componentConfig.getModuleDetection().getUseLlmSemantic()) {
            log.info("开始提取语义信息...");
            Map<String, SemanticInfo> semanticInfoMap = llmService.extractSemanticInfoBatch(
                    new ArrayList<>(entities.values())
            );

            // 将语义信息关联到实体
            semanticInfoMap.forEach((qualifiedName, semanticInfo) -> {
                CodeEntity entity = entities.get(qualifiedName);
                if (entity != null) {
                    entity.setSemanticInfo(semanticInfo);
                }
            });

            log.info("语义信息提取完成");
        }

        // 5. 构建术语表
        buildGlossary(entities, knowledgeBase);

        log.info("预处理完成");
        return knowledgeBase;
    }

    /**
     * 解析单个Java文件
     */
    private List<CodeEntity> parseJavaFile(Path javaFilePath) throws IOException {
        List<CodeEntity> entities = new ArrayList<>();


        CompilationUnit cu = StaticJavaParser.parse(javaFilePath);

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("default");

        // 处理所有类型声明
        cu.findAll(TypeDeclaration.class).forEach(typeDecl -> {
            try {
                CodeEntity entity = createCodeEntity(typeDecl, packageName);
                if (entity != null) {
                    entities.add(entity);
                }
            } catch (Exception e) {
                log.error("创建代码实体失败: {}", typeDecl.getNameAsString(), e);
            }
        });

        return entities;
    }

    /**
     * 创建代码实体
     */
    private CodeEntity createCodeEntity(TypeDeclaration<?> typeDecl, String packageName) {
        CodeEntity entity = new CodeEntity();

        String className = typeDecl.getNameAsString();
        entity.setName(className);
        entity.setQualifiedName(packageName + "." + className);
        entity.setPackageName(packageName);
        entity.setId(UUID.randomUUID().toString());

        // 提取注释
        Optional<Comment> comment = typeDecl.getComment();
        if (comment.isPresent()) {
            entity.setComment(cleanComment(comment.get().getContent()));
        }

        // 处理类或接口
        if (typeDecl.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();

            if (classDecl.isInterface()) {
                entity.setType(CodeEntity.EntityType.INTERFACE);
            } else if (classDecl.isAbstract()) {
                entity.setType(CodeEntity.EntityType.ABSTRACT_CLASS);
            } else {
                entity.setType(CodeEntity.EntityType.CLASS);
            }

            // 提取继承关系
            classDecl.getExtendedTypes().forEach(extendedType -> {
                entity.setSuperClass(extendedType.getNameAsString());
            });

            // 提取实现的接口
            classDecl.getImplementedTypes().forEach(implementedType -> {
                entity.getInterfaces().add(implementedType.getNameAsString());
            });

            // 提取注解
            classDecl.getAnnotations().forEach(annotation -> {
                entity.getAnnotations().add(annotation.getNameAsString());
            });

            // 提取字段
            classDecl.getFields().forEach(field -> {
                field.getVariables().forEach(var -> {
                    CodeEntity.FieldInfo fieldInfo = new CodeEntity.FieldInfo();
                    fieldInfo.setName(var.getNameAsString());
                    fieldInfo.setType(var.getTypeAsString());
                    fieldInfo.setPublic(field.isPublic());
                    fieldInfo.setStatic(field.isStatic());
                    fieldInfo.setFinal(field.isFinal());

                    field.getComment().ifPresent(c ->
                        fieldInfo.setComment(cleanComment(c.getContent()))
                    );

                    entity.getFields().add(fieldInfo);
                });
            });

            // 提取方法
            classDecl.getMethods().forEach(method -> {
                CodeEntity.MethodInfo methodInfo = new CodeEntity.MethodInfo();
                methodInfo.setName(method.getNameAsString());
                methodInfo.setReturnType(method.getTypeAsString());
                methodInfo.setPublic(method.isPublic());
                methodInfo.setStatic(method.isStatic());

                List<String> params = method.getParameters().stream()
                        .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                        .collect(Collectors.toList());
                methodInfo.setParameters(params);

                method.getComment().ifPresent(c ->
                    methodInfo.setComment(cleanComment(c.getContent()))
                );

                entity.getMethods().add(methodInfo);
            });

        } else if (typeDecl.isEnumDeclaration()) {
            entity.setType(CodeEntity.EntityType.ENUM);
        }

        return entity;
    }

    /**
     * 构建依赖关系图（改进版：包含泛型参数类型的依赖解析）
     */
    private void buildDependencyGraph(Map<String, CodeEntity> entities,
                                     KnowledgeBase.DependencyGraph graph) {
        log.info("构建依赖关系图...");

        for (CodeEntity entity : entities.values()) {
            Set<String> dependencies = new HashSet<>();

            // 继承依赖
            if (entity.getSuperClass() != null) {
                String superQualified = resolveQualifiedName(entity.getSuperClass(),
                        entity.getPackageName(), entities);
                if (superQualified != null) {
                    dependencies.add(superQualified);
                }
            }

            // 接口依赖
            for (String interfaceName : entity.getInterfaces()) {
                String interfaceQualified = resolveQualifiedName(interfaceName,
                        entity.getPackageName(), entities);
                if (interfaceQualified != null) {
                    dependencies.add(interfaceQualified);
                }
            }

            // 字段类型依赖（包含泛型参数）
            for (CodeEntity.FieldInfo field : entity.getFields()) {
                addTypeDependencies(field.getType(), entity.getPackageName(), entities, dependencies);
            }

            // 方法参数和返回类型依赖（包含泛型参数）
            for (CodeEntity.MethodInfo method : entity.getMethods()) {
                addTypeDependencies(method.getReturnType(), entity.getPackageName(), entities, dependencies);

                for (String param : method.getParameters()) {
                    String paramType = param.split(" ")[0];
                    addTypeDependencies(paramType, entity.getPackageName(), entities, dependencies);
                }
            }

            // 添加到依赖图
            entity.setDependencies(dependencies);
            for (String dep : dependencies) {
                graph.addDependency(entity.getQualifiedName(), dep);
            }
        }

        log.info("依赖关系图构建完成");
    }

    /**
     * 添加类型依赖（包含基本类型和泛型参数中的项目类型）
     */
    private void addTypeDependencies(String fullType, String currentPackage,
                                     Map<String, CodeEntity> entities, Set<String> dependencies) {
        if (fullType == null) {
            return;
        }

        // 基本类型依赖
        String baseType = extractBaseType(fullType);
        String baseQualified = resolveQualifiedName(baseType, currentPackage, entities);
        if (baseQualified != null) {
            dependencies.add(baseQualified);
        }

        // 泛型参数类型依赖
        List<String> genericArgs = extractGenericTypeArguments(fullType);
        for (String arg : genericArgs) {
            String argQualified = resolveQualifiedName(arg, currentPackage, entities);
            if (argQualified != null) {
                dependencies.add(argQualified);
            }
        }
    }

    /**
     * 提取泛型参数中的所有类型名
     * 例如: "Map<String, List<Foo>>" -> ["String", "List", "Foo"]
     */
    private List<String> extractGenericTypeArguments(String type) {
        List<String> args = new ArrayList<>();
        if (type == null) {
            return args;
        }

        int ltIdx = type.indexOf('<');
        int gtIdx = type.lastIndexOf('>');
        if (ltIdx < 0 || gtIdx <= ltIdx) {
            return args;
        }

        String inner = type.substring(ltIdx + 1, gtIdx);

        // 按逗号分割（注意嵌套泛型）
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                String segment = inner.substring(start, i).trim();
                addTypeFromSegment(segment, args);
                start = i + 1;
            }
        }
        // 最后一段
        String lastSegment = inner.substring(start).trim();
        addTypeFromSegment(lastSegment, args);

        return args;
    }

    private void addTypeFromSegment(String segment, List<String> args) {
        if (segment.isEmpty()) return;
        // 提取基本类型名（去除自身的泛型部分）
        String baseName = extractBaseType(segment);
        if (baseName != null && !baseName.isEmpty() && !isJavaBuiltInType(baseName)) {
            args.add(baseName);
        }
        // 递归提取嵌套泛型
        args.addAll(extractGenericTypeArguments(segment));
    }

    /**
     * 构建术语表
     */
    private void buildGlossary(Map<String, CodeEntity> entities, KnowledgeBase knowledgeBase) {
        log.info("构建术语表...");

        Map<String, KnowledgeBase.TermDefinition> glossary = new HashMap<>();

        for (CodeEntity entity : entities.values()) {
            if (entity.getSemanticInfo() != null) {
                for (String term : entity.getSemanticInfo().getKeyTerms()) {
                    if (!glossary.containsKey(term)) {
                        KnowledgeBase.TermDefinition termDef = new KnowledgeBase.TermDefinition();
                        termDef.setTerm(term);
                        termDef.setCategory(entity.getSemanticInfo().getBusinessDomain());
                        glossary.put(term, termDef);
                    }
                }
            }
        }

        knowledgeBase.setGlossary(glossary);
        log.info("术语表构建完成，共 {} 个术语", glossary.size());
    }

    /**
     * 解析完全限定名
     */
    private String resolveQualifiedName(String typeName, String currentPackage,
                                       Map<String, CodeEntity> entities) {
        if (typeName == null || typeName.isEmpty()) {
            return null;
        }

        // 过滤Java基本类型和常用类
        if (isJavaBuiltInType(typeName)) {
            return null;
        }

        // 已经是完全限定名
        if (entities.containsKey(typeName)) {
            return typeName;
        }

        // 尝试在当前包中查找
        String qualifiedName = currentPackage + "." + typeName;
        if (entities.containsKey(qualifiedName)) {
            return qualifiedName;
        }

        // 尝试在所有包中查找匹配的简单名称
        for (String key : entities.keySet()) {
            if (key.endsWith("." + typeName)) {
                return key;
            }
        }

        return null;
    }

    /**
     * 提取基本类型（去除泛型）
     */
    private String extractBaseType(String type) {
        if (type == null) {
            return null;
        }
        // 移除泛型
        int genericStart = type.indexOf('<');
        if (genericStart > 0) {
            type = type.substring(0, genericStart);
        }
        // 移除数组标记
        type = type.replace("[]", "");
        return type.trim();
    }

    /**
     * 判断是否为Java内置类型（扩展版：覆盖标准库常用类型）
     */
    private boolean isJavaBuiltInType(String type) {
        if (type == null || type.isEmpty()) {
            return true;
        }

        // 完全限定名以java./javax.开头的直接判定为内置类型
        if (type.startsWith("java.") || type.startsWith("javax.")) {
            return true;
        }

        Set<String> builtInTypes = new HashSet<>(Arrays.asList(
                // 基本类型
                "int", "long", "double", "float", "boolean", "char", "byte", "short",
                // 包装类型
                "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short",
                // 字符串和对象
                "String", "Object", "void", "Void",
                // 集合类型
                "List", "Set", "Map", "Collection", "ArrayList", "HashMap", "HashSet",
                "LinkedList", "TreeSet", "TreeMap", "LinkedHashMap", "LinkedHashSet",
                "Queue", "Deque", "Stack", "Vector", "ArrayDeque", "PriorityQueue",
                "ConcurrentHashMap", "CopyOnWriteArrayList", "ConcurrentLinkedQueue",
                // 函数式接口
                "Optional", "Stream", "CompletableFuture", "Future",
                "Supplier", "Consumer", "Function", "Predicate", "BiFunction",
                "BiConsumer", "BiPredicate", "UnaryOperator", "BinaryOperator",
                // 迭代器和比较器
                "Iterator", "Iterable", "Comparable", "Comparator", "Serializable", "Cloneable",
                // 数值类型
                "BigDecimal", "BigInteger", "Number", "AtomicInteger", "AtomicLong",
                // 日期时间
                "Date", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
                "ZonedDateTime", "OffsetDateTime", "Duration", "Period",
                "Calendar", "Timestamp",
                // 其他常用类型
                "UUID", "Pattern", "Matcher", "URI", "URL",
                "File", "Path", "InputStream", "OutputStream", "Reader", "Writer",
                "StringBuilder", "StringBuffer",
                "Class", "Enum", "Annotation", "Throwable", "Exception", "RuntimeException",
                "IOException", "Error"
        ));
        return builtInTypes.contains(type);
    }

    /**
     * 清理注释内容（改进版：保留Javadoc结构化信息）
     */
    private String cleanComment(String comment) {
        if (comment == null) {
            return null;
        }

        // 尝试解析为Javadoc以保留结构化信息
        try {
            Javadoc javadoc = StaticJavaParser.parseJavadoc(comment, false);

            StringBuilder result = new StringBuilder();

            // 主描述
            String description = javadoc.getDescription().toText().trim();
            if (!description.isEmpty()) {
                result.append(description);
            }

            // 保留@param信息
            for (JavadocBlockTag tag : javadoc.getBlockTags()) {
                if (tag.getType() == JavadocBlockTag.Type.PARAM) {
                    tag.getName().ifPresent(name ->
                        result.append(" @param ").append(name).append(": ")
                              .append(tag.getContent().toText().trim())
                    );
                } else if (tag.getType() == JavadocBlockTag.Type.RETURN) {
                    result.append(" @return: ").append(tag.getContent().toText().trim());
                } else if (tag.getType() == JavadocBlockTag.Type.THROWS) {
                    tag.getName().ifPresent(name ->
                        result.append(" @throws ").append(name).append(": ")
                              .append(tag.getContent().toText().trim())
                    );
                } else if (tag.getType() == JavadocBlockTag.Type.SEE) {
                    result.append(" @see ").append(tag.getContent().toText().trim());
                }
            }

            String parsed = result.toString().trim();
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (Exception e) {
            // Javadoc解析失败，回退到简单清理
            log.debug("Javadoc解析失败，使用简单清理: {}", e.getMessage());
        }

        // 回退：简单清理
        return comment.replaceAll("^[/*\\s]+", "")
                .replaceAll("[*/\\s]+$", "")
                .replaceAll("\\s*\\*\\s*", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
}
