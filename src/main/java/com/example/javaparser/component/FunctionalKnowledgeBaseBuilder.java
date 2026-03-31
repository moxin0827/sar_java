package com.example.javaparser.component;

import com.example.javaparser.llm.LLMService;
import com.example.javaparser.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 功能结构知识库构建服务
 * 利用LLM从代码中提取功能层级、术语表、功能约束等信息
 */
@Slf4j
@Service
public class FunctionalKnowledgeBaseBuilder {

    @Autowired
    private LLMService llmService;

    /**
     * 构建功能结构知识库
     *
     * @param knowledgeBase 基础知识库（包含代码实体和依赖关系）
     * @return 功能结构知识库
     */
    public FunctionalKnowledgeBase buildFunctionalKnowledgeBase(KnowledgeBase knowledgeBase) {
        log.info("开始构建功能结构知识库...");

        FunctionalKnowledgeBase functionalKB = new FunctionalKnowledgeBase();

        // 1. 构建功能层级结构
        log.info("=== 步骤1: 构建功能层级结构 ===");
        FunctionalKnowledgeBase.FunctionalHierarchy hierarchy = buildFunctionalHierarchy(knowledgeBase);
        functionalKB.setFunctionalHierarchy(hierarchy);
        log.info("功能层级构建完成，共 {} 个节点", hierarchy.getNodeMap().size());

        // 2. 构建术语表
        log.info("=== 步骤2: 构建术语表 ===");
        Map<String, FunctionalKnowledgeBase.TermDefinition> glossary = buildGlossary(knowledgeBase);
        functionalKB.setGlossary(glossary);
        log.info("术语表构建完成，共 {} 个术语", glossary.size());

        // 3. 识别功能块
        log.info("=== 步骤3: 识别功能块 ===");
        Map<String, FunctionalKnowledgeBase.FunctionalBlock> functionalBlocks =
            identifyFunctionalBlocks(knowledgeBase, hierarchy);
        functionalKB.setFunctionalBlocks(functionalBlocks);
        log.info("功能块识别完成，共 {} 个功能块", functionalBlocks.size());

        // 4. 提取功能约束
        log.info("=== 步骤4: 提取功能约束 ===");
        List<FunctionalKnowledgeBase.FunctionalConstraint> constraints =
            extractFunctionalConstraints(knowledgeBase, functionalBlocks);
        functionalKB.setConstraints(constraints);
        log.info("功能约束提取完成，共 {} 个约束", constraints.size());

        // 5. 识别业务规则
        log.info("=== 步骤5: 识别业务规则 ===");
        List<FunctionalKnowledgeBase.BusinessRule> businessRules =
            identifyBusinessRules(knowledgeBase);
        functionalKB.setBusinessRules(businessRules);
        log.info("业务规则识别完成，共 {} 个规则", businessRules.size());

        // 6. 构建功能依赖图
        log.info("=== 步骤6: 构建功能依赖图 ===");
        Map<String, Set<String>> functionalDeps = buildFunctionalDependencies(functionalBlocks, knowledgeBase);
        functionalKB.setFunctionalDependencies(functionalDeps);
        log.info("功能依赖图构建完成");

        log.info("功能结构知识库构建完成！");
        return functionalKB;
    }

    /**
     * 构建功能层级结构
     */
    private FunctionalKnowledgeBase.FunctionalHierarchy buildFunctionalHierarchy(KnowledgeBase knowledgeBase) {
        FunctionalKnowledgeBase.FunctionalHierarchy hierarchy = new FunctionalKnowledgeBase.FunctionalHierarchy();

        // 创建根节点（系统级）
        FunctionalKnowledgeBase.FunctionalNode root = new FunctionalKnowledgeBase.FunctionalNode();
        root.setId("root");
        root.setName(knowledgeBase.getProjectName());
        root.setLevel(FunctionalKnowledgeBase.FunctionalNode.FunctionalLevel.SYSTEM);

        // 使用LLM生成系统级描述
        String systemDescription = llmService.generateSystemDescription(
            knowledgeBase.getProjectName(),
            knowledgeBase.getEntities().values()
        );
        root.setDescription(systemDescription);

        hierarchy.setRoot(root);
        hierarchy.getNodeMap().put(root.getId(), root);

        // 按包结构识别子系统
        Map<String, List<CodeEntity>> packageGroups = knowledgeBase.getEntities().values().stream()
            .collect(Collectors.groupingBy(this::extractTopLevelPackage));

        for (Map.Entry<String, List<CodeEntity>> entry : packageGroups.entrySet()) {
            String topPackage = entry.getKey();
            List<CodeEntity> entities = entry.getValue();

            if (entities.size() < 3) {
                continue; // 跳过太小的包
            }

            // 创建子系统节点
            FunctionalKnowledgeBase.FunctionalNode subsystemNode = createSubsystemNode(
                topPackage, entities, knowledgeBase
            );
            hierarchy.addNode(root.getId(), subsystemNode);

            // 在子系统下创建模块节点
            Map<String, List<CodeEntity>> moduleGroups = entities.stream()
                .collect(Collectors.groupingBy(CodeEntity::getPackageName));

            for (Map.Entry<String, List<CodeEntity>> moduleEntry : moduleGroups.entrySet()) {
                String packageName = moduleEntry.getKey();
                List<CodeEntity> moduleEntities = moduleEntry.getValue();

                FunctionalKnowledgeBase.FunctionalNode moduleNode = createModuleNode(
                    packageName, moduleEntities, knowledgeBase
                );
                hierarchy.addNode(subsystemNode.getId(), moduleNode);
            }
        }

        return hierarchy;
    }

