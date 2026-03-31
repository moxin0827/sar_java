package com.example.javaparser.service.evaluation;

import com.example.javaparser.config.RecoveryProperties;
import com.example.javaparser.dto.EvaluationResultDTO;
import com.example.javaparser.dto.RecoveryRequest;
import com.example.javaparser.dto.RecoveryResultDTO;
import com.example.javaparser.entity.ClassRelation;
import com.example.javaparser.entity.RecoveredComponent;
import com.example.javaparser.repository.ClassRelationRepository;
import com.example.javaparser.repository.RecoveredComponentRepository;
import com.example.javaparser.repository.RecoveryResultRepository;
import com.example.javaparser.service.recovery.ArchitectureRecoveryOrchestrator;
import com.example.javaparser.service.recovery.clustering.ClusteringAlgorithmType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 评价服务 - 对架构恢复结果进行多维度评价
 */
@Slf4j
@Service
public class EvaluationService {

    @Autowired
    private GroundTruthLoader groundTruthLoader;

    @Autowired
    private MoJoFMCalculator moJoFMCalculator;

    @Autowired
    private ClusteringMetrics clusteringMetrics;

    @Autowired
    private RecoveredComponentRepository recoveredComponentRepository;

    @Autowired
    private RecoveryResultRepository recoveryResultRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    @Autowired
    private ArchitectureRecoveryOrchestrator orchestrator;

    @Autowired
    private RecoveryProperties recoveryProperties;

    /**
     * 评价已有恢复结果
     *
     * @param projectId       项目ID
     * @param groundTruthFile ground truth 文件名
     * @return 评价结果
     */
    public EvaluationResultDTO evaluate(Long projectId, String groundTruthFile) {
        // 加载 Ground Truth
        Map<String, List<String>> groundTruth = groundTruthLoader.loadGroundTruth(groundTruthFile);

        // 获取最新恢复结果
        var latestResult = recoveryResultRepository
                .findTopByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new RuntimeException("未找到恢复结果，请先执行架构恢复"));

        List<RecoveredComponent> components =
                recoveredComponentRepository.findByRecoveryResultId(latestResult.getId());

        // 转换为划分格式
        Map<String, List<String>> recovered = toPartition(components);

        // 构建组件 source 映射
        Map<String, String> componentSources = new HashMap<>();
        for (RecoveredComponent comp : components) {
            if (comp.getName() != null && comp.getSource() != null) {
                componentSources.put(comp.getName(), comp.getSource());
            }
        }

        // 获取依赖关系（用于 TurboMQ）
        List<ClassRelation> relations = classRelationRepository.findByProjectId(projectId);

