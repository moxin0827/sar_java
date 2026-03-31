package com.example.javaparser.service.recovery.clustering;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 层次聚类（凝聚式 Agglomerative Clustering）
 *
 * 支持 SINGLE / COMPLETE / AVERAGE / WARD 四种链接准则。
 * 使用距离阈值切割树状图来确定最终聚类数。
 * 复杂度 O(n³)，适用于 n < 1000 的场景。
 */
@Slf4j
@Service
public class AgglomerativeAlgorithm implements ClusteringAlgorithm {

    @Override
    public String getName() {
        return "AGGLOMERATIVE";
    }

    @Override
    public Map<Integer, List<Integer>> cluster(double[][] similarity, ClusteringParams params) {
        int n = similarity.length;
        if (n == 0) return Collections.emptyMap();
        if (n == 1) return Collections.singletonMap(0, Collections.singletonList(0));

        String linkage = params.getLinkage() != null ? params.getLinkage().toUpperCase() : "AVERAGE";
        double simThreshold = params.getThreshold() != null ? params.getThreshold() : 0.5;
        double distThreshold = 1.0 - simThreshold;

        log.info("层次聚类: linkage={}, simThreshold={}, distThreshold={}", linkage, simThreshold, distThreshold);

        // 1. 相似度 → 距离矩阵
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dist[i][j] = (i == j) ? 0.0 : Math.max(0.0, 1.0 - similarity[i][j]);
            }
        }

        // 2. 初始化：每个点为一个聚类
        // clusterMembers: clusterId -> 成员索引列表
        Map<Integer, List<Integer>> clusterMembers = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            List<Integer> members = new ArrayList<>();
            members.add(i);
            clusterMembers.put(i, members);
        }

        // 聚类间距离矩阵（使用 Map 存储活跃聚类对的距离）
        // 初始时等于点间距离
        Map<Integer, Map<Integer, Double>> clusterDist = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Map<Integer, Double> row = new HashMap<>();
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    row.put(j, dist[i][j]);
                }
            }
            clusterDist.put(i, row);
        }

        Set<Integer> activeClusters = new LinkedHashSet<>(clusterMembers.keySet());
        int nextClusterId = n;

        // 3. 迭代合并
        for (int iter = 0; iter < n - 1; iter++) {
            if (activeClusters.size() <= 1) break;

            // 找最近的两个聚类
            double minDist = Double.MAX_VALUE;
            int mergeA = -1, mergeB = -1;

            for (int a : activeClusters) {
                Map<Integer, Double> rowA = clusterDist.get(a);
                if (rowA == null) continue;
                for (int b : activeClusters) {
                    if (b <= a) continue;
                    Double d = rowA.get(b);
                    if (d != null && d < minDist) {
                        minDist = d;
                        mergeA = a;
                        mergeB = b;
                    }
                }
            }

            if (mergeA == -1 || mergeB == -1) break;

            // 如果最小距离超过阈值，停止合并
            if (minDist > distThreshold) {
                log.info("层次聚类在第 {} 次合并后停止（最小距离 {} > 阈值 {}）",
                        iter, String.format("%.4f", minDist), String.format("%.4f", distThreshold));
                break;
            }

            // 合并 mergeA 和 mergeB 为新聚类
            int newId = nextClusterId++;
            List<Integer> newMembers = new ArrayList<>();
            newMembers.addAll(clusterMembers.get(mergeA));
            newMembers.addAll(clusterMembers.get(mergeB));
            clusterMembers.put(newId, newMembers);

            int sizeA = clusterMembers.get(mergeA).size();
            int sizeB = clusterMembers.get(mergeB).size();

            // 计算新聚类到其他活跃聚类的距离（Lance-Williams公式）
            Map<Integer, Double> newRow = new HashMap<>();
            for (int other : activeClusters) {
                if (other == mergeA || other == mergeB) continue;

                double dA = getClusterDist(clusterDist, mergeA, other);
                double dB = getClusterDist(clusterDist, mergeB, other);

                double newDist;
                switch (linkage) {
                    case "SINGLE":
                        newDist = Math.min(dA, dB);
                        break;
                    case "COMPLETE":
                        newDist = Math.max(dA, dB);
                        break;
                    case "WARD": {
                        // Ward's method: 合并后方差增量最小的两个聚类
                        // Lance-Williams 公式: d(new, other) = sqrt(
                        //   ((|other|+|A|)*dA² + (|other|+|B|)*dB² - |other|*dAB²) / (|other|+|A|+|B|))
                        int sizeOther = clusterMembers.get(other).size();
                        double dAB = getClusterDist(clusterDist, mergeA, mergeB);
                        int sizeNew = sizeA + sizeB;
                        newDist = Math.sqrt(
                                ((sizeOther + sizeA) * dA * dA
                                        + (sizeOther + sizeB) * dB * dB
                                        - sizeOther * dAB * dAB)
                                        / (sizeOther + sizeNew));
                        break;
                    }
                    case "AVERAGE":
                    default:
                        newDist = (sizeA * dA + sizeB * dB) / (sizeA + sizeB);
                        break;
                }

                newRow.put(other, newDist);
                // 更新对方到新聚类的距离
                Map<Integer, Double> otherRow = clusterDist.get(other);
                if (otherRow != null) {
                    otherRow.put(newId, newDist);
                }
            }
            clusterDist.put(newId, newRow);

            // 移除旧聚类
            activeClusters.remove(mergeA);
            activeClusters.remove(mergeB);
            clusterDist.remove(mergeA);
            clusterDist.remove(mergeB);
            // 从其他行中移除旧聚类的引用
            for (int other : activeClusters) {
                Map<Integer, Double> otherRow = clusterDist.get(other);
                if (otherRow != null) {
                    otherRow.remove(mergeA);
                    otherRow.remove(mergeB);
                }
            }
            clusterMembers.remove(mergeA);
            clusterMembers.remove(mergeB);

            activeClusters.add(newId);
        }

        // 4. 构建结果（重新编号为 0-based）
        Map<Integer, List<Integer>> result = new LinkedHashMap<>();
        int idx = 0;
        for (int clusterId : activeClusters) {
            List<Integer> members = clusterMembers.get(clusterId);
            if (members != null && !members.isEmpty()) {
                result.put(idx++, members);
            }
        }

        log.info("层次聚类完成: {} 个聚类（linkage={}）", result.size(), linkage);
        return result;
    }

    private double getClusterDist(Map<Integer, Map<Integer, Double>> clusterDist, int a, int b) {
        Map<Integer, Double> row = clusterDist.get(a);
        if (row != null && row.containsKey(b)) {
            return row.get(b);
        }
        row = clusterDist.get(b);
        if (row != null && row.containsKey(a)) {
            return row.get(a);
        }
        return Double.MAX_VALUE;
    }
}