    /**
     * 创建子系统节点
     */
    private FunctionalKnowledgeBase.FunctionalNode createSubsystemNode(
            String topPackage, List<CodeEntity> entities, KnowledgeBase knowledgeBase) {

        FunctionalKnowledgeBase.FunctionalNode node = new FunctionalKnowledgeBase.FunctionalNode();
        node.setId("subsystem_" + topPackage);
        node.setLevel(FunctionalKnowledgeBase.FunctionalNode.FunctionalLevel.SUBSYSTEM);

        // 使用LLM生成子系统名称和描述
        String subsystemInfo = llmService.generateSubsystemInfo(topPackage, entities);
        Map<String, String> parsedInfo = parseSubsystemInfo(subsystemInfo);

        node.setName(parsedInfo.getOrDefault("name", capitalize(topPackage)));
        node.setDescription(parsedInfo.getOrDefault("description", "Subsystem for " + topPackage));

        // 关联实体
        entities.forEach(e -> node.getRelatedEntities().add(e.getQualifiedName()));

        return node;
    }

    /**
     * 创建模块节点
     */
    private FunctionalKnowledgeBase.FunctionalNode createModuleNode(
            String packageName, List<CodeEntity> entities, KnowledgeBase knowledgeBase) {

        FunctionalKnowledgeBase.FunctionalNode node = new FunctionalKnowledgeBase.FunctionalNode();
        node.setId("module_" + packageName);
        node.setLevel(FunctionalKnowledgeBase.FunctionalNode.FunctionalLevel.MODULE);

        // 使用LLM生成模块名称和描述
        String moduleInfo = llmService.generateModuleInfo(packageName, entities);
        Map<String, String> parsedInfo = parseModuleInfo(moduleInfo);

        node.setName(parsedInfo.getOrDefault("name", extractModuleName(packageName)));
        node.setDescription(parsedInfo.getOrDefault("description", "Module for " + packageName));

        // 关联实体
        entities.forEach(e -> node.getRelatedEntities().add(e.getQualifiedName()));

        // 提取属性
        node.getAttributes().put("packageName", packageName);
        node.getAttributes().put("entityCount", String.valueOf(entities.size()));

        return node;
    }

    /**
     * 构建术语表
     */
    private Map<String, FunctionalKnowledgeBase.TermDefinition> buildGlossary(KnowledgeBase knowledgeBase) {
        Map<String, FunctionalKnowledgeBase.TermDefinition> glossary = new HashMap<>();

        // 收集所有潜在的领域术语
        Set<String> potentialTerms = new HashSet<>();

        for (CodeEntity entity : knowledgeBase.getEntities().values()) {
            // 从类名提取术语
            potentialTerms.addAll(extractTermsFromName(entity.getName()));

            // 从注释提取术语
            if (entity.getComment() != null) {
                potentialTerms.addAll(extractTermsFromComment(entity.getComment()));
            }

            // 从语义信息提取术语
            if (entity.getSemanticInfo() != null) {
                potentialTerms.addAll(entity.getSemanticInfo().getKeyTerms());
            }
        }

        // 使用LLM批量定义术语
        log.info("使用LLM定义 {} 个术语...", potentialTerms.size());

        List<String> termList = new ArrayList<>(potentialTerms);
        int batchSize = 5;  // 减小批处理大小以避免响应被截断

        for (int i = 0; i < termList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, termList.size());
            List<String> batch = termList.subList(i, end);

            try {
                Map<String, FunctionalKnowledgeBase.TermDefinition> batchDefinitions =
                    llmService.defineTermsBatch(batch, knowledgeBase);
                glossary.putAll(batchDefinitions);

                // 避免API限流 - 增加延迟以应对 503 错误
                Thread.sleep(2000);  // 增加到 2 秒
            } catch (Exception e) {
                log.error("术语定义失败: {}", batch, e);
            }
        }

