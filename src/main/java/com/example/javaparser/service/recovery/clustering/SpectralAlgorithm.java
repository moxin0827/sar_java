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
 * 谱聚类（Spectral Clustering）
 *
 * 利用图拉普拉斯矩阵的特征向量进行降维，再用 KMeans 聚类。
 * 适合发现非凸形状的聚类结构。
 *
 * 依赖 commons-math3 的 EigenDecomposition 和 KMeansPlusPlusClusterer。
 */
@Slf4j
@Service
public class SpectralAlgorithm implements ClusteringAlgorithm {

    @Override
    public String getName() {
        return "SPECTRAL";
    }

    @Override
    public Map<Integer, List<Integer>> cluster(double[][] similarity, ClusteringParams params) {
        int n = similarity.length;
        if (n == 0) return Collections.emptyMap();
        if (n == 1) return Collections.singletonMap(0, Collections.singletonList(0));
        if (n == 2) {
            Map<Integer, List<Integer>> result = new LinkedHashMap<>();
            result.put(0, Arrays.asList(0, 1));
            return result;
        }

        int requestedK = params.getNumClusters() != null ? params.getNumClusters() : 0;
        int maxIter = params.getKmeansMaxIter() != null ? params.getKmeansMaxIter() : 100;

        log.info("谱聚类: n={}, requestedK={}", n, requestedK);

        // 1. 构建权重矩阵 W（使用相似度矩阵，对角线置0）
        double[][] W = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                W[i][j] = (i == j) ? 0.0 : Math.max(0.0, similarity[i][j]);
            }
        }

        // 2. 计算度矩阵 D 和 D^(-1/2)
        double[] degree = new double[n];
        double[] dInvSqrt = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                degree[i] += W[i][j];
            }
            dInvSqrt[i] = degree[i] > 1e-10 ? 1.0 / Math.sqrt(degree[i]) : 0.0;
        }

        // 3. 构建归一化拉普拉斯矩阵 L_sym = I - D^(-1/2) * W * D^(-1/2)
        double[][] laplacian = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    laplacian[i][j] = 1.0;
                } else {
                    laplacian[i][j] = -dInvSqrt[i] * W[i][j] * dInvSqrt[j];
                }
            }
        }

        // 4. 特征分解
        RealMatrix L = new Array2DRowRealMatrix(laplacian);
        EigenDecomposition eigen = new EigenDecomposition(L);
        double[] eigenvalues = eigen.getRealEigenvalues();

        // commons-math3 返回特征值降序排列，我们需要最小的特征值
        // 构建 (eigenvalue, index) 对并按特征值升序排序
        double[] sortedEigenvalues = new double[n];
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, Comparator.comparingDouble(a -> eigenvalues[a]));

        for (int i = 0; i < n; i++) {
            sortedEigenvalues[i] = eigenvalues[indices[i]];
        }

        // 5. 确定 K
        int k;
        if (requestedK > 0) {
            k = Math.min(requestedK, n - 1);
        } else {
            k = detectKByEigengap(sortedEigenvalues, n);
        }
        k = Math.max(2, Math.min(k, n - 1));

        log.info("谱聚类选定 K={}", k);

        // 6. 取前 k 个最小非零特征值对应的特征向量
        // 跳过第一个（接近0的特征值，对应常数向量）
        double[][] U = new double[n][k];
        for (int col = 0; col < k; col++) {
            int eigIdx = indices[col + 1]; // 跳过最小的（≈0）
            double[] eigVec = eigen.getEigenvector(eigIdx).toArray();
            for (int row = 0; row < n; row++) {
                U[row][col] = eigVec[row];
            }
        }

        // 7. 行归一化
        for (int i = 0; i < n; i++) {
            double norm = 0;
            for (int j = 0; j < k; j++) {
                norm += U[i][j] * U[i][j];
            }
            norm = Math.sqrt(norm);
            if (norm > 1e-10) {
                for (int j = 0; j < k; j++) {
                    U[i][j] /= norm;
                }
            }
        }

        // 8. 对 U 的行执行 KMeans++（多次运行取最优，减少随机性影响）
        List<DoublePoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            points.add(new DoublePoint(U[i]));
        }

        // 多次运行 KMeans，取 SSE 最小的结果
        int numRuns = 3;
        List<CentroidCluster<DoublePoint>> bestClusters = null;
        double bestSSE = Double.MAX_VALUE;

        for (int run = 0; run < numRuns; run++) {
            KMeansPlusPlusClusterer<DoublePoint> kmeans = new KMeansPlusPlusClusterer<>(k, maxIter);
            List<CentroidCluster<DoublePoint>> clusters = kmeans.cluster(points);
            double sse = computeSSE(clusters);
            if (sse < bestSSE) {
                bestSSE = sse;
                bestClusters = clusters;
            }
        }

        List<CentroidCluster<DoublePoint>> clusters = bestClusters;
        log.debug("谱聚类KMeans最优SSE: {}", String.format("%.4f", bestSSE));

        // 9. 转换结果
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

        // 处理可能未被分配的点（KMeans 理论上不会遗漏，但做防御）
        Set<Integer> assigned = result.values().stream()
                .flatMap(List::stream).collect(Collectors.toSet());
        if (assigned.size() < n) {
            List<Integer> unassigned = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!assigned.contains(i)) unassigned.add(i);
            }
            if (!unassigned.isEmpty()) {
                int lastKey = result.isEmpty() ? 0 : Collections.max(result.keySet()) + 1;
                result.put(lastKey, unassigned);
            }
        }

        log.info("谱聚类完成: {} 个聚类", result.size());
        return result;
    }

    /**
     * Eigengap 启发式自动选 K
     * 改进：搜索上限 min(sqrt(n), 30)，增加相对阈值避免平坦分布选过小K
     */
    private int detectKByEigengap(double[] sortedEigenvalues, int n) {
        int maxK = Math.min((int) Math.ceil(Math.sqrt(n)), 30);
        maxK = Math.max(maxK, 2);

        double maxGap = 0;
        int bestK = 2;

        // 从第2个特征值开始（跳过第1个≈0的）
        for (int i = 1; i < Math.min(maxK, sortedEigenvalues.length - 1); i++) {
            double gap = sortedEigenvalues[i + 1] - sortedEigenvalues[i];
            // 相对阈值：gap 必须大于当前特征值的 10%，避免平坦分布误判
            double relativeThreshold = Math.abs(sortedEigenvalues[i]) * 0.1;
            if (gap > maxGap && gap > relativeThreshold) {
                maxGap = gap;
                bestK = i; // i 个特征向量 → i 个聚类
            }
        }

        log.debug("Eigengap检测: bestK={}, maxGap={}, searchLimit={}", bestK, maxGap, maxK);
        return bestK;
    }

    /**
     * 计算 KMeans 聚类的 SSE（Sum of Squared Errors）
     */
    private double computeSSE(List<CentroidCluster<DoublePoint>> clusters) {
        double sse = 0;
        for (CentroidCluster<DoublePoint> cluster : clusters) {
            double[] center = cluster.getCenter().getPoint();
            for (DoublePoint p : cluster.getPoints()) {
                double[] point = p.getPoint();
                for (int d = 0; d < center.length; d++) {
                    double diff = point[d] - center[d];
                    sse += diff * diff;
                }
            }
        }
        return sse;
    }
}