        // 计算指标
        return computeMetrics(recovered, groundTruth, relations,
                latestResult.getClusteringAlgorithm(),
                latestResult.getProcessingTimeMs(),
                componentSources);
    }

    /**
     * 多算法对比评价（共享前置步骤，每个算法独立评测）
     */
    public EvaluationResultDTO.CompareResultDTO compare(Long projectId,
                                                         String groundTruthFile,
                                                         List<String> algorithms,
                                                         String outputDir) {
        Map<String, List<String>> groundTruth = groundTruthLoader.loadGroundTruth(groundTruthFile);
        List<ClassRelation> relations = classRelationRepository.findByProjectId(projectId);

        // 使用多算法恢复：前置步骤只执行一次
        RecoveryRequest request = new RecoveryRequest();
        request.setUseFskRecovery(true);
        request.setUseClusteringRecovery(true);
        request.setStructWeight(recoveryProperties.getStructWeight());
        request.setSemanticWeight(recoveryProperties.getSemanticWeight());

        Map<String, RecoveryResultDTO> recoveryResults =
                orchestrator.recoverMultiAlgorithm(projectId, request, outputDir, algorithms);

        // 对每个算法的结果独立评测
        List<EvaluationResultDTO> results = new ArrayList<>();
        for (String algoName : algorithms) {
            RecoveryResultDTO recoveryResult = recoveryResults.get(algoName);
            if (recoveryResult == null) {
                log.warn("算法 {} 无恢复结果，跳过评测", algoName);
                EvaluationResultDTO failResult = new EvaluationResultDTO();
                failResult.setAlgorithm(algoName);
                failResult.setMojoFM(0.0);
                failResult.setPrecision(0.0);
                failResult.setRecall(0.0);
                failResult.setF1(0.0);
                failResult.setTurboMQ(0.0);
                results.add(failResult);
                continue;
            }

            Map<String, List<String>> recovered = new LinkedHashMap<>();
            Map<String, String> componentSources = new HashMap<>();
            for (RecoveryResultDTO.ComponentDTO comp : recoveryResult.getComponents()) {
                recovered.put(comp.getName(), comp.getClassNames());
                if (comp.getSource() != null) {
                    componentSources.put(comp.getName(), comp.getSource());
                }
            }

            EvaluationResultDTO evalResult = computeMetrics(
                    recovered, groundTruth, relations, algoName,
                    recoveryResult.getProcessingTimeMs(),
                    componentSources);
            results.add(evalResult);
        }

        EvaluationResultDTO.CompareResultDTO compareResult = new EvaluationResultDTO.CompareResultDTO();
        compareResult.setGroundTruthFile(groundTruthFile);
        compareResult.setResults(results);

        int totalClasses = groundTruth.values().stream().mapToInt(List::size).sum();
        compareResult.setTotalClasses(totalClasses);

        return compareResult;
    }

    /**
     * 计算所有评价指标
     * 优化：在计算前先过滤恢复结果，只保留 ground truth 中存在的类
     * 这样避免解析出的大量非核心类（测试类等）稀释 precision
     *
     * @param componentSources 组件名 -> source（PACKAGE/FSK/CLUSTERING），可为null
     */
    private EvaluationResultDTO computeMetrics(Map<String, List<String>> recovered,
                                                Map<String, List<String>> groundTruth,
                                                List<ClassRelation> relations,
                                                String algorithm, Long timeMs,
                                                Map<String, String> componentSources) {
        EvaluationResultDTO result = new EvaluationResultDTO();
        result.setAlgorithm(algorithm);
        result.setTimeMs(timeMs);
        result.setComponentCount(recovered.size());
        result.setGroundTruthComponentCount(groundTruth.size());

        int totalClasses = recovered.values().stream().mapToInt(List::size).sum();
        result.setTotalClasses(totalClasses);

        // 构建 ground truth 类集合
        Set<String> gtClasses = new HashSet<>();
        for (List<String> classes : groundTruth.values()) {
            gtClasses.addAll(classes);
        }

        // 过滤恢复结果：只保留 ground truth 中存在的类
        // 这样评估的是"对于 ground truth 关心的类，恢复结果的分组质量如何"
        Map<String, List<String>> filteredRecovered = filterToGroundTruthClasses(recovered, gtClasses);

        int filteredClasses = filteredRecovered.values().stream().mapToInt(List::size).sum();
        log.info("评估过滤: 恢复结果 {} 个类 -> {} 个 ground truth 类 (ground truth共 {} 个类)",
                totalClasses, filteredClasses, gtClasses.size());

        // 优化5: 计算 Phase0 覆盖度（PACKAGE source 组件中 GT 类的占比）
        double phase0Coverage = 0.0;
        if (componentSources != null && filteredClasses > 0) {
            int phase0GtClasses = 0;
            for (Map.Entry<String, List<String>> entry : filteredRecovered.entrySet()) {
                if ("PACKAGE".equals(componentSources.get(entry.getKey()))) {
                    phase0GtClasses += entry.getValue().size();
                }
            }
            phase0Coverage = (double) phase0GtClasses / filteredClasses;
            result.setPhase0Coverage(phase0Coverage);
            if (phase0Coverage > 0.9) {
                log.info("[{}] phase0Coverage={}, 评估主要反映包结构质量而非聚类算法质量",
                        algorithm, String.format("%.2f", phase0Coverage));
            }
        }

        // MoJoFM（使用过滤后的结果）
        double mojoFM = moJoFMCalculator.calculate(filteredRecovered, groundTruth);
        result.setMojoFM(mojoFM);

        // Precision / Recall / F1（使用过滤后的结果）
        double[] prf1 = clusteringMetrics.calculatePrecisionRecallF1(filteredRecovered, groundTruth);
        result.setPrecision(prf1[0]);
        result.setRecall(prf1[1]);
        result.setF1(prf1[2]);

        // TurboMQ（使用原始结果，因为 TurboMQ 衡量的是模块化质量，需要完整依赖图）
        double turboMQ = clusteringMetrics.calculateTurboMQ(recovered, relations);
        result.setTurboMQ(turboMQ);

        log.info("评价完成 [{}]: MoJoFM={}, F1={}, TurboMQ={}, phase0Coverage={}",
                algorithm,
                String.format("%.2f", mojoFM),
                String.format("%.4f", prf1[2]),
                String.format("%.4f", turboMQ),
                String.format("%.2f", phase0Coverage));

        return result;
    }

    /**
     * 过滤恢复结果，只保留 ground truth 中存在的类。
     * 移除空组件，确保评估只关注 ground truth 关心的类的分组质量。
     */
    private Map<String, List<String>> filterToGroundTruthClasses(
            Map<String, List<String>> recovered, Set<String> gtClasses) {
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : recovered.entrySet()) {
            List<String> filteredClasses = new ArrayList<>();
            for (String cls : entry.getValue()) {
                if (gtClasses.contains(cls)) {
                    filteredClasses.add(cls);
                }
            }
            if (!filteredClasses.isEmpty()) {
                filtered.put(entry.getKey(), filteredClasses);
            }
        }
        return filtered;
    }

    /**
     * 将 RecoveredComponent 列表转换为划分格式
     */
    private Map<String, List<String>> toPartition(List<RecoveredComponent> components) {
        Map<String, List<String>> partition = new LinkedHashMap<>();
        for (RecoveredComponent comp : components) {
            if (comp.getClassNames() != null && !comp.getClassNames().isEmpty()) {
                String[] classes = comp.getClassNames().split(",");
                List<String> classList = new ArrayList<>();
                for (String cls : classes) {
                    String trimmed = cls.trim();
                    if (!trimmed.isEmpty()) classList.add(trimmed);
                }
                if (!classList.isEmpty()) {
                    partition.put(comp.getName(), classList);
                }
            }
        }
        return partition;
    }
}