        return glossary;
    }

    /**
     * 识别功能块
     */
    private Map<String, FunctionalKnowledgeBase.FunctionalBlock> identifyFunctionalBlocks(
            KnowledgeBase knowledgeBase,
            FunctionalKnowledgeBase.FunctionalHierarchy hierarchy) {

        Map<String, FunctionalKnowledgeBase.FunctionalBlock> functionalBlocks = new HashMap<>();

        // 遍历模块级节点，为每个模块创建功能块
        for (FunctionalKnowledgeBase.FunctionalNode node : hierarchy.getNodeMap().values()) {
            if (node.getLevel() == FunctionalKnowledgeBase.FunctionalNode.FunctionalLevel.MODULE) {

                List<CodeEntity> moduleEntities = node.getRelatedEntities().stream()
                    .map(name -> knowledgeBase.getEntities().get(name))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

                if (moduleEntities.isEmpty()) {
                    continue;
                }

                // 使用LLM识别功能块
                FunctionalKnowledgeBase.FunctionalBlock block =
                    llmService.identifyFunctionalBlock(node.getName(), moduleEntities);

                if (block != null) {
                    block.setId("fb_" + node.getId());
                    block.setHierarchyNodeId(node.getId());
                    block.setEntities(node.getRelatedEntities());

                    functionalBlocks.put(block.getId(), block);
                }
            }
        }

        return functionalBlocks;
    }

    /**
     * 提取功能约束
     */
    private List<FunctionalKnowledgeBase.FunctionalConstraint> extractFunctionalConstraints(
            KnowledgeBase knowledgeBase,
            Map<String, FunctionalKnowledgeBase.FunctionalBlock> functionalBlocks) {

        List<FunctionalKnowledgeBase.FunctionalConstraint> constraints = new ArrayList<>();

        // 分析功能块之间的约束关系
        List<FunctionalKnowledgeBase.FunctionalBlock> blockList =
            new ArrayList<>(functionalBlocks.values());

        for (int i = 0; i < blockList.size(); i++) {
            for (int j = i + 1; j < blockList.size(); j++) {
                FunctionalKnowledgeBase.FunctionalBlock block1 = blockList.get(i);
                FunctionalKnowledgeBase.FunctionalBlock block2 = blockList.get(j);

                // 检查是否有依赖关系
                boolean hasDependency = hasEntityDependency(block1, block2, knowledgeBase);

                if (hasDependency) {
                    // 使用LLM分析约束类型
                    FunctionalKnowledgeBase.FunctionalConstraint constraint =
                        llmService.analyzeConstraint(block1, block2, knowledgeBase);

                    if (constraint != null) {
                        constraint.setId("constraint_" + UUID.randomUUID().toString());
                        constraint.setSource(block1.getId());
                        constraint.setTarget(block2.getId());
                        constraints.add(constraint);
                    }
                }
            }
        }

        return constraints;
    }

    /**
     * 识别业务规则
     */
    private List<FunctionalKnowledgeBase.BusinessRule> identifyBusinessRules(KnowledgeBase knowledgeBase) {
        List<FunctionalKnowledgeBase.BusinessRule> businessRules = new ArrayList<>();

        // 从代码注释和方法名中识别业务规则
        for (CodeEntity entity : knowledgeBase.getEntities().values()) {

            // 分析方法中的业务规则
            for (CodeEntity.MethodInfo method : entity.getMethods()) {
                if (method.getComment() != null && containsRuleKeywords(method.getComment())) {

                    // 使用LLM提取业务规则
                    FunctionalKnowledgeBase.BusinessRule rule =
                        llmService.extractBusinessRule(entity, method);

                    if (rule != null) {
                        rule.setId("rule_" + UUID.randomUUID().toString());
                        rule.getAffectedEntities().add(entity.getQualifiedName());
                        businessRules.add(rule);
                    }
                }
            }
        }

        return businessRules;
    }

    /**
     * 构建功能依赖图
     */
    private Map<String, Set<String>> buildFunctionalDependencies(
            Map<String, FunctionalKnowledgeBase.FunctionalBlock> functionalBlocks,
            KnowledgeBase knowledgeBase) {

        Map<String, Set<String>> dependencies = new HashMap<>();

        for (FunctionalKnowledgeBase.FunctionalBlock block : functionalBlocks.values()) {
            Set<String> blockDeps = new HashSet<>();

            // 分析实体依赖
            for (String entityName : block.getEntities()) {
                CodeEntity entity = knowledgeBase.getEntities().get(entityName);
                if (entity != null) {
                    for (String dep : entity.getDependencies()) {
                        // 找到依赖实体所属的功能块
                        String depBlockId = findBlockForEntity(dep, functionalBlocks);
                        if (depBlockId != null && !depBlockId.equals(block.getId())) {
                            blockDeps.add(depBlockId);
                        }
                    }
                }
            }

            dependencies.put(block.getId(), blockDeps);
        }

        return dependencies;
    }

    // ==================== 辅助方法 ====================

    /**
     * 提取顶层包名
     */
    private String extractTopLevelPackage(CodeEntity entity) {
        String packageName = entity.getPackageName();
        String[] parts = packageName.split("\\.");

        // 通常取第3或第4层作为顶层包
        // 例如: com.example.project.module -> project
        if (parts.length >= 4) {
            return parts[2];
        } else if (parts.length >= 3) {
            return parts[2];
        }
        return packageName;
    }

    /**
     * 提取模块名
     */
    private String extractModuleName(String packageName) {
        String[] parts = packageName.split("\\.");
        if (parts.length > 0) {
            return capitalize(parts[parts.length - 1]);
        }
        return packageName;
    }

    /**
     * 从名称中提取术语
     */
    private Set<String> extractTermsFromName(String name) {
        Set<String> terms = new HashSet<>();

        // 按驼峰命名拆分
        String[] words = name.split("(?=[A-Z])");
        for (String word : words) {
            if (word.length() > 3 && !isCommonWord(word)) {
                terms.add(word.toLowerCase());
            }
        }

        return terms;
    }

    /**
     * 从注释中提取术语
     */
    private Set<String> extractTermsFromComment(String comment) {
        Set<String> terms = new HashSet<>();

        // 简单的分词
        String[] words = comment.split("\\s+");
        for (String word : words) {
            word = word.replaceAll("[^a-zA-Z]", "");
            if (word.length() > 4 && !isCommonWord(word)) {
                terms.add(word.toLowerCase());
            }
        }

        return terms;
    }

    /**
     * 判断是否为常见词
     */
    private boolean isCommonWord(String word) {
        Set<String> commonWords = new HashSet<>(Arrays.asList(
            "the", "and", "for", "with", "this", "that", "from", "have",
            "class", "interface", "method", "field", "public", "private",
            "return", "void", "string", "integer", "boolean", "list", "map"
        ));
        return commonWords.contains(word.toLowerCase());
    }

    /**
     * 检查是否包含规则关键词
     */
    private boolean containsRuleKeywords(String text) {
        String lowerText = text.toLowerCase();
        return lowerText.contains("rule") ||
               lowerText.contains("validate") ||
               lowerText.contains("check") ||
               lowerText.contains("must") ||
               lowerText.contains("should") ||
               lowerText.contains("if") && lowerText.contains("then");
    }

    /**
     * 检查两个功能块之间是否有实体依赖
     */
    private boolean hasEntityDependency(
            FunctionalKnowledgeBase.FunctionalBlock block1,
            FunctionalKnowledgeBase.FunctionalBlock block2,
            KnowledgeBase knowledgeBase) {

        for (String entity1 : block1.getEntities()) {
            CodeEntity e1 = knowledgeBase.getEntities().get(entity1);
            if (e1 != null) {
                for (String dep : e1.getDependencies()) {
                    if (block2.getEntities().contains(dep)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 查找实体所属的功能块
     */
    private String findBlockForEntity(
            String entityName,
            Map<String, FunctionalKnowledgeBase.FunctionalBlock> functionalBlocks) {

        for (Map.Entry<String, FunctionalKnowledgeBase.FunctionalBlock> entry : functionalBlocks.entrySet()) {
            if (entry.getValue().getEntities().contains(entityName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 解析子系统信息
     */
    private Map<String, String> parseSubsystemInfo(String info) {
        Map<String, String> result = new HashMap<>();
        // 简单的解析逻辑，实际应该更复杂
        String[] lines = info.split("\n");
        for (String line : lines) {
            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    result.put(parts[0].trim().toLowerCase(), parts[1].trim());
                }
            }
        }
        return result;
    }

    /**
     * 解析模块信息
     */
    private Map<String, String> parseModuleInfo(String info) {
        return parseSubsystemInfo(info); // 使用相同的解析逻辑
    }

    /**
     * 首字母大写
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
