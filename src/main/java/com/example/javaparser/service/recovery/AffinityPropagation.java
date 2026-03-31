package com.example.javaparser.service.recovery;

import com.example.javaparser.config.RecoveryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.IntStream;

@Slf4j
@Service
public class AffinityPropagation {

    @Autowired
    private RecoveryProperties recoveryProperties;

    /**
     * 执行Affinity Propagation聚类（优化版）
     *
     * 优化点：
     * 1. responsibility更新使用top-2预计算，从O(N³)降为O(N²)
     * 2. availability更新使用列求和预计算，从O(N³)降为O(N²)
     * 3. medianPreference使用采样估计，避免全量排序
     * 4. responsibility和availability更新支持行/列级并行化
     *
     * @param similarity  相似度矩阵 (n x n)
     * @param damping     阻尼因子 (0.5 ~ 1.0)
     * @param maxIter     最大迭代次数
     * @param preference  偏好值（对角线值，影响聚类数量，越小聚类越少）
     * @return 聚类结果: exemplar index -> member indices
     */
    public Map<Integer, List<Integer>> cluster(double[][] similarity, double damping,
                                                int maxIter, double preference) {
        int n = similarity.length;
        if (n == 0) return Collections.emptyMap();

        // 设置preference（对角线）
        for (int i = 0; i < n; i++) {
            similarity[i][i] = preference;
        }

        double[][] responsibility = new double[n][n];
        double[][] availability = new double[n][n];

        int convergedCount = 0;
        int[] prevExemplars = new int[n];
        Arrays.fill(prevExemplars, -1);

        int parallelThreshold = recoveryProperties.getParallelThreshold() != null ? recoveryProperties.getParallelThreshold() : 50;
        boolean useParallel = Boolean.TRUE.equals(recoveryProperties.getEnableParallelClustering()) && n > parallelThreshold;

        for (int iter = 0; iter < maxIter; iter++) {
            // ===== responsibility更新 =====
            if (useParallel) {
                updateResponsibilityParallel(similarity, responsibility, availability, damping, n);
            } else {
                updateResponsibilitySequential(similarity, responsibility, availability, damping, n);
            }

            // ===== availability更新 =====
            if (useParallel) {
                updateAvailabilityParallel(responsibility, availability, damping, n);
            } else {
                updateAvailabilitySequential(responsibility, availability, damping, n);
            }

            // 检查收敛
            int[] exemplars = new int[n];
            for (int i = 0; i < n; i++) {
                double maxVal = Double.NEGATIVE_INFINITY;
                int bestK = i;
                for (int k = 0; k < n; k++) {
                    double val = responsibility[i][k] + availability[i][k];
                    if (val > maxVal) {
                        maxVal = val;
                        bestK = k;
                    }
                }
                exemplars[i] = bestK;
            }

            if (Arrays.equals(exemplars, prevExemplars)) {
                convergedCount++;
                if (convergedCount >= 10) {
                    log.info("AP聚类在第 {} 次迭代收敛{}", iter + 1, useParallel ? "（并行模式）" : "");
                    break;
                }
            } else {
                convergedCount = 0;
            }
            prevExemplars = exemplars.clone();
        }

        // 构建聚类结果
        Map<Integer, List<Integer>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            double maxVal = Double.NEGATIVE_INFINITY;
            int bestK = i;
            for (int k = 0; k < n; k++) {
                double val = responsibility[i][k] + availability[i][k];
                if (val > maxVal) {
                    maxVal = val;
                    bestK = k;
                }
            }
            clusters.computeIfAbsent(bestK, k -> new ArrayList<>()).add(i);
        }

