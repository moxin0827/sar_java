package com.example.javaparser.service.recovery.clustering;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * DBSCAN 聚类算法（基于预计算距离矩阵）
 *
 * 自动发现任意形状的聚类，不需要预设聚类数。
 * 噪声点会被分配到最近的聚类（架构恢复不允许孤立类）。
 */
@Slf4j
@Service
public class DbscanAlgorithm implements ClusteringAlgorithm {

    @Override
    public String getName() {
        return "DBSCAN";
    }

    @Override
    public Map<Integer, List<Integer>> cluster(double[][] similarity, ClusteringParams params) {
        int n = similarity.length;
        if (n == 0) return Collections.emptyMap();
        if (n == 1) return Collections.singletonMap(0, Collections.singletonList(0));

        int minPts = params.getMinPts() != null ? params.getMinPts() : 2;

        // 1. 相似度 → 距离矩阵
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dist[i][j] = (i == j) ? 0.0 : Math.max(0.0, 1.0 - similarity[i][j]);
            }
        }

        // 自适应 eps：如果未指定，使用 k-distance 启发式估计
        double eps;
        if (params.getEps() != null) {
            eps = params.getEps();
        } else {
            eps = estimateEps(dist, minPts, n);
            log.info("DBSCAN自适应eps估计: {} (基于{}-distance中位数)", String.format("%.4f", eps), minPts);
        }

        log.info("DBSCAN聚类: eps={}, minPts={}, n={}", String.format("%.4f", eps), minPts, n);

        // 2. DBSCAN 核心
        int[] labels = new int[n]; // 0=未访问, -1=噪声, >0=聚类ID
        boolean[] visited = new boolean[n];
        int clusterId = 0;

        for (int i = 0; i < n; i++) {
            if (visited[i]) continue;
            visited[i] = true;

            List<Integer> neighbors = regionQuery(dist, i, eps, n);

            if (neighbors.size() < minPts) {
                labels[i] = -1; // 标记为噪声
            } else {
                clusterId++;
                expandCluster(dist, i, neighbors, clusterId, labels, visited, eps, minPts, n);
            }
        }

        log.info("DBSCAN初步结果: {} 个聚类, {} 个噪声点",
                clusterId, countNoise(labels));

        // 3. 噪声点分配到最近聚类
        if (clusterId == 0) {
            // 全部为噪声，放入一个聚类
            log.warn("DBSCAN未发现任何聚类（所有点为噪声），将所有点归为一个聚类");
            return Collections.singletonMap(0,
                    IntStream.range(0, n).boxed().collect(Collectors.toList()));
        }

        assignNoiseToNearestCluster(dist, labels, clusterId, n);

        // 4. 构建结果
        Map<Integer, List<Integer>> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int cid = labels[i] - 1; // 转为 0-based
            result.computeIfAbsent(cid, k -> new ArrayList<>()).add(i);
        }

        log.info("DBSCAN聚类完成: {} 个聚类", result.size());
        return result;
    }

    /**
     * 查找 eps 邻域内的所有点
     */
    private List<Integer> regionQuery(double[][] dist, int point, double eps, int n) {
        List<Integer> neighbors = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            if (dist[point][j] <= eps) {
                neighbors.add(j);
            }
        }
        return neighbors;
    }

    /**
     * 扩展聚类
     */
    private void expandCluster(double[][] dist, int point, List<Integer> neighbors,
                                int clusterId, int[] labels, boolean[] visited,
                                double eps, int minPts, int n) {
        labels[point] = clusterId;

        // 使用队列遍历（避免递归栈溢出）
        Queue<Integer> queue = new LinkedList<>(neighbors);
        Set<Integer> inQueue = new HashSet<>(neighbors);

        while (!queue.isEmpty()) {
            int current = queue.poll();

            if (!visited[current]) {
                visited[current] = true;
                List<Integer> currentNeighbors = regionQuery(dist, current, eps, n);

                if (currentNeighbors.size() >= minPts) {
                    for (int nb : currentNeighbors) {
                        if (!inQueue.contains(nb)) {
                            queue.add(nb);
                            inQueue.add(nb);
                        }
                    }
                }
            }

            if (labels[current] <= 0) {
                labels[current] = clusterId;
            }
        }
    }

    /**
     * 将噪声点分配到最近的聚类
     */
    private void assignNoiseToNearestCluster(double[][] dist, int[] labels,
                                              int maxClusterId, int n) {
        for (int i = 0; i < n; i++) {
            if (labels[i] != -1) continue;

            double minDist = Double.MAX_VALUE;
            int nearestCluster = 1;

            for (int j = 0; j < n; j++) {
                if (labels[j] <= 0 || i == j) continue;
                if (dist[i][j] < minDist) {
                    minDist = dist[i][j];
                    nearestCluster = labels[j];
                }
            }

            labels[i] = nearestCluster;
        }
    }

    private int countNoise(int[] labels) {
        int count = 0;
        for (int l : labels) {
            if (l == -1) count++;
        }
        return count;
    }

    /**
     * k-distance 启发式估计 eps（DBSCAN 论文推荐方法）
     * 对每个点计算第 minPts 近邻的距离，取这些距离的中位数作为 eps。
     */
    private double estimateEps(double[][] dist, int minPts, int n) {
        double[] kDistances = new double[n];
        for (int i = 0; i < n; i++) {
            // 收集点 i 到其他所有点的距离并排序
            double[] dists = new double[n - 1];
            int idx = 0;
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    dists[idx++] = dist[i][j];
                }
            }
            Arrays.sort(dists);
            // 第 minPts 近邻的距离（0-indexed，所以是 minPts-1）
            int kIdx = Math.min(minPts - 1, dists.length - 1);
            kDistances[i] = dists[kIdx];
        }
        // 取中位数
        Arrays.sort(kDistances);
        return kDistances[n / 2];
    }
}
