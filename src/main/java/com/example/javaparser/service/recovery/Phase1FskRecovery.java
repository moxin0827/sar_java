package com.example.javaparser.service.recovery;

import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.FunctionKnowledge;
import com.example.javaparser.entity.RecoveredComponent;
import com.example.javaparser.repository.ClassInfoRepository;
import com.example.javaparser.repository.FunctionKnowledgeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class Phase1FskRecovery {

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private FunctionKnowledgeRepository functionKnowledgeRepository;

    /**
     * 基于FSK知识将类映射到组件
     * 优化：支持预加载数据、模糊类名匹配、叶子节点优先
     *
     * @param projectId        项目ID
     * @param recoveryResultId 恢复结果ID
     * @param preloadedClasses 预加载的ClassInfo列表（可为null，为null时自行查询）
     * @return Phase1Result 包含已分配的组件和未恢复的类名
     */
    public Phase1Result recover(Long projectId, Long recoveryResultId, List<ClassInfo> preloadedClasses) {
        log.info("Phase1: 基于FSK的架构恢复开始");

        List<ClassInfo> allClasses = preloadedClasses != null ?
                preloadedClasses : classInfoRepository.findByProjectId(projectId);
        List<FunctionKnowledge> fskNodes = functionKnowledgeRepository.findByProjectId(projectId);

        // 构建FQN集合
        Set<String> allClassFqns = allClasses.stream()
                .map(ClassInfo::getFullyQualifiedName)
                .collect(Collectors.toSet());

        // 优化：构建simpleName -> FQN列表的索引，用于模糊匹配
        Map<String, List<String>> simpleNameIndex = new HashMap<>();
        for (ClassInfo ci : allClasses) {
            simpleNameIndex.computeIfAbsent(ci.getSimpleName(), k -> new ArrayList<>())
                    .add(ci.getFullyQualifiedName());
        }

        Set<String> assignedClasses = new HashSet<>();
        List<RecoveredComponent> components = new ArrayList<>();

        // 优化：叶子节点优先处理，再处理父节点
        List<FunctionKnowledge> leafNodes = new ArrayList<>();
        List<FunctionKnowledge> parentNodes = new ArrayList<>();
        for (FunctionKnowledge fk : fskNodes) {
            if (fk.getRelatedClassNames() == null || fk.getRelatedClassNames().isEmpty()) {
                continue;
            }
            if (Boolean.TRUE.equals(fk.getIsLeaf()) || fk.getParentFunctionId() != null) {
                leafNodes.add(fk);
            } else {
                parentNodes.add(fk);
            }
        }

        // 先处理叶子节点（更精确的分配）
        processNodes(leafNodes, projectId, recoveryResultId, allClassFqns,
                simpleNameIndex, assignedClasses, components);

        // 再处理父节点（仅分配未被叶子节点覆盖的类）
        processNodes(parentNodes, projectId, recoveryResultId, allClassFqns,
                simpleNameIndex, assignedClasses, components);

        // 未恢复的类
        Set<String> unrecovered = new HashSet<>(allClassFqns);
        unrecovered.removeAll(assignedClasses);

        log.info("Phase1完成: {} 个组件, {} 个已分配类, {} 个未恢复类",
                components.size(), assignedClasses.size(), unrecovered.size());

        return new Phase1Result(components, new ArrayList<>(unrecovered));
    }

    /**
     * 兼容旧接口（不传预加载数据）
     */
    public Phase1Result recover(Long projectId, Long recoveryResultId) {
        return recover(projectId, recoveryResultId, null);
    }

    /**
     * 处理一批FSK节点，将关联的类分配到组件
     * 严格置信度检查：只接受精确FQN匹配的类，simpleName模糊匹配仅在有包名上下文时使用
     */
    private void processNodes(List<FunctionKnowledge> nodes, Long projectId, Long recoveryResultId,
                               Set<String> allClassFqns, Map<String, List<String>> simpleNameIndex,
                               Set<String> assignedClasses, List<RecoveredComponent> components) {
        for (FunctionKnowledge fk : nodes) {
            List<String> relatedClasses = Arrays.asList(fk.getRelatedClassNames().split(","));

            // 第一遍：只收集精确FQN匹配的类，建立包名上下文
            List<String> exactMatches = new ArrayList<>();
            Set<String> nodePackagePrefixes = new HashSet<>();
            int totalNames = 0;

            for (String rawName : relatedClasses) {
                String name = rawName.trim();
                if (name.isEmpty()) continue;
                totalNames++;

                if (allClassFqns.contains(name) && !assignedClasses.contains(name)) {
                    exactMatches.add(name);
                    String pkg = extractPackagePrefix(name, 3);
                    if (pkg != null) nodePackagePrefixes.add(pkg);
                }
            }

            // 置信度检查：如果没有任何精确匹配，跳过该节点
            if (exactMatches.isEmpty() && totalNames > 1) {
                log.debug("Phase1跳过无精确匹配FSK节点 '{}': 0/{} 精确匹配",
                        fk.getFunctionName(), totalNames);
                continue;
            }

            // 第二遍：对非精确匹配的类，仅在有包名上下文时进行严格模糊匹配
            List<String> fuzzyMatches = new ArrayList<>();
            if (!nodePackagePrefixes.isEmpty()) {
                for (String rawName : relatedClasses) {
                    String name = rawName.trim();
                    if (name.isEmpty()) continue;
                    if (allClassFqns.contains(name)) continue; // 已在第一遍处理

                    String matched = resolveClassNameStrict(name, allClassFqns, simpleNameIndex, nodePackagePrefixes);
                    if (matched != null && !assignedClasses.contains(matched)
                            && !exactMatches.contains(matched)) {
                        fuzzyMatches.add(matched);
                    }
                }
            }

            // 合并精确匹配和严格模糊匹配
            List<String> validClasses = new ArrayList<>(exactMatches);
            validClasses.addAll(fuzzyMatches);

            if (validClasses.isEmpty()) {
                continue;
            }

            // 如果模糊匹配数量远超精确匹配（>2倍），只保留精确匹配
            if (!exactMatches.isEmpty() && fuzzyMatches.size() > exactMatches.size() * 2) {
                log.debug("Phase1节点 '{}' 模糊匹配过多({} vs {}精确)，只保留精确匹配",
                        fk.getFunctionName(), fuzzyMatches.size(), exactMatches.size());
                validClasses = exactMatches;
            }

            RecoveredComponent comp = new RecoveredComponent();
            comp.setProjectId(projectId);
            comp.setRecoveryResultId(recoveryResultId);
            comp.setName(fk.getFunctionName());
            comp.setLevel(fk.getParentFunctionId() != null ? 2 : 1);
            comp.setClassNames(String.join(",", validClasses));
            comp.setSource("FSK");

            components.add(comp);
            assignedClasses.addAll(validClasses);
        }
    }

    /**
     * 提取FQN的包名前缀（取前N级）
     */
    private String extractPackagePrefix(String fqn, int levels) {
        String[] parts = fqn.split("\\.");
        if (parts.length <= levels) return fqn;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * 严格模糊匹配：只在有包名上下文且候选唯一或包名一致时才返回匹配
     * 比 resolveClassNameWithContext 更严格，避免将不相关的类拉入FSK组件
     */
    private String resolveClassNameStrict(String name, Set<String> allClassFqns,
                                           Map<String, List<String>> simpleNameIndex,
                                           Set<String> nodePackagePrefixes) {
        // 1. 精确匹配FQN
        if (allClassFqns.contains(name)) {
            return name;
        }

        // 2. simpleName查找 — 必须有包名上下文才接受
        List<String> candidates = simpleNameIndex.get(name);
        if (candidates != null && !candidates.isEmpty()) {
            if (candidates.size() == 1) {
                // 唯一候选：检查包名前缀是否与节点一致
                String prefix = extractPackagePrefix(candidates.get(0), 3);
                if (prefix != null && nodePackagePrefixes.contains(prefix)) {
                    return candidates.get(0);
                }
                // 唯一候选但包名不一致，不分配
                return null;
            }
            // 多个候选：只接受包名前缀一致的
            for (String candidate : candidates) {
                String prefix = extractPackagePrefix(candidate, 3);
                if (prefix != null && nodePackagePrefixes.contains(prefix)) {
                    return candidate;
                }
            }
            return null; // 多候选无一致包名，不分配
        }

        // 3. 从FQN中提取simpleName再匹配
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            String simpleName = name.substring(lastDot + 1);
            candidates = simpleNameIndex.get(simpleName);
            if (candidates != null && !candidates.isEmpty()) {
                // 优先匹配包名前缀完全相同的
                String prefix = name.substring(0, lastDot);
                for (String candidate : candidates) {
                    if (candidate.startsWith(prefix)) {
                        return candidate;
                    }
                }
                // 再检查节点包名一致性
                for (String candidate : candidates) {
                    String candPrefix = extractPackagePrefix(candidate, 3);
                    if (candPrefix != null && nodePackagePrefixes.contains(candPrefix)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    public static class Phase1Result {
        public final List<RecoveredComponent> components;
        public final List<String> unrecoveredClassNames;

        public Phase1Result(List<RecoveredComponent> components, List<String> unrecoveredClassNames) {
            this.components = components;
            this.unrecoveredClassNames = unrecoveredClassNames;
        }
    }
}
