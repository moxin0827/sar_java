package com.example.javaparser.service.recovery.clustering;

import lombok.Builder;
import lombok.Data;

/**
 * 聚类参数统一封装 - 各算法按需取用
 */
@Data
@Builder
public class ClusteringParams {

    // === AP 参数 ===
    /** AP阻尼因子 (default 0.9) */
    private Double damping;
    /** 最大迭代次数 (default 200) */
    private Integer maxIterations;
    /** AP偏好值（null则自动计算中位数） */
    private Double preference;

    // === 通用参数 ===
    /** 相似度阈值（Agglomerative切割阈值） */
    private Double threshold;
    /** 是否启用并行 */
    private Boolean enableParallel;
    /** 并行启用的最小N */
    private Integer parallelThreshold;

    // === DBSCAN 参数 ===
    /** DBSCAN邻域半径（距离空间，0.5表示相似度>=0.5的点为邻居） */
    private Double eps;
    /** DBSCAN最小邻域点数 */
    private Integer minPts;

    // === Spectral 参数 ===
    /** 聚类数（0=自动检测） */
    private Integer numClusters;

    // === KMeans 参数 ===
    /** KMeans搜索K的下限 */
    private Integer kMin;
    /** KMeans搜索K的上限（0=auto=sqrt(n)） */
    private Integer kMax;
    /** KMeans单次最大迭代 */
    private Integer kmeansMaxIter;

    // === Agglomerative 参数 ===
    /** 链接准则: SINGLE, COMPLETE, AVERAGE */
    private String linkage;
}
