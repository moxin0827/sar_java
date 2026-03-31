package com.example.javaparser.service.recovery.clustering;

import com.example.javaparser.service.recovery.AffinityPropagation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Affinity Propagation 适配器 - 委托现有实现
 */
@Slf4j
@Service
public class AffinityPropagationAlgorithm implements ClusteringAlgorithm {

    @Autowired
    private AffinityPropagation affinityPropagation;

    @Override
    public String getName() {
        return "AFFINITY_PROPAGATION";
    }

    @Override
    public Map<Integer, List<Integer>> cluster(double[][] similarity, ClusteringParams params) {
        double damping = params.getDamping() != null ? params.getDamping() : 0.9;
        int maxIter = params.getMaxIterations() != null ? params.getMaxIterations() : 200;
        double preference = params.getPreference() != null ?
                params.getPreference() : AffinityPropagation.medianPreference(similarity);

        log.info("AP聚类: damping={}, maxIter={}, preference={}", damping, maxIter, String.format("%.4f", preference));
        return affinityPropagation.cluster(similarity, damping, maxIter, preference);
    }
}
