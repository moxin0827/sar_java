package com.example.javaparser.service.recovery.clustering;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.DoublePoint;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * K-Means + Silhouette 自动选K 聚类算法
 *
 * 遍历 K ∈ [kMin, kMax]，用 Silhouette 分数选最优 K。
 * 使用相似度矩阵的特征向量降维后作为 KMeans 输入，避免高维空间的维度灾难。
 *
 * 依赖 commons-math3 的 KMeansPlusPlusClusterer 和 EigenDecomposition。
 */
@Slf4j
@Service
public class KMeansSilhouetteAlgorithm implements ClusteringAlgorithm {

    @Override
    public String getName() {
        return "KMEANS_SILHOUETTE";
    }

    @Override
    public Map<Integer, List<Integer>> cluster(double[][] similarity, ClusteringParams params) {
        int n = similarity.length;
        if (n == 0) return Collections.emptyMap();
        if (n == 1) return Collections.singletonMap(0, Collections.singletonList(0));

        int kMin = params.getKMin() != null ? params.getKMin() : 2;
        int kMax = params.getKMax() != null && params.getKMax() > 0 ?
                params.getKMax() : (int) Math.ceil(Math.sqrt(n));
        int maxIter = params.getKmeansMaxIter() != null ? params.getKmeansMaxIter() : 100;
        int fixedK = params.getNumClusters() != null ? params.getNumClusters() : 0;

        // 限制范围
        kMin = Math.max(2, kMin);
        kMax = Math.min(kMax, n - 1);
        if (kMax < kMin) kMax = kMin;

        log.info("KMeans+Silhouette: n={}, kMin={}, kMax={}, fixedK={}", n, kMin, kMax, fixedK);

        // 使用特征向量降维替代原始相似度行，避免高维空间的维度灾难
        int targetDim = Math.min(kMax, 50);
        List<DoublePoint> points = computeSpectralEmbedding(similarity, n, targetDim);

        // 预计算距离矩阵（用于 Silhouette，基于原始相似度）
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dist[i][j] = (i == j) ? 0.0 : Math.max(0.0, 1.0 - similarity[i][j]);
            }
        }

        // 如果指定了固定 K，直接使用
        if (fixedK > 0) {
            fixedK = Math.min(fixedK, n - 1);
            fixedK = Math.max(2, fixedK);
            return runKMeansAndConvert(points, fixedK, maxIter, n);
        }

        // 遍历 K，选最优 Silhouette
        double bestScore = -1;
        int bestK = kMin;
        List<CentroidCluster<DoublePoint>> bestClusters = null;

        for (int k = kMin; k <= kMax; k++) {
            KMeansPlusPlusClusterer<DoublePoint> clusterer =
                    new KMeansPlusPlusClusterer<>(k, maxIter);
            List<CentroidCluster<DoublePoint>> clusters = clusterer.cluster(points);

            // 构建点到聚类的映射
            int[] assignments = buildAssignments(clusters, points, n);
            double score = computeSilhouette(assignments, k, dist, n);

            log.debug("K={}, silhouette={}", k, String.format("%.4f", score));

            if (score > bestScore) {
                bestScore = score;
                bestK = k;
                bestClusters = clusters;
            }
        }

        log.info("KMeans最优K={}, silhouette={}", bestK, String.format("%.4f", bestScore));

        if (bestClusters == null) {
            return runKMeansAndConvert(points, kMin, maxIter, n);
        }

        return convertClusters(bestClusters, points, n);
    }

    /**
     * 执行 KMeans 并转换结果
     */
    private Map<Integer, List<Integer>> runKMeansAndConvert(List<DoublePoint> points,
                                                             int k, int maxIter, int n) {
        KMeansPlusPlusClusterer<DoublePoint> clusterer =
                new KMeansPlusPlusClusterer<>(k, maxIter);
        List<CentroidCluster<DoublePoint>> clusters = clusterer.cluster(points);
        return convertClusters(clusters, points, n);
    }

    /**
     * 构建点到聚类的分配数组
     */
    private int[] buildAssignments(List<CentroidCluster<DoublePoint>> clusters,
                                    List<DoublePoint> points, int n) {
        int[] assignments = new int[n];
        Arrays.fill(assignments, -1);

        // 建立 DoublePoint 到索引的映射（通过引用相等）
        Map<DoublePoint, Integer> pointIndex = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            pointIndex.put(points.get(i), i);
        }

        for (int c = 0; c < clusters.size(); c++) {
            for (DoublePoint p : clusters.get(c).getPoints()) {
                Integer idx = pointIndex.get(p);
                if (idx != null) {
                    assignments[idx] = c;
                }
            }
        }

        return assignments;
    }

    /**
     * 计算 Silhouette 分数
     *
     * s(i) = (b(i) - a(i)) / max(a(i), b(i))
     * a(i) = 同聚类内平均距离
     * b(i) = 最近其他聚类的平均距离
     */
    private double computeSilhouette(int[] assignments, int k, double[][] dist, int n) {
        if (k <= 1) return 0;

        // 预计算每个聚类的成员
        List<List<Integer>> clusterMembers = new ArrayList<>();
        for (int c = 0; c < k; c++) {
            clusterMembers.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            if (assignments[i] >= 0 && assignments[i] < k) {
                clusterMembers.get(assignments[i]).add(i);
            }
        }

        double totalSilhouette = 0;
        int validCount = 0;

        for (int i = 0; i < n; i++) {
            int ci = assignments[i];
            if (ci < 0 || ci >= k) continue;

            List<Integer> myCluster = clusterMembers.get(ci);

            // a(i): 同聚类内平均距离
            double ai = 0;
            if (myCluster.size() > 1) {
                for (int j : myCluster) {
                    if (j != i) ai += dist[i][j];
                }
                ai /= (myCluster.size() - 1);
            }

            // b(i): 最近其他聚类的平均距离
            double bi = Double.MAX_VALUE;
            for (int c = 0; c < k; c++) {
                if (c == ci) continue;
                List<Integer> otherCluster = clusterMembers.get(c);
                if (otherCluster.isEmpty()) continue;

                double avgDist = 0;
                for (int j : otherCluster) {
                    avgDist += dist[i][j];
                }
                avgDist /= otherCluster.size();
                bi = Math.min(bi, avgDist);
            }

            if (bi == Double.MAX_VALUE) bi = 0;

            double si = 0;
            double maxAB = Math.max(ai, bi);
            if (maxAB > 0) {
                si = (bi - ai) / maxAB;
            }

            totalSilhouette += si;
            validCount++;
        }

        return validCount > 0 ? totalSilhouette / validCount : 0;
    }

    /**
     * 将 KMeans 聚类结果转换为标准格式
     */
    private Map<Integer, List<Integer>> convertClusters(List<CentroidCluster<DoublePoint>> clusters,
                                                         List<DoublePoint> points, int n) {
        Map<DoublePoint, Integer> pointIndex = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            pointIndex.put(points.get(i), i);
        }

        Map<Integer, List<Integer>> result = new LinkedHashMap<>();
        for (int c = 0; c < clusters.size(); c++) {
            List<Integer> members = new ArrayList<>();
            for (DoublePoint p : clusters.get(c).getPoints()) {
                Integer idx = pointIndex.get(p);
                if (idx != null) members.add(idx);
            }
            if (!members.isEmpty()) {
                result.put(c, members);
            }
        }

        // 处理未分配的点
        Set<Integer> assigned = result.values().stream()
                .flatMap(List::stream).collect(Collectors.toSet());
        if (assigned.size() < n) {
            List<Integer> unassigned = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!assigned.contains(i)) unassigned.add(i);
            }
            if (!unassigned.isEmpty()) {
                int nextKey = result.isEmpty() ? 0 : Collections.max(result.keySet()) + 1;
                result.put(nextKey, unassigned);
            }
        }

        log.info("KMeans聚类完成: {} 个聚类", result.size());
        return result;
    }

    /**
     * 使用相似度矩阵的特征向量进行降维（类似谱聚类的降维思路）
     * 将 n 维相似度行降为 targetDim 维，KMeans 在低维空间运行效果更好且更快
     */
    private List<DoublePoint> computeSpectralEmbedding(double[][] similarity, int n, int targetDim) {
        targetDim = Math.min(targetDim, n - 1);
        targetDim = Math.max(targetDim, 2);

        try {
            // 构建归一化拉普拉斯矩阵 L_sym = D^(-1/2) * W * D^(-1/2)
            // 这里直接用相似度矩阵的特征向量（等价于对归一化拉普拉斯取最大特征值对应的向量）
            double[][] W = new double[n][n];
            double[] degree = new double[n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    W[i][j] = (i == j) ? 0.0 : Math.max(0.0, similarity[i][j]);
                    degree[i] += W[i][j];
                }
            }

            double[] dInvSqrt = new double[n];
            for (int i = 0; i < n; i++) {
                dInvSqrt[i] = degree[i] > 1e-10 ? 1.0 / Math.sqrt(degree[i]) : 0.0;
            }

            // 归一化: D^(-1/2) * W * D^(-1/2)
            double[][] normW = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    normW[i][j] = dInvSqrt[i] * W[i][j] * dInvSqrt[j];
                }
            }

            RealMatrix M = new Array2DRowRealMatrix(normW);
            EigenDecomposition eigen = new EigenDecomposition(M);
            double[] eigenvalues = eigen.getRealEigenvalues();

            // 按特征值降序排序，取前 targetDim 个最大特征值对应的特征向量
            Integer[] indices = new Integer[n];
            for (int i = 0; i < n; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Double.compare(eigenvalues[b], eigenvalues[a]));

            double[][] U = new double[n][targetDim];
            for (int col = 0; col < targetDim; col++) {
                double[] eigVec = eigen.getEigenvector(indices[col]).toArray();
                for (int row = 0; row < n; row++) {
                    U[row][col] = eigVec[row];
                }
            }

            // 行归一化
            for (int i = 0; i < n; i++) {
                double norm = 0;
                for (int j = 0; j < targetDim; j++) {
                    norm += U[i][j] * U[i][j];
                }
                norm = Math.sqrt(norm);
                if (norm > 1e-10) {
                    for (int j = 0; j < targetDim; j++) {
                        U[i][j] /= norm;
                    }
                }
            }

            List<DoublePoint> points = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                points.add(new DoublePoint(U[i]));
            }

            log.info("KMeans特征向量降维: {}维 -> {}维", n, targetDim);
            return points;

        } catch (Exception e) {
            // 降维失败时回退到原始相似度行
            log.warn("特征向量降维失败，回退到原始相似度行: {}", e.getMessage());
            List<DoublePoint> points = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                points.add(new DoublePoint(similarity[i]));
            }
            return points;
        }
    }
}
