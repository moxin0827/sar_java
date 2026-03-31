package com.example.javaparser.service.recovery.clustering;

import java.util.List;
import java.util.Map;

/**
 * 聚类算法统一接口 - 策略模式
 * 所有聚类算法接受相似度矩阵，返回聚类结果
 */
public interface ClusteringAlgorithm {

    /**
     * 算法名称标识
     */
    String getName();

    /**
     * 执行聚类
     *
     * @param similarity n×n 相似度矩阵（值域 [0,1]，对角线为0）
     * @param params     算法特定参数
     * @return 聚类结果: cluster ID -> member index list
     */
    Map<Integer, List<Integer>> cluster(double[][] similarity, ClusteringParams params);
}