        log.info("AP聚类完成: {} 个聚类", clusters.size());
        return clusters;
    }

    // ===== 并行版本 =====

    /**
     * 并行responsibility更新 - 按行并行，每行独立计算top-2
     */
    private void updateResponsibilityParallel(double[][] S, double[][] R,
                                               double[][] A, double damping, int n) {
        IntStream.range(0, n).parallel().forEach(i -> {
            double max1 = Double.NEGATIVE_INFINITY;
            double max2 = Double.NEGATIVE_INFINITY;
            int max1Idx = -1;

            for (int kk = 0; kk < n; kk++) {
                double val = A[i][kk] + S[i][kk];
                if (val > max1) {
                    max2 = max1;
                    max1 = val;
                    max1Idx = kk;
                } else if (val > max2) {
                    max2 = val;
                }
            }

            for (int k = 0; k < n; k++) {
                double maxVal = (k == max1Idx) ? max2 : max1;
                double newR = S[i][k] - maxVal;
                R[i][k] = damping * R[i][k] + (1 - damping) * newR;
            }
        });
    }

    /**
     * 并行availability更新 - 按列并行，每列独立计算列求和
     */
    private void updateAvailabilityParallel(double[][] R, double[][] A,
                                             double damping, int n) {
        IntStream.range(0, n).parallel().forEach(k -> {
            double colSum = 0;
            for (int ii = 0; ii < n; ii++) {
                if (ii != k) {
                    colSum += Math.max(0, R[ii][k]);
                }
            }

            // self-availability: a[k][k]
            double newAkk = colSum;
            A[k][k] = damping * A[k][k] + (1 - damping) * newAkk;

            // 其他行: a[i][k] where i != k
            for (int i = 0; i < n; i++) {
                if (i == k) continue;
                double sumWithoutI = colSum - Math.max(0, R[i][k]);
                double newA = Math.min(0, R[k][k] + sumWithoutI);
                A[i][k] = damping * A[i][k] + (1 - damping) * newA;
            }
        });
    }

    // ===== 顺序版本（回退模式） =====

    private void updateResponsibilitySequential(double[][] S, double[][] R,
                                                 double[][] A, double damping, int n) {
        for (int i = 0; i < n; i++) {
            double max1 = Double.NEGATIVE_INFINITY;
            double max2 = Double.NEGATIVE_INFINITY;
            int max1Idx = -1;

            for (int kk = 0; kk < n; kk++) {
                double val = A[i][kk] + S[i][kk];
                if (val > max1) {
                    max2 = max1;
                    max1 = val;
                    max1Idx = kk;
                } else if (val > max2) {
                    max2 = val;
                }
            }

            for (int k = 0; k < n; k++) {
                double maxVal = (k == max1Idx) ? max2 : max1;
                double newR = S[i][k] - maxVal;
                R[i][k] = damping * R[i][k] + (1 - damping) * newR;
            }
        }
    }

    private void updateAvailabilitySequential(double[][] R, double[][] A,
                                               double damping, int n) {
        for (int k = 0; k < n; k++) {
            double colSum = 0;
            for (int ii = 0; ii < n; ii++) {
                if (ii != k) {
                    colSum += Math.max(0, R[ii][k]);
                }
            }

            double newAkk = colSum;
            A[k][k] = damping * A[k][k] + (1 - damping) * newAkk;

            for (int i = 0; i < n; i++) {
                if (i == k) continue;
                double sumWithoutI = colSum - Math.max(0, R[i][k]);
                double newA = Math.min(0, R[k][k] + sumWithoutI);
                A[i][k] = damping * A[i][k] + (1 - damping) * newA;
            }
        }
    }

    /**
     * 计算中位数preference（优化版：采样估计，避免全量排序）
     * 对于小矩阵(N<=100)使用精确计算，大矩阵使用采样
     */
    public static double medianPreference(double[][] similarity) {
        return percentilePreference(similarity, 0.5);
    }

    /**
     * 计算指定百分位数的preference，带下限保护。
     * 当 preference 过低（接近0）时，AP 会产生过多聚类（如 slf4j 的 32 个）。
     * 下限保护确保 preference 不低于 10th percentile。
     *
     * percentile=0.0 返回最小值（Frey & Dueck 2007推荐，产生最少聚类）
     * percentile=0.5 返回中位数（产生中等数量聚类）
     * percentile=1.0 返回最大值（产生最多聚类）
     *
     * @param similarity  相似度矩阵
     * @param percentile  百分位数 [0.0, 1.0]
     */
    public static double percentilePreference(double[][] similarity, double percentile) {
        int n = similarity.length;
        if (n == 0) return 0;

        percentile = Math.max(0.0, Math.min(1.0, percentile));

        List<Double> values = collectSimilarityValues(similarity, n);
        Collections.sort(values);

        int idx = (int) (percentile * (values.size() - 1));
        double pref = values.get(idx);

        // 下限保护：preference 不低于 10th percentile，避免稀疏图产生过多聚类
        if (percentile < 0.1) {
            int minIdx = (int) (0.1 * (values.size() - 1));
            double lowerBound = values.get(minIdx);
            if (pref < lowerBound) {
                log.debug("AP preference下限保护: {} -> {} (10th percentile)", pref, lowerBound);
                pref = lowerBound;
            }
        }

        return pref;
    }

    /**
     * 收集相似度矩阵中的非对角线值（小矩阵精确，大矩阵采样）
     */
    private static List<Double> collectSimilarityValues(double[][] similarity, int n) {
        if (n <= 100) {
            List<Double> values = new ArrayList<>(n * (n - 1));
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j) {
                        values.add(similarity[i][j]);
                    }
                }
            }
            return values;
        }

        // 大矩阵：采样估计（采样约10000个值）
        int sampleSize = Math.min(10000, n * (n - 1));
        Random rng = new Random(42);
        List<Double> samples = new ArrayList<>(sampleSize);
        for (int s = 0; s < sampleSize; s++) {
            int i = rng.nextInt(n);
            int j = rng.nextInt(n - 1);
            if (j >= i) j++;
            samples.add(similarity[i][j]);
        }
        return samples;
    }

    /**
     * 返回最小值preference（产生最少聚类，Frey & Dueck 2007原论文推荐）
     */
    public static double minimumPreference(double[][] similarity) {
        return percentilePreference(similarity, 0.0);
    }
}
