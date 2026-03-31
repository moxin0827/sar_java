package com.example.javaparser.component;

import com.example.javaparser.config.ComponentConfig;
import com.example.javaparser.llm.LLMService;
import com.example.javaparser.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 架构恢复服务 - 识别功能模块和依赖关系
 */
@Slf4j
@Service
public class ArchitectureRecoveryService {

    @Autowired
    private LLMService llmService;

    @Autowired
    private ComponentConfig componentConfig;

    /**
     * 执行架构恢复
     */
    public void recoverArchitecture(KnowledgeBase knowledgeBase) {
        log.info("开始架构恢复...");

        // 1. 基于包结构的初步模块划分
        Map<String, FunctionalModule> packageBasedModules = new HashMap<>();
        if (componentConfig.getModuleDetection().getUsePackageStructure()) {
            packageBasedModules = identifyModulesByPackage(knowledgeBase);
            log.info("基于包结构识别出 {} 个模块", packageBasedModules.size());
        }

        // 2. 收集已分配的实体，命名约定仅处理未分配的实体（避免级联合并导致上帝组件）
        if (componentConfig.getModuleDetection().getUseNamingConvention()) {
            Set<String> assignedEntities = packageBasedModules.values().stream()
                    .flatMap(m -> m.getEntities().stream())
                    .collect(Collectors.toSet());
            Map<String, FunctionalModule> namingBasedModules = identifyModulesByNamingForUnassigned(
                    knowledgeBase, assignedEntities);
            packageBasedModules.putAll(namingBasedModules);
            log.info("结合命名约定后共 {} 个模块", packageBasedModules.size());
        }

        // 3. 基于依赖分析的模块优化
        if (componentConfig.getModuleDetection().getUseDependencyAnalysis()) {
            optimizeModulesByDependency(packageBasedModules, knowledgeBase);
            log.info("依赖分析优化完成");
        }

        // 4. 基于语义相似度的聚类
        if (componentConfig.getModuleDetection().getUseLlmSemantic()) {
            clusterBySemanticSimilarity(packageBasedModules, knowledgeBase);
            log.info("语义聚类完成");
        }

        // 5. 计算模块的内聚度和耦合度
        calculateModuleMetrics(packageBasedModules, knowledgeBase);

        // 6. 将模块添加到知识库
        knowledgeBase.setModules(packageBasedModules);

        log.info("架构恢复完成，共识别出 {} 个功能模块", packageBasedModules.size());
    }

    /**
     * 基于包结构识别模块
     */
    private Map<String, FunctionalModule> identifyModulesByPackage(KnowledgeBase knowledgeBase) {
        Map<String, FunctionalModule> modules = new HashMap<>();

        // 最大组件大小限制
        final int MAX_COMPONENT_SIZE = 30;

        // 按包名分组
        Map<String, List<CodeEntity>> packageGroups = knowledgeBase.getEntities().values().stream()
                .collect(Collectors.groupingBy(CodeEntity::getPackageName));

        log.info("=== 包结构分析 ===");
        log.info("总包数: {}", packageGroups.size());
        log.info("最小聚类大小: {}", componentConfig.getClustering().getMinClusterSize());
        log.info("最大组件大小: {}", MAX_COMPONENT_SIZE);

        // 统计包大小分布
        Map<String, Integer> packageSizes = new HashMap<>();
        for (Map.Entry<String, List<CodeEntity>> entry : packageGroups.entrySet()) {
            packageSizes.put(entry.getKey(), entry.getValue().size());
        }

        // 按大小排序显示
        packageSizes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> log.info("  包 {} : {} 个类", e.getKey(), e.getValue()));

        int skippedPackages = 0;
        int createdModules = 0;

