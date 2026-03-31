package com.example.javaparser.service.recovery.clustering;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 聚类算法工厂 - 根据类型返回对应算法实现
 */
@Slf4j
@Service
public class ClusteringAlgorithmFactory {

    @Autowired
    private AffinityPropagationAlgorithm affinityPropagation;

    @Autowired
    private AgglomerativeAlgorithm agglomerative;

    @Autowired
    private DbscanAlgorithm dbscan;

    @Autowired
    private SpectralAlgorithm spectral;

    @Autowired
    private KMeansSilhouetteAlgorithm kmeansSilhouette;

    /**
     * 根据算法类型获取算法实例
     */
    public ClusteringAlgorithm getAlgorithm(ClusteringAlgorithmType type) {
        if (type == null) {
            log.warn("算法类型为null，使用默认AP算法");
            return affinityPropagation;
        }

        switch (type) {
            case AFFINITY_PROPAGATION:
                return affinityPropagation;
            case AGGLOMERATIVE:
                return agglomerative;
            case DBSCAN:
                return dbscan;
            case SPECTRAL:
                return spectral;
            case KMEANS_SILHOUETTE:
                return kmeansSilhouette;
            default:
                log.warn("未知算法类型: {}, 使用默认AP算法", type);
                return affinityPropagation;
        }
    }
}
