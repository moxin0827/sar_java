package com.example.javaparser.service.recovery;

import com.example.javaparser.config.RecoveryProperties;
import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.ClassRelation;
import com.example.javaparser.service.llm.SemanticEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.IntStream;

@Slf4j
@Service
public class SimilarityCalculator {

    @Autowired
    private RecoveryProperties recoveryProperties;

    @Autowired
    private SemanticEmbeddingService semanticEmbeddingService;

    /**
     * 构建组合相似度矩阵 (structural + semantic)
     * 优化：支持并行计算，语义/结构矩阵分离后加权合并
     */
    public double[][] buildSimilarityMatrix(List<ClassInfo> classes, List<ClassRelation> relations,
                                             Double structWeight, Double semanticWeight) {
        int n = classes.size();
        double[][] matrix = new double[n][n];

        double sw = structWeight != null ? structWeight : recoveryProperties.getStructWeight();
        double semw = semanticWeight != null ? semanticWeight : recoveryProperties.getSemanticWeight();

        // 构建类名到索引的映射
        Map<String, Integer> indexMap = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            indexMap.put(classes.get(i).getFullyQualifiedName(), i);
        }

        // 构建邻接集合
        Map<Integer, Set<Integer>> adjacency = buildAdjacency(indexMap, relations);

        // 预加载所有embedding向量（一次性反序列化）
        double[][] embeddings = new double[n][];
        for (int i = 0; i < n; i++) {
            embeddings[i] = semanticEmbeddingService.getEmbeddingVector(classes.get(i));
        }

        int parallelThreshold = recoveryProperties.getParallelThreshold() != null ? recoveryProperties.getParallelThreshold() : 50;
        boolean useParallel = Boolean.TRUE.equals(recoveryProperties.getEnableParallelSimilarity()) && n > parallelThreshold;

        if (useParallel) {
            log.info("使用并行模式计算 {}x{} 相似度矩阵", n, n);
            buildMatrixParallel(matrix, n, sw, semw, adjacency, classes, embeddings);
        } else {
            buildMatrixSequential(matrix, n, sw, semw, adjacency, classes, embeddings);
        }