        for (Map.Entry<String, List<CodeEntity>> entry : packageGroups.entrySet()) {
            String packageName = entry.getKey();
            List<CodeEntity> entities = entry.getValue();

            // 跳过太小的包
            if (entities.size() < componentConfig.getClustering().getMinClusterSize()) {
                log.debug("跳过小包: {} (只有 {} 个类，小于最小值 {})",
                        packageName, entities.size(), componentConfig.getClustering().getMinClusterSize());
                skippedPackages++;
                continue;
            }

            // 如果包太大，尝试按子包拆分
            if (entities.size() > MAX_COMPONENT_SIZE) {
                log.info("包 {} 包含 {} 个类，超过最大限制 {}，尝试拆分",
                        packageName, entities.size(), MAX_COMPONENT_SIZE);
                Map<String, List<CodeEntity>> subPackages = splitBySubPackage(entities, packageName);

                log.info("  拆分为 {} 个子包", subPackages.size());
                for (Map.Entry<String, List<CodeEntity>> subEntry : subPackages.entrySet()) {
                    log.info("    子包 {} : {} 个类", subEntry.getKey(), subEntry.getValue().size());
                    if (subEntry.getValue().size() >= componentConfig.getClustering().getMinClusterSize()) {
                        createModuleFromPackage(modules, subEntry.getKey(), subEntry.getValue());
                        createdModules++;
                    } else {
                        log.warn("    子包 {} 太小 ({} 个类)，跳过",
                                subEntry.getKey(), subEntry.getValue().size());
                    }
                }
            } else {
                createModuleFromPackage(modules, packageName, entities);
                createdModules++;
            }
        }

        log.info("=== 包结构识别结果 ===");
        log.info("跳过的小包: {} 个", skippedPackages);
        log.info("创建的模块: {} 个", createdModules);
        log.info("==================");

