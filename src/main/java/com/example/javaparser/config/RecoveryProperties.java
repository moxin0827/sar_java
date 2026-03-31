package com.example.javaparser.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "recovery")
public class RecoveryProperties {
    private Double structWeight = 0.45;
    private Double semanticWeight = 0.55;
    private Double threshold = 0.5;
    private Integer maxIterations = 200;
    private Double damping = 0.9;

    /** 并行embedding批次数（默认6） */
    private Integer embeddingConcurrentBatches = 6;

    /** embedding批次间延迟毫秒（默认50ms，降低自200ms） */
    private Integer embeddingDelayMs = 50;

    /** 是否启用并行embedding生成 */
    private Boolean enableParallelEmbedding = true;

    /** 是否启用并行相似度矩阵计算 */
    private Boolean enableParallelSimilarity = true;

    /** 是否启用并行AP聚类 */
    private Boolean enableParallelClustering = true;

    /** FSK采样上限（默认80，超大项目可提升到100-120） */
    private Integer fskMaxSamples = 80;

    /** 并行计算阈值（类数量超过此值启用并行，默认50） */
    private Integer parallelThreshold = 50;

    // === 聚类算法选择 ===
    /** 默认聚类算法: AFFINITY_PROPAGATION, AGGLOMERATIVE, DBSCAN, SPECTRAL, KMEANS_SILHOUETTE */
    private String clusteringAlgorithm = "AFFINITY_PROPAGATION";

    // === DBSCAN参数 ===
    /** DBSCAN邻域半径（null=自适应k-distance估计，>0=固定值） */
    private Double dbscanEps = null;
    /** DBSCAN最小邻域点数 */
    private Integer dbscanMinPts = 2;

    // === Spectral参数 ===
    /** Spectral聚类数（0=自动检测） */
    private Integer spectralNumClusters = 0;

    // === KMeans参数 ===
    /** KMeans搜索K的下限 */
    private Integer kmeansKMin = 2;
    /** KMeans搜索K的上限（0=auto=sqrt(n)） */
    private Integer kmeansKMax = 0;
    /** KMeans单次最大迭代 */
    private Integer kmeansMaxIter = 100;

    // === Agglomerative参数 ===
    /** 链接准则: SINGLE, COMPLETE, AVERAGE, WARD */
    private String agglomerativeLinkage = "WARD";

    // === 组件合并后处理 ===
    /** 是否启用小组件合并后处理（默认true） */
    private Boolean enableMergeSmallComponents = true;

    /** 最小组件类数量，低于此值的组件将被合并到最相似的大组件（默认2） */
    private Integer minComponentSize = 2;

    /** 合并时的最低相似度阈值，低于此值不合并而是归入Misc组件（默认0.1） */
    private Double mergeSimThreshold = 0.1;

    /** AP偏好百分位数（0.0=最小值产生最少聚类，0.5=中位数，默认0.1） */
    private Double apPreferencePercentile = 0.1;

    /** 目标组件数量（0=自动计算sqrt(N)，>0=固定目标） */
    private Integer targetComponentCount = 0;
}

