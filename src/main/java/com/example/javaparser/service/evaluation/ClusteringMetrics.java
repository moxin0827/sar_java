package com.example.javaparser.service.evaluation;

import com.example.javaparser.entity.ClassRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 聚类评价指标计算器
 *
 * 包含：Precision / Recall / F1、TurboMQ
 */
@Slf4j
@Service
public class ClusteringMetrics {

    /**
     * 计算 Precision / Recall / F1
     *
     * 基于类对（class pair）的方式：
     * - 如果两个类在恢复结果和 Ground Truth 中都属于同一组件 → TP
     * - 如果两个类在恢复结果中属于同一组件但 Ground Truth 中不是 → FP
     * - 如果两个类在 Ground Truth 中属于同一组件但恢复结果中不是 → FN
     *
     * @param recovered  恢复结果划分
     * @param groundTruth Ground Truth 划分
     * @return [precision, recall, f1]
     */
    public double[] calculatePrecisionRecallF1(Map<String, List<String>> recovered,
                                                Map<String, List<String>> groundTruth) {
        // 取共同实体
        Set<String> allRecovered = new HashSet<>();
        for (List<String> classes : recovered.values()) allRecovered.addAll(classes);
        Set<String> allGT = new HashSet<>();
        for (List<String> classes : groundTruth.values()) allGT.addAll(classes);

        Set<String> common = new HashSet<>(allRecovered);
        common.retainAll(allGT);

        if (common.size() < 2) {
            return new double[]{0, 0, 0};
        }

        // 构建实体到聚类的映射
        Map<String, String> entityToRecovered = buildEntityMap(recovered, common);
        Map<String, String> entityToGT = buildEntityMap(groundTruth, common);

        List<String> entities = new ArrayList<>(common);
        int tp = 0, fp = 0, fn = 0;

        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                String ei = entities.get(i);
                String ej = entities.get(j);

                boolean sameInRecovered = Objects.equals(
                        entityToRecovered.get(ei), entityToRecovered.get(ej));
                boolean sameInGT = Objects.equals(
                        entityToGT.get(ei), entityToGT.get(ej));

                if (sameInRecovered && sameInGT) tp++;
                else if (sameInRecovered && !sameInGT) fp++;
                else if (!sameInRecovered && sameInGT) fn++;
            }
        }

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1 = (precision + recall) > 0 ?
                2 * precision * recall / (precision + recall) : 0;

        log.info("P/R/F1: precision={}, recall={}, f1={} (TP={}, FP={}, FN={})",
                String.format("%.4f", precision),
                String.format("%.4f", recall),
                String.format("%.4f", f1),
                tp, fp, fn);

        return new double[]{precision, recall, f1};
    }

    /**
     * 计算 TurboMQ（Turbo Modularization Quality）
     *
     * TurboMQ = Σ CF_i
     * CF_i = 2 * μ_i / (2 * μ_i + Σ_{j≠i} (ε_{i,j} + ε_{j,i}))
     *
     * μ_i = 组件 i 内部依赖数
     * ε_{i,j} = 组件 i 到组件 j 的外部依赖数
     *
     * @param partition 组件划分
     * @param relations 类间依赖关系
     * @return TurboMQ 值
     */
    public double calculateTurboMQ(Map<String, List<String>> partition,
                                    List<ClassRelation> relations) {
        if (partition.isEmpty()) return 0;

        // 构建类到组件的映射
        Map<String, String> classToComponent = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : partition.entrySet()) {
            for (String cls : entry.getValue()) {
                classToComponent.put(cls, entry.getKey());
            }
        }

        // 统计内部依赖和外部依赖
        Map<String, Integer> internalDeps = new HashMap<>(); // 组件 -> 内部依赖数
        Map<String, Map<String, Integer>> externalDeps = new HashMap<>(); // 组件i -> (组件j -> 依赖数)

        for (String compName : partition.keySet()) {
            internalDeps.put(compName, 0);
            externalDeps.put(compName, new HashMap<>());
        }

        for (ClassRelation rel : relations) {
            String srcComp = classToComponent.get(rel.getSourceClassName());
            String tgtComp = classToComponent.get(rel.getTargetClassName());

            if (srcComp == null || tgtComp == null) continue;

            if (srcComp.equals(tgtComp)) {
                internalDeps.merge(srcComp, 1, Integer::sum);
            } else {
                externalDeps.get(srcComp).merge(tgtComp, 1, Integer::sum);
            }
        }

        // 计算 TurboMQ
        double turboMQ = 0;
        for (String comp : partition.keySet()) {
            int mu = internalDeps.getOrDefault(comp, 0);

            int externalSum = 0;
            // ε_{i,j} + ε_{j,i}
            for (String other : partition.keySet()) {
                if (other.equals(comp)) continue;
                externalSum += externalDeps.getOrDefault(comp, Collections.emptyMap())
                        .getOrDefault(other, 0);
                externalSum += externalDeps.getOrDefault(other, Collections.emptyMap())
                        .getOrDefault(comp, 0);
            }

            double denominator = 2.0 * mu + externalSum;
            if (denominator > 0) {
                double cf = (2.0 * mu) / denominator;
                turboMQ += cf;
            }
        }

        log.info("TurboMQ: {}", String.format("%.4f", turboMQ));
        return turboMQ;
    }

    private Map<String, String> buildEntityMap(Map<String, List<String>> partition,
                                                Set<String> entities) {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : partition.entrySet()) {
            for (String cls : entry.getValue()) {
                if (entities.contains(cls)) {
                    map.put(cls, entry.getKey());
                }
            }
        }
        return map;
    }
}