        return modules;
    }

    /**
     * 按子包拆分实体
     */
    private Map<String, List<CodeEntity>> splitBySubPackage(List<CodeEntity> entities, String basePackage) {
        Map<String, List<CodeEntity>> subPackages = new HashMap<>();

        for (CodeEntity entity : entities) {
            String packageName = entity.getPackageName();

            // 如果包名比基础包名长，说明是子包
            if (packageName.length() > basePackage.length() && packageName.startsWith(basePackage)) {
                String subPackagePart = packageName.substring(basePackage.length() + 1);
                String[] parts = subPackagePart.split("\\.");

                // 使用第一级子包作为分组依据
                String subPackageKey = basePackage + "." + parts[0];
                subPackages.computeIfAbsent(subPackageKey, k -> new ArrayList<>()).add(entity);
            } else {
                // 没有子包，放入基础包
                subPackages.computeIfAbsent(basePackage, k -> new ArrayList<>()).add(entity);
            }
        }

        return subPackages;
    }

    /**
     * 从包创建模块
     */
    private void createModuleFromPackage(Map<String, FunctionalModule> modules,
                                        String packageName,
                                        List<CodeEntity> entities) {
        FunctionalModule module = new FunctionalModule();
        module.setId(UUID.randomUUID().toString());
        module.setName(extractModuleNameFromPackage(packageName));
        module.setDescription("Module based on package: " + packageName);

        Set<String> entityNames = entities.stream()
                .map(CodeEntity::getQualifiedName)
                .collect(Collectors.toSet());
        module.setEntities(entityNames);

        // 推断架构层次
        module.setLayer(inferLayerFromEntities(entities));

        modules.put(module.getId(), module);

        log.debug("创建模块: {} (包含 {} 个类)", module.getName(), entities.size());
    }

    /**
     * 基于命名约定识别模块
     */
    private Map<String, FunctionalModule> identifyModulesByNaming(KnowledgeBase knowledgeBase) {
        Map<String, FunctionalModule> modules = new HashMap<>();

        // 按常见的命名模式分组
        Map<String, List<CodeEntity>> namingGroups = new HashMap<>();

        for (CodeEntity entity : knowledgeBase.getEntities().values()) {
            String pattern = extractNamingPattern(entity.getName());
            namingGroups.computeIfAbsent(pattern, k -> new ArrayList<>()).add(entity);
        }

        for (Map.Entry<String, List<CodeEntity>> entry : namingGroups.entrySet()) {
            String pattern = entry.getKey();
            List<CodeEntity> entities = entry.getValue();

            if (entities.size() >= componentConfig.getClustering().getMinClusterSize()) {
                FunctionalModule module = new FunctionalModule();
                module.setId(UUID.randomUUID().toString());
                module.setName(pattern + "Module");

                Set<String> entityNames = entities.stream()
                        .map(CodeEntity::getQualifiedName)
                        .collect(Collectors.toSet());
                module.setEntities(entityNames);

                modules.put(module.getId(), module);
            }
        }

        return modules;
    }

    /**
     * 基于命名约定识别模块（仅处理未被包结构分配的实体）
     * 避免与包结构模块合并导致级联合并产生上帝组件
     */
    private Map<String, FunctionalModule> identifyModulesByNamingForUnassigned(
            KnowledgeBase knowledgeBase, Set<String> assignedEntities) {
        Map<String, FunctionalModule> modules = new HashMap<>();
        Map<String, List<CodeEntity>> namingGroups = new HashMap<>();

        for (CodeEntity entity : knowledgeBase.getEntities().values()) {
            if (!assignedEntities.contains(entity.getQualifiedName())) {
                String pattern = extractNamingPattern(entity.getName());
                namingGroups.computeIfAbsent(pattern, k -> new ArrayList<>()).add(entity);
            }
        }

        for (Map.Entry<String, List<CodeEntity>> entry : namingGroups.entrySet()) {
            if (entry.getValue().size() >= componentConfig.getClustering().getMinClusterSize()) {
                FunctionalModule module = new FunctionalModule();
                module.setId(UUID.randomUUID().toString());
                module.setName(entry.getKey() + "Module");

                Set<String> entityNames = entry.getValue().stream()
                        .map(CodeEntity::getQualifiedName)
                        .collect(Collectors.toSet());
                module.setEntities(entityNames);

                modules.put(module.getId(), module);
            }
        }

        log.info("基于命名约定为未分配实体识别出 {} 个模块", modules.size());
        return modules;
    }

    /**
     * 基于依赖分析优化模块
     */
    private void optimizeModulesByDependency(Map<String, FunctionalModule> modules,
                                            KnowledgeBase knowledgeBase) {
        // 计算模块间的依赖关系
        for (FunctionalModule module : modules.values()) {
            Set<String> moduleDeps = new HashSet<>();

            for (String entityName : module.getEntities()) {
                CodeEntity entity = knowledgeBase.getEntities().get(entityName);
                if (entity != null) {
                    for (String dep : entity.getDependencies()) {
                        // 找到依赖实体所属的模块
                        String depModuleId = findModuleForEntity(dep, modules);
                        if (depModuleId != null && !depModuleId.equals(module.getId())) {
                            moduleDeps.add(depModuleId);
                        }
                    }
                }
            }

            module.setDependencies(moduleDeps);
            log.debug("模块 {} 依赖 {} 个其他模块", module.getName(), moduleDeps.size());
        }

        // 更新被依赖关系
        for (FunctionalModule module : modules.values()) {
            for (String depModuleId : module.getDependencies()) {
                FunctionalModule depModule = modules.get(depModuleId);
                if (depModule != null) {
                    depModule.getDependents().add(module.getId());
                }
            }
        }

        // 检测并警告星型拓扑
        detectStarTopology(modules);
    }

    /**
     * 检测星型拓扑（所有组件都依赖一个中心组件）
     */
    private void detectStarTopology(Map<String, FunctionalModule> modules) {
        if (modules.size() < 3) {
            return; // 组件太少，不需要检测
        }

        // 统计每个模块被依赖的次数
        Map<String, Integer> dependencyCount = new HashMap<>();
        for (FunctionalModule module : modules.values()) {
            for (String depId : module.getDependencies()) {
                dependencyCount.put(depId, dependencyCount.getOrDefault(depId, 0) + 1);
            }
        }

        // 检查是否有模块被过多依赖（超过70%的其他模块）
        int threshold = (int) (modules.size() * 0.7);
        for (Map.Entry<String, Integer> entry : dependencyCount.entrySet()) {
            if (entry.getValue() >= threshold) {
                FunctionalModule centralModule = modules.get(entry.getKey());
                log.warn("⚠️ 检测到中心组件: {} 被 {} 个组件依赖（可能是上帝组件，建议拆分）",
                         centralModule.getName(), entry.getValue());
            }
        }
    }

    /**
     * 基于语义相似度聚类
     */
    private void clusterBySemanticSimilarity(Map<String, FunctionalModule> modules,
                                            KnowledgeBase knowledgeBase) {
        // 收集所有未分配到模块的实体
        Set<String> assignedEntities = modules.values().stream()
                .flatMap(m -> m.getEntities().stream())
                .collect(Collectors.toSet());

        List<CodeEntity> unassignedEntities = knowledgeBase.getEntities().values().stream()
                .filter(e -> !assignedEntities.contains(e.getQualifiedName()))
                .collect(Collectors.toList());

        if (unassignedEntities.isEmpty()) {
            return;
        }

        log.info("对 {} 个未分配实体进行语义聚类", unassignedEntities.size());

        // 使用简单的层次聚类
        List<Set<CodeEntity>> clusters = performHierarchicalClustering(unassignedEntities);

        // 为每个聚类创建模块
        for (Set<CodeEntity> cluster : clusters) {
            if (cluster.size() >= componentConfig.getClustering().getMinClusterSize()) {
                FunctionalModule module = new FunctionalModule();
                module.setId(UUID.randomUUID().toString());

                Set<String> entityNames = cluster.stream()
                        .map(CodeEntity::getQualifiedName)
                        .collect(Collectors.toSet());
                module.setEntities(entityNames);

                // 使用LLM生成模块名称
                String moduleName = generateModuleName(cluster);
                module.setName(moduleName);

                modules.put(module.getId(), module);
            }
        }
    }

    /**
     * 执行层次聚类
     */
    private List<Set<CodeEntity>> performHierarchicalClustering(List<CodeEntity> entities) {
        List<Set<CodeEntity>> clusters = new ArrayList<>();

        // 初始化：每个实体为一个聚类
        List<Set<CodeEntity>> currentClusters = entities.stream()
                .map(e -> {
                    Set<CodeEntity> cluster = new HashSet<>();
                    cluster.add(e);
                    return cluster;
                })
                .collect(Collectors.toList());

        // 迭代合并相似的聚类
        while (currentClusters.size() > 1) {
            double maxSimilarity = -1;
            int mergeIdx1 = -1;
            int mergeIdx2 = -1;

            // 找到最相似的两个聚类
            for (int i = 0; i < currentClusters.size(); i++) {
                for (int j = i + 1; j < currentClusters.size(); j++) {
                    double similarity = calculateClusterSimilarity(
                            currentClusters.get(i),
                            currentClusters.get(j)
                    );

                    if (similarity > maxSimilarity) {
                        maxSimilarity = similarity;
                        mergeIdx1 = i;
                        mergeIdx2 = j;
                    }
                }
            }

            // 如果相似度低于阈值，停止合并
            if (maxSimilarity < componentConfig.getClustering().getSimilarityThreshold()) {
                break;
            }

            // 合并两个聚类
            Set<CodeEntity> merged = new HashSet<>();
            merged.addAll(currentClusters.get(mergeIdx1));
            merged.addAll(currentClusters.get(mergeIdx2));

            // 检查聚类大小限制
            if (merged.size() > componentConfig.getClustering().getMaxClusterSize()) {
                break;
            }

            currentClusters.remove(mergeIdx2);
            currentClusters.remove(mergeIdx1);
            currentClusters.add(merged);
        }

        return currentClusters;
    }

    /**
     * 计算聚类间的相似度
     */
    private double calculateClusterSimilarity(Set<CodeEntity> cluster1, Set<CodeEntity> cluster2) {
        if (cluster1.isEmpty() || cluster2.isEmpty()) {
            return 0.0;
        }

        double totalSimilarity = 0.0;
        int count = 0;

        for (CodeEntity e1 : cluster1) {
            for (CodeEntity e2 : cluster2) {
                totalSimilarity += calculateEntitySimilarity(e1, e2);
                count++;
            }
        }

        return count > 0 ? totalSimilarity / count : 0.0;
    }

    /**
     * 计算实体间的相似度
     */
    private double calculateEntitySimilarity(CodeEntity e1, CodeEntity e2) {
        double similarity = 0.0;

        // 1. 包名相似度 (权重: 0.3)
        if (e1.getPackageName().equals(e2.getPackageName())) {
            similarity += 0.3;
        } else if (isSimilarPackage(e1.getPackageName(), e2.getPackageName())) {
            similarity += 0.15;
        }

        // 2. 依赖关系相似度 (权重: 0.3)
        Set<String> commonDeps = new HashSet<>(e1.getDependencies());
        commonDeps.retainAll(e2.getDependencies());
        if (!e1.getDependencies().isEmpty() && !e2.getDependencies().isEmpty()) {
            double depSimilarity = (double) commonDeps.size() /
                    Math.max(e1.getDependencies().size(), e2.getDependencies().size());
            similarity += 0.3 * depSimilarity;
        }

        // 3. 语义相似度 (权重: 0.4)
        if (e1.getSemanticInfo() != null && e2.getSemanticInfo() != null) {
            double semanticSim = calculateSemanticSimilarity(
                    e1.getSemanticInfo(),
                    e2.getSemanticInfo()
            );
            similarity += 0.4 * semanticSim;
        }

        return similarity;
    }

    /**
     * 计算语义信息相似度
     */
    private double calculateSemanticSimilarity(SemanticInfo info1, SemanticInfo info2) {
        double similarity = 0.0;

        // 架构层次相同
        if (info1.getArchitectureLayer() == info2.getArchitectureLayer()) {
            similarity += 0.4;
        }

        // 业务领域相同
        if (info1.getBusinessDomain() != null &&
                info1.getBusinessDomain().equals(info2.getBusinessDomain())) {
            similarity += 0.3;
        }

        // 关键术语重叠
        Set<String> commonTerms = new HashSet<>(info1.getKeyTerms());
        commonTerms.retainAll(info2.getKeyTerms());
        if (!info1.getKeyTerms().isEmpty() && !info2.getKeyTerms().isEmpty()) {
            double termSimilarity = (double) commonTerms.size() /
                    Math.max(info1.getKeyTerms().size(), info2.getKeyTerms().size());
            similarity += 0.3 * termSimilarity;
        }

        return similarity;
    }

    /**
     * 计算模块指标（改进版：引入MQ指标驱动聚类质量评估）
     */
    private void calculateModuleMetrics(Map<String, FunctionalModule> modules,
                                       KnowledgeBase knowledgeBase) {
        for (FunctionalModule module : modules.values()) {
            // 计算内聚度
            double cohesion = calculateCohesion(module, knowledgeBase);
            module.setCohesion(cohesion);

            // 计算耦合度
            double coupling = calculateCoupling(module, modules);
            module.setCoupling(coupling);

            log.debug("模块 {} - 内聚度: {}, 耦合度: {}", module.getName(), cohesion, coupling);
        }

        // 计算整体MQ（Modularization Quality）指标
        double mq = calculateMQ(modules, knowledgeBase);
        log.info("=== 模块化质量评估 ===");
        log.info("MQ (Modularization Quality) = {}", String.format("%.4f", mq));
        if (mq < 0.1) {
            log.warn("MQ值较低(<0.1)，模块划分质量不佳，建议调整聚类参数");
        } else if (mq < 0.3) {
            log.info("MQ值中等(0.1-0.3)，模块划分质量一般");
        } else {
            log.info("MQ值良好(>=0.3)，模块划分质量较好");
        }
        log.info("======================");
    }

    /**
     * 计算Modularization Quality (MQ) 指标
     * MQ = (1/k) × Σ cohesion_i - (2/(k×(k-1))) × Σ coupling_ij
     *
     * 其中：
     *   cohesion_i = 模块内部边数 / 模块内最大可能边数
     *   coupling_ij = 模块i和j之间的边数 / 两模块间最大可能边数
     *   k = 模块数量
     */
    private double calculateMQ(Map<String, FunctionalModule> modules, KnowledgeBase knowledgeBase) {
        int k = modules.size();
        if (k == 0) return 0.0;

        List<FunctionalModule> moduleList = new ArrayList<>(modules.values());

        // 计算内聚度总和
        double cohesionSum = 0.0;
        for (FunctionalModule module : moduleList) {
            cohesionSum += calculateMQCohesion(module, knowledgeBase);
        }

        // 计算耦合度总和
        double couplingSum = 0.0;
        if (k > 1) {
            for (int i = 0; i < moduleList.size(); i++) {
                for (int j = i + 1; j < moduleList.size(); j++) {
                    couplingSum += calculateMQCoupling(moduleList.get(i), moduleList.get(j), knowledgeBase);
                }
            }
        }

        // MQ公式
        double mqCohesion = cohesionSum / k;
        double mqCoupling = k > 1 ? (2.0 / (k * (k - 1))) * couplingSum : 0.0;

        return mqCohesion - mqCoupling;
    }

    /**
     * 计算MQ中的模块内聚度：模块内部边数 / 模块内最大可能边数
     */
    private double calculateMQCohesion(FunctionalModule module, KnowledgeBase knowledgeBase) {
        List<String> entityList = new ArrayList<>(module.getEntities());
        int n = entityList.size();
        if (n < 2) return n == 1 ? 1.0 : 0.0;

        int maxEdges = n * (n - 1); // 有向图最大边数
        int actualEdges = 0;

        Set<String> entitySet = module.getEntities();
        for (String entityName : entityList) {
            CodeEntity entity = knowledgeBase.getEntities().get(entityName);
            if (entity != null) {
                for (String dep : entity.getDependencies()) {
                    if (entitySet.contains(dep)) {
                        actualEdges++;
                    }
                }
            }
        }

        return maxEdges > 0 ? (double) actualEdges / maxEdges : 0.0;
    }

    /**
     * 计算MQ中的模块间耦合度：两模块间的边数 / 两模块间最大可能边数
     */
    private double calculateMQCoupling(FunctionalModule module1, FunctionalModule module2,
                                       KnowledgeBase knowledgeBase) {
        int n1 = module1.getEntities().size();
        int n2 = module2.getEntities().size();
        if (n1 == 0 || n2 == 0) return 0.0;

        int maxEdges = 2 * n1 * n2; // 双向最大边数
        int actualEdges = 0;

        // module1 -> module2
        for (String entityName : module1.getEntities()) {
            CodeEntity entity = knowledgeBase.getEntities().get(entityName);
            if (entity != null) {
                for (String dep : entity.getDependencies()) {
                    if (module2.getEntities().contains(dep)) {
                        actualEdges++;
                    }
                }
            }
        }

        // module2 -> module1
        for (String entityName : module2.getEntities()) {
            CodeEntity entity = knowledgeBase.getEntities().get(entityName);
            if (entity != null) {
                for (String dep : entity.getDependencies()) {
                    if (module1.getEntities().contains(dep)) {
                        actualEdges++;
                    }
                }
            }
        }

        return maxEdges > 0 ? (double) actualEdges / maxEdges : 0.0;
    }

    /**
     * 计算内聚度
     */
    private double calculateCohesion(FunctionalModule module, KnowledgeBase knowledgeBase) {
        if (module.getEntities().size() < 2) {
            return 1.0;
        }

        int totalPairs = 0;
        int connectedPairs = 0;

        List<String> entityList = new ArrayList<>(module.getEntities());
        for (int i = 0; i < entityList.size(); i++) {
            for (int j = i + 1; j < entityList.size(); j++) {
                totalPairs++;

                String entity1 = entityList.get(i);
                String entity2 = entityList.get(j);

                CodeEntity e1 = knowledgeBase.getEntities().get(entity1);
                CodeEntity e2 = knowledgeBase.getEntities().get(entity2);

                if (e1 != null && e2 != null) {
                    // 检查是否有依赖关系
                    if (e1.getDependencies().contains(entity2) ||
                            e2.getDependencies().contains(entity1)) {
                        connectedPairs++;
                    }
                }
            }
        }

        return totalPairs > 0 ? (double) connectedPairs / totalPairs : 0.0;
    }

    /**
     * 计算耦合度
     */
    private double calculateCoupling(FunctionalModule module, Map<String, FunctionalModule> allModules) {
        if (allModules.size() < 2) {
            return 0.0;
        }

        int externalDependencies = module.getDependencies().size();
        int maxPossibleDeps = allModules.size() - 1;

        return maxPossibleDeps > 0 ? (double) externalDependencies / maxPossibleDeps : 0.0;
    }

    /**
     * 生成模块名称
     */
    private String generateModuleName(Set<CodeEntity> entities) {
        // 收集所有实体名称
        List<String> names = entities.stream()
                .map(CodeEntity::getName)
                .collect(Collectors.toList());

        // 提取公共前缀或后缀
        String commonPattern = findCommonPattern(names);
        if (commonPattern != null && !commonPattern.isEmpty()) {
            return commonPattern + "Module";
        }

        // 使用第一个实体的包名
        CodeEntity first = entities.iterator().next();
        return extractModuleNameFromPackage(first.getPackageName());
    }

    /**
     * 查找公共模式
     */
    private String findCommonPattern(List<String> names) {
        if (names.isEmpty()) {
            return "Unknown";
        }

        // 查找公共后缀
        Map<String, Integer> suffixCount = new HashMap<>();
        for (String name : names) {
            String suffix = extractSuffix(name);
            suffixCount.put(suffix, suffixCount.getOrDefault(suffix, 0) + 1);
        }

        // 返回最常见的后缀
        return suffixCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");
    }

    /**
     * 提取后缀
     */
    private String extractSuffix(String name) {
        String[] commonSuffixes = {"Service", "Controller", "Repository", "Dao", "Manager",
                "Handler", "Processor", "Util", "Helper", "Factory", "Builder"};

        for (String suffix : commonSuffixes) {
            if (name.endsWith(suffix)) {
                return suffix;
            }
        }

        return "Component";
    }

    /**
     * 从包名提取模块名
     */
    private String extractModuleNameFromPackage(String packageName) {
        String[] parts = packageName.split("\\.");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            return capitalize(lastPart);
        }
        return "Unknown";
    }

    /**
     * 提取命名模式
     */
    private String extractNamingPattern(String name) {
        return extractSuffix(name);
    }

    /**
     * 推断架构层次
     */
    private SemanticInfo.ArchitectureLayer inferLayerFromEntities(List<CodeEntity> entities) {
        Map<SemanticInfo.ArchitectureLayer, Integer> layerCount = new HashMap<>();

        for (CodeEntity entity : entities) {
            if (entity.getSemanticInfo() != null) {
                SemanticInfo.ArchitectureLayer layer = entity.getSemanticInfo().getArchitectureLayer();
                layerCount.put(layer, layerCount.getOrDefault(layer, 0) + 1);
            }
        }

        return layerCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(SemanticInfo.ArchitectureLayer.UNKNOWN);
    }

    /**
     * 查找实体所属模块
     */
    private String findModuleForEntity(String entityName, Map<String, FunctionalModule> modules) {
        for (Map.Entry<String, FunctionalModule> entry : modules.entrySet()) {
            if (entry.getValue().getEntities().contains(entityName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 判断包名是否相似
     */
    private boolean isSimilarPackage(String pkg1, String pkg2) {
        String[] parts1 = pkg1.split("\\.");
        String[] parts2 = pkg2.split("\\.");

        int minLen = Math.min(parts1.length, parts2.length);
        int commonParts = 0;

        for (int i = 0; i < minLen; i++) {
            if (parts1[i].equals(parts2[i])) {
                commonParts++;
            } else {
                break;
            }
        }

        return commonParts >= minLen - 1;
    }

    /**
     * 合并模块
     */
    private Map<String, FunctionalModule> mergeModules(Map<String, FunctionalModule> modules1,
                                                       Map<String, FunctionalModule> modules2) {
        Map<String, FunctionalModule> merged = new HashMap<>(modules1);

        for (FunctionalModule module2 : modules2.values()) {
            boolean merged_flag = false;

            for (FunctionalModule module1 : merged.values()) {
                // 检查是否有重叠的实体
                Set<String> intersection = new HashSet<>(module1.getEntities());
                intersection.retainAll(module2.getEntities());

                if (!intersection.isEmpty()) {
                    // 合并到现有模块
                    module1.getEntities().addAll(module2.getEntities());
                    merged_flag = true;
                    break;
                }
            }

            if (!merged_flag) {
                merged.put(module2.getId(), module2);
            }
        }

        return merged;
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
