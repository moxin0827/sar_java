package com.example.javaparser.service.evaluation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MoJoFM（MoJo Fitness Metric）计算器
 *
 * MoJoFM(A, B) = 1 - mojo(A, B) / max(mojo(∀, B))
 *
 * 其中 mojo(A, B) 是将划分 A 转换为划分 B 所需的最少 Move + Join 操作数。
 * max(mojo(∀, B)) = n - |B|（n 为总实体数，|B| 为 B 的聚类数）
 *
 * 参考: Wen & Tzerpos, "An Effectiveness Measure for Software Clustering Algorithms", 2004
 */
@Slf4j
@Service
public class MoJoFMCalculator {

    /**
     * 计算 MoJoFM 值
     *
     * @param partitionA 恢复结果划分（组件名 -> 类名列表）
     * @param partitionB Ground Truth 划分（组件名 -> 类名列表）
     * @return MoJoFM 值（0-100，越高越好）
     */
    public double calculate(Map<String, List<String>> partitionA,
                             Map<String, List<String>> partitionB) {
        // 提取所有实体的交集（只评估两者都包含的类）
        Set<String> allEntitiesA = new HashSet<>();
        for (List<String> classes : partitionA.values()) {
            allEntitiesA.addAll(classes);
        }
        Set<String> allEntitiesB = new HashSet<>();
        for (List<String> classes : partitionB.values()) {
            allEntitiesB.addAll(classes);
        }

        // 取交集
        Set<String> commonEntities = new HashSet<>(allEntitiesA);
        commonEntities.retainAll(allEntitiesB);

        if (commonEntities.isEmpty()) {
            log.warn("MoJoFM: 恢复结果与 Ground Truth 无共同类");
            return 0.0;
        }

        // 过滤两个划分，只保留共同实体
        Map<String, List<String>> filteredA = filterPartition(partitionA, commonEntities);
        Map<String, List<String>> filteredB = filterPartition(partitionB, commonEntities);

        int n = commonEntities.size();
        int numClustersB = filteredB.size();

        // max(mojo) = n - |B|
        double maxMojo = n - numClustersB;
        if (maxMojo <= 0) {
            return 100.0; // 每个类一个聚类，完美匹配
        }

        // 计算 mojo(A, B)
        int mojoDistance = calculateMoJo(filteredA, filteredB, commonEntities);

        double mojoFM = (1.0 - (double) mojoDistance / maxMojo) * 100.0;
        mojoFM = Math.max(0.0, Math.min(100.0, mojoFM));

        log.info("MoJoFM: {}% (mojo={}, maxMojo={}, commonEntities={})",
                String.format("%.2f", mojoFM), mojoDistance, (int) maxMojo, n);
        return mojoFM;
    }

    /**
     * 计算 MoJo 距离（Move + Join 操作数）
     *
     * 使用贪心最大匹配近似：
     * 1. 构建 A×B 的重叠矩阵
     * 2. 贪心匹配 A 的每个聚类到 B 的最佳聚类
     * 3. Move 数 = 总实体数 - 最大匹配权重
     * 4. Join 数 = |映射到同一 B 聚类的 A 聚类数| - |被映射到的 B 聚类数|
     */
    private int calculateMoJo(Map<String, List<String>> partitionA,
                               Map<String, List<String>> partitionB,
                               Set<String> entities) {
        // 构建实体到 B 聚类的映射
        Map<String, String> entityToClusterB = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : partitionB.entrySet()) {
            for (String cls : entry.getValue()) {
                entityToClusterB.put(cls, entry.getKey());
            }
        }

        // 对 A 的每个聚类，找到与 B 重叠最多的聚类
        Map<String, String> clusterMapping = new HashMap<>(); // A聚类 -> B聚类
        int totalOverlap = 0;

        for (Map.Entry<String, List<String>> entryA : partitionA.entrySet()) {
            // 统计该 A 聚类中的实体分布在哪些 B 聚类中
            Map<String, Integer> overlapCount = new HashMap<>();
            for (String cls : entryA.getValue()) {
                String bCluster = entityToClusterB.get(cls);
                if (bCluster != null) {
                    overlapCount.merge(bCluster, 1, Integer::sum);
                }
            }

            // 选择重叠最多的 B 聚类
            String bestB = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> overlap : overlapCount.entrySet()) {
                if (overlap.getValue() > bestCount) {
                    bestCount = overlap.getValue();
                    bestB = overlap.getKey();
                }
            }

            if (bestB != null) {
                clusterMapping.put(entryA.getKey(), bestB);
                totalOverlap += bestCount;
            }
        }

        // Move 数 = 需要移动的实体数
        int moveCount = entities.size() - totalOverlap;

        // Join 数 = 映射到同一 B 聚类的 A 聚类需要合并的次数
        Map<String, Integer> bClusterCount = new HashMap<>();
        for (String bCluster : clusterMapping.values()) {
            bClusterCount.merge(bCluster, 1, Integer::sum);
        }
        int joinCount = 0;
        for (int count : bClusterCount.values()) {
            if (count > 1) {
                joinCount += count - 1;
            }
        }

        return moveCount + joinCount;
    }

    /**
     * 过滤划分，只保留指定实体
     */
    private Map<String, List<String>> filterPartition(Map<String, List<String>> partition,
                                                       Set<String> entities) {
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : partition.entrySet()) {
            List<String> filteredClasses = new ArrayList<>();
            for (String cls : entry.getValue()) {
                if (entities.contains(cls)) {
                    filteredClasses.add(cls);
                }
            }
            if (!filteredClasses.isEmpty()) {
                filtered.put(entry.getKey(), filteredClasses);
            }
        }
        return filtered;
    }
}
