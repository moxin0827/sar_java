package com.example.javaparser.service.recovery.clustering;

/**
 * 支持的聚类算法枚举
 */
public enum ClusteringAlgorithmType {
    /** Affinity Propagation（默认） */
    AFFINITY_PROPAGATION,
    /** 层次聚类（凝聚式） */
    AGGLOMERATIVE,
    /** 基于密度的聚类 */
    DBSCAN,
    /** 谱聚类 */
    SPECTRAL,
    /** K-Means + Silhouette 自动选K */
    KMEANS_SILHOUETTE
}
