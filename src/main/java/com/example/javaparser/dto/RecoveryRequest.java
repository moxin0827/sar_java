package com.example.javaparser.dto;

import lombok.Data;

@Data
public class RecoveryRequest {
    private Double structWeight;
    private Double semanticWeight;
    private Double threshold;
    private Integer maxHierarchyLevels;
    private Boolean useFskRecovery = true;
    private Boolean useClusteringRecovery = true;

    /** 聚类算法选择（覆盖配置文件默认值） */
    private String clusteringAlgorithm;

    /** DBSCAN eps（覆盖默认值） */
    private Double dbscanEps;
    /** DBSCAN minPts */
    private Integer dbscanMinPts;

    /** Spectral/KMeans聚类数（0=auto） */
    private Integer numClusters;

    /** KMeans K搜索范围 */
    private Integer kmeansKMin;
    private Integer kmeansKMax;

    /** Agglomerative链接准则: SINGLE, COMPLETE, AVERAGE */
    private String linkage;
}