        return matrix;
    }

    /**
     * 并行计算相似度矩阵
     * 语义相似度和结构相似度分别并行计算后加权合并
     */
    private void buildMatrixParallel(double[][] matrix, int n, double sw, double semw,
                                      Map<Integer, Set<Integer>> adjacency,
                                      List<ClassInfo> classes, double[][] embeddings) {
        long start = System.currentTimeMillis();

        // 1. 并行计算语义相似度矩阵（含L2范数预计算）
        double[][] semanticMatrix = computeSemanticSimilarityMatrixParallel(embeddings, n);

        // 2. 并行计算结构相似度矩阵
        double[][] structuralMatrix = computeStructuralSimilarityMatrixParallel(n, adjacency, classes);

        // 3. 并行加权合并
        IntStream.range(0, n).parallel().forEach(i -> {
            for (int j = i + 1; j < n; j++) {
                double combined = sw * structuralMatrix[i][j] + semw * semanticMatrix[i][j];
                matrix[i][j] = combined;
                matrix[j][i] = combined;
            }
            matrix[i][i] = 0.0;
        });

        long elapsed = System.currentTimeMillis() - start;
        log.info("并行相似度矩阵计算完成: {}x{}, 耗时 {}ms", n, n, elapsed);
    }

    /**
     * 顺序计算相似度矩阵（回退模式）
     */
    private void buildMatrixSequential(double[][] matrix, int n, double sw, double semw,
                                        Map<Integer, Set<Integer>> adjacency,
                                        List<ClassInfo> classes, double[][] embeddings) {
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double structSim = calculateStructuralSim(i, j, adjacency, classes);
                double semanticSim = calculateSemanticSim(embeddings[i], embeddings[j]);
                double combined = sw * structSim + semw * semanticSim;
                matrix[i][j] = combined;
                matrix[j][i] = combined;
            }
            matrix[i][i] = 0.0;
        }
    }

    /**
     * 并行计算语义相似度矩阵
     * 优化：预计算L2范数，避免每对重复计算；按行并行化
     */
    private double[][] computeSemanticSimilarityMatrixParallel(double[][] embeddings, int n) {
        double[][] matrix = new double[n][n];

        // 检查是否有有效的embedding
        int dim = 0;
        for (double[] emb : embeddings) {
            if (emb.length > 0) {
                dim = emb.length;
                break;
            }
        }
        if (dim == 0) return matrix;

        // 预计算所有向量的L2范数
        final double[] norms = new double[n];
        final int embDim = dim;
        IntStream.range(0, n).parallel().forEach(i -> {
            if (embeddings[i].length == embDim) {
                double sum = 0;
                for (int d = 0; d < embDim; d++) {
                    sum += embeddings[i][d] * embeddings[i][d];
                }
                norms[i] = Math.sqrt(sum);
            }
        });

        // 按行并行计算余弦相似度
        IntStream.range(0, n).parallel().forEach(i -> {
            if (embeddings[i].length != embDim || norms[i] == 0) return;
            for (int j = i + 1; j < n; j++) {
                if (embeddings[j].length != embDim || norms[j] == 0) continue;
                double dot = 0;
                for (int d = 0; d < embDim; d++) {
                    dot += embeddings[i][d] * embeddings[j][d];
                }
                double sim = dot / (norms[i] * norms[j]);
                sim = Math.max(0, sim);
                matrix[i][j] = sim;
                matrix[j][i] = sim;
            }
        });

        return matrix;
    }

    /**
     * 并行计算结构相似度矩阵
     */
    private double[][] computeStructuralSimilarityMatrixParallel(int n,
                                                                   Map<Integer, Set<Integer>> adjacency,
                                                                   List<ClassInfo> classes) {
        double[][] matrix = new double[n][n];

        IntStream.range(0, n).parallel().forEach(i -> {
            for (int j = i + 1; j < n; j++) {
                double sim = calculateStructuralSim(i, j, adjacency, classes);
                matrix[i][j] = sim;
                matrix[j][i] = sim;
            }
        });

        return matrix;
    }

    /**
     * 构建邻接集合（从关系列表中提取双向依赖图）
     * 双向边确保 Jaccard 相似度对称：Jaccard(A,B) == Jaccard(B,A)
     */
    Map<Integer, Set<Integer>> buildAdjacency(Map<String, Integer> indexMap,
                                               List<ClassRelation> relations) {
        Map<Integer, Set<Integer>> adjacency = new HashMap<>();
        for (ClassRelation rel : relations) {
            Integer srcIdx = indexMap.get(rel.getSourceClassName());
            Integer tgtIdx = indexMap.get(rel.getTargetClassName());
            if (srcIdx != null && tgtIdx != null && !srcIdx.equals(tgtIdx)) {
                adjacency.computeIfAbsent(srcIdx, k -> new HashSet<>()).add(tgtIdx);
                adjacency.computeIfAbsent(tgtIdx, k -> new HashSet<>()).add(srcIdx);
            }
        }
        return adjacency;
    }

    /**
     * 计算两个类之间的结构相似度
     * 权重分配：Jaccard(0.4) + DirectDep(0.25) + PackageSim(0.35)
     * PackageSim使用渐进式包前缀相似度，而非二值同包判断
     */
    private double calculateStructuralSim(int i, int j,
                                           Map<Integer, Set<Integer>> adjacency,
                                           List<ClassInfo> classes) {
        double sim = 0.0;

        // Jaccard相似度：共享邻居 (权重0.4)
        Set<Integer> neighborsI = adjacency.getOrDefault(i, Collections.emptySet());
        Set<Integer> neighborsJ = adjacency.getOrDefault(j, Collections.emptySet());
        if (!neighborsI.isEmpty() || !neighborsJ.isEmpty()) {
            int intersectionSize = 0;
            Set<Integer> smaller = neighborsI.size() <= neighborsJ.size() ? neighborsI : neighborsJ;
            Set<Integer> larger = smaller == neighborsI ? neighborsJ : neighborsI;
            for (int idx : smaller) {
                if (larger.contains(idx)) {
                    intersectionSize++;
                }
            }
            int unionSize = neighborsI.size() + neighborsJ.size() - intersectionSize;
            if (unionSize > 0) {
                sim += 0.4 * ((double) intersectionSize / unionSize);
            }
        }

        // 直接依赖关系 (权重0.25)
        if (neighborsI.contains(j) || neighborsJ.contains(i)) {
            sim += 0.25;
        }

        // 加权包前缀相似度 (权重0.35)：深层包段权重更高，提高子包区分度
        String pkgI = classes.get(i).getPackageName();
        String pkgJ = classes.get(j).getPackageName();
        if (pkgI != null && pkgJ != null) {
            if (pkgI.equals(pkgJ)) {
                sim += 0.35;
            } else {
                String[] partsI = pkgI.split("\\.");
                String[] partsJ = pkgJ.split("\\.");
                int common = 0;
                for (int k = 0; k < Math.min(partsI.length, partsJ.length); k++) {
                    if (partsI[k].equals(partsJ[k])) common++;
                    else break;
                }
                if (common > 0) {
                    // 加权方案：第k段权重为k+1，深层匹配贡献更大
                    double weightedScore = 0;
                    double totalWeight = 0;
                    for (int k = 0; k < common; k++) {
                        double w = k + 1;
                        weightedScore += w;
                        totalWeight += w;
                    }
                    // 未匹配段的权重也计入总权重
                    for (int k = common; k < Math.max(partsI.length, partsJ.length); k++) {
                        totalWeight += k + 1;
                    }
                    sim += 0.35 * (weightedScore / totalWeight);
                }
            }
        }

        return sim;
    }

    /**
     * 计算两个embedding向量的余弦相似度（归一化到[0,1]）
     */
    private double calculateSemanticSim(double[] embI, double[] embJ) {
        double sim = SemanticEmbeddingService.cosineSimilarity(embI, embJ);
        return Math.max(0, sim);
    }

    /**
     * 上下文感知的相似度矩阵构建：用全量类构建邻接图，但只计算目标类之间的相似度。
     * 这样聚类时不会丢失与已恢复类之间的结构关系信息。
     *
     * @param targetClasses  需要聚类的目标类（未恢复类）
     * @param allClasses     全量类（包括已恢复类，用于构建完整邻接图）
     * @param relations      全量关系
     * @param structWeight   结构权重
     * @param semanticWeight 语义权重
     */
    public double[][] buildContextAwareSimilarityMatrix(List<ClassInfo> targetClasses,
                                                         List<ClassInfo> allClasses,
                                                         List<ClassRelation> relations,
                                                         Double structWeight, Double semanticWeight) {
        int targetN = targetClasses.size();
        int allN = allClasses.size();
        double[][] matrix = new double[targetN][targetN];

        double sw = structWeight != null ? structWeight : recoveryProperties.getStructWeight();
        double semw = semanticWeight != null ? semanticWeight : recoveryProperties.getSemanticWeight();

        // 用全量类构建索引和邻接图
        Map<String, Integer> allIndexMap = new HashMap<>(allN * 2);
        for (int i = 0; i < allN; i++) {
            allIndexMap.put(allClasses.get(i).getFullyQualifiedName(), i);
        }
        Map<Integer, Set<Integer>> fullAdjacency = buildAdjacency(allIndexMap, relations);

        // 构建目标类的FQN到目标索引映射
        Map<String, Integer> targetIndexMap = new HashMap<>(targetN * 2);
        int[] targetToAllIdx = new int[targetN]; // 目标索引 -> 全量索引
        for (int i = 0; i < targetN; i++) {
            String fqn = targetClasses.get(i).getFullyQualifiedName();
            targetIndexMap.put(fqn, i);
            Integer allIdx = allIndexMap.get(fqn);
            targetToAllIdx[i] = allIdx != null ? allIdx : -1;
        }

        // 预加载目标类的embedding
        double[][] embeddings = new double[targetN][];
        for (int i = 0; i < targetN; i++) {
            embeddings[i] = semanticEmbeddingService.getEmbeddingVector(targetClasses.get(i));
        }

        int parallelThreshold = recoveryProperties.getParallelThreshold() != null ? recoveryProperties.getParallelThreshold() : 50;
        boolean useParallel = Boolean.TRUE.equals(recoveryProperties.getEnableParallelSimilarity()) && targetN > parallelThreshold;

        if (useParallel) {
            log.info("使用并行模式计算上下文感知相似度矩阵: 目标{}x{}, 全量邻接图{}个节点", targetN, targetN, allN);

            // 并行计算语义相似度
            double[][] semanticMatrix = computeSemanticSimilarityMatrixParallel(embeddings, targetN);

            // 并行计算结构相似度（使用全量邻接图）
            double[][] structuralMatrix = new double[targetN][targetN];
            IntStream.range(0, targetN).parallel().forEach(i -> {
                int allI = targetToAllIdx[i];
                for (int j = i + 1; j < targetN; j++) {
                    int allJ = targetToAllIdx[j];
                    double sim = calculateStructuralSimWithFullAdjacency(allI, allJ, fullAdjacency, allClasses);
                    structuralMatrix[i][j] = sim;
                    structuralMatrix[j][i] = sim;
                }
            });

            // 加权合并
            IntStream.range(0, targetN).parallel().forEach(i -> {
                for (int j = i + 1; j < targetN; j++) {
                    double combined = sw * structuralMatrix[i][j] + semw * semanticMatrix[i][j];
                    matrix[i][j] = combined;
                    matrix[j][i] = combined;
                }
                matrix[i][i] = 0.0;
            });
        } else {
            for (int i = 0; i < targetN; i++) {
                int allI = targetToAllIdx[i];
                for (int j = i + 1; j < targetN; j++) {
                    int allJ = targetToAllIdx[j];
                    double structSim = calculateStructuralSimWithFullAdjacency(allI, allJ, fullAdjacency, allClasses);
                    double semanticSim = calculateSemanticSim(embeddings[i], embeddings[j]);
                    double combined = sw * structSim + semw * semanticSim;
                    matrix[i][j] = combined;
                    matrix[j][i] = combined;
                }
                matrix[i][i] = 0.0;
            }
        }

        return matrix;
    }

    /**
     * 使用全量邻接图计算两个类的结构相似度
     * 索引为全量类列表中的索引
     */
    private double calculateStructuralSimWithFullAdjacency(int allI, int allJ,
                                                            Map<Integer, Set<Integer>> fullAdjacency,
                                                            List<ClassInfo> allClasses) {
        if (allI < 0 || allJ < 0) return 0.0;

        double sim = 0.0;

        // Jaccard相似度：共享邻居 (权重0.4)
        Set<Integer> neighborsI = fullAdjacency.getOrDefault(allI, Collections.emptySet());
        Set<Integer> neighborsJ = fullAdjacency.getOrDefault(allJ, Collections.emptySet());
        if (!neighborsI.isEmpty() || !neighborsJ.isEmpty()) {
            int intersectionSize = 0;
            Set<Integer> smaller = neighborsI.size() <= neighborsJ.size() ? neighborsI : neighborsJ;
            Set<Integer> larger = smaller == neighborsI ? neighborsJ : neighborsI;
            for (int idx : smaller) {
                if (larger.contains(idx)) {
                    intersectionSize++;
                }
            }
            int unionSize = neighborsI.size() + neighborsJ.size() - intersectionSize;
            if (unionSize > 0) {
                sim += 0.4 * ((double) intersectionSize / unionSize);
            }
        }

        // 直接依赖关系 (权重0.25)
        if (neighborsI.contains(allJ) || neighborsJ.contains(allI)) {
            sim += 0.25;
        }

        // 加权包前缀相似度 (权重0.35)
        String pkgI = allClasses.get(allI).getPackageName();
        String pkgJ = allClasses.get(allJ).getPackageName();
        if (pkgI != null && pkgJ != null) {
            if (pkgI.equals(pkgJ)) {
                sim += 0.35;
            } else {
                String[] partsI = pkgI.split("\\.");
                String[] partsJ = pkgJ.split("\\.");
                int common = 0;
                for (int k = 0; k < Math.min(partsI.length, partsJ.length); k++) {
                    if (partsI[k].equals(partsJ[k])) common++;
                    else break;
                }
                if (common > 0) {
                    double weightedScore = 0;
                    double totalWeight = 0;
                    for (int k = 0; k < common; k++) {
                        double w = k + 1;
                        weightedScore += w;
                        totalWeight += w;
                    }
                    for (int k = common; k < Math.max(partsI.length, partsJ.length); k++) {
                        totalWeight += k + 1;
                    }
                    sim += 0.35 * (weightedScore / totalWeight);
                }
            }
        }

        return sim;
    }
}
