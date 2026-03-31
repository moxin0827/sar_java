package com.example.javaparser.service.recovery;

import com.example.javaparser.config.RecoveryProperties;
import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.ClassRelation;
import com.example.javaparser.entity.RecoveredComponent;
import com.example.javaparser.repository.ClassInfoRepository;
import com.example.javaparser.repository.ClassRelationRepository;
import com.example.javaparser.service.recovery.clustering.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class Phase2ClusteringRecovery {

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    @Autowired
    private SimilarityCalculator similarityCalculator;

    @Autowired
    private AffinityPropagation affinityPropagation;

    @Autowired
    private ClusteringAlgorithmFactory algorithmFactory;

    @Autowired
    private RecoveryProperties recoveryProperties;

    /**
     * 对未恢复的类执行聚类（优化版：接受预加载数据，避免重复DB查询）
     *
     * @param projectId            项目ID
     * @param recoveryResultId     恢复结果ID
     * @param unrecoveredClassNames 未恢复的类名列表
     * @param structWeight         结构权重
     * @param semanticWeight       语义权重
     * @param preloadedClasses     预加载的ClassInfo列表（可为null）
     * @param preloadedRelations   预加载的ClassRelation列表（可为null）
     * @param algorithmType        聚类算法类型
     * @param request              恢复请求（包含算法参数）
     */
    public List<RecoveredComponent> recover(Long projectId, Long recoveryResultId,
                                             List<String> unrecoveredClassNames,
                                             Double structWeight, Double semanticWeight,
                                             List<ClassInfo> preloadedClasses,
                                             List<ClassRelation> preloadedRelations,
                                             ClusteringAlgorithmType algorithmType,
                                             com.example.javaparser.dto.RecoveryRequest request) {
        log.info("Phase2: {}聚类恢复开始, {} 个未恢复类",
                algorithmType != null ? algorithmType : "AP", unrecoveredClassNames.size());

        if (unrecoveredClassNames.isEmpty()) {
            return Collections.emptyList();
        }

        // 大型项目优化：当未恢复类数量过大时（>500），先按包前缀分块聚类再合并
        // 避免 O(n²) 相似度矩阵和 O(n³) 聚类算法的性能瓶颈
        int BLOCK_THRESHOLD = 500;
        if (unrecoveredClassNames.size() > BLOCK_THRESHOLD) {
            log.info("Phase2大型项目分块模式: {} 个类超过阈值 {}", unrecoveredClassNames.size(), BLOCK_THRESHOLD);
            return recoverByBlocks(projectId, recoveryResultId, unrecoveredClassNames,
                    structWeight, semanticWeight, preloadedClasses, preloadedRelations,
                    algorithmType, request);
        }

        // 优化：使用预加载数据，避免重复查询
        List<ClassInfo> allClasses = preloadedClasses != null ?
                preloadedClasses : classInfoRepository.findByProjectId(projectId);
        Set<String> unrecoveredSet = new HashSet<>(unrecoveredClassNames);
        List<ClassInfo> targetClasses = allClasses.stream()
                .filter(c -> unrecoveredSet.contains(c.getFullyQualifiedName()))
                .collect(Collectors.toList());

        if (targetClasses.size() < 2) {
            // 不足以聚类，创建单个组件
            if (targetClasses.size() == 1) {
                RecoveredComponent comp = new RecoveredComponent();
                comp.setProjectId(projectId);
                comp.setRecoveryResultId(recoveryResultId);
                comp.setName("Misc_" + targetClasses.get(0).getSimpleName());
                comp.setLevel(1);
                comp.setClassNames(targetClasses.get(0).getFullyQualifiedName());
                comp.setSource("CLUSTERING");
                return Collections.singletonList(comp);
            }
            return Collections.emptyList();
        }

        // 优化：使用预加载关系数据
        List<ClassRelation> relations = preloadedRelations != null ?
                preloadedRelations : classRelationRepository.findByProjectId(projectId);

        // 构建上下文感知相似度矩阵：用全量类构建邻接图，只计算目标类子矩阵
        // 这样聚类时不会丢失与已恢复类之间的结构关系信息
        double[][] similarity = similarityCalculator.buildContextAwareSimilarityMatrix(
                targetClasses, allClasses, relations, structWeight, semanticWeight);

        // 构建聚类参数
        ClusteringParams params = buildClusteringParams(request, similarity);

        // 获取算法实例并执行聚类
        ClusteringAlgorithm algorithm = algorithmFactory.getAlgorithm(
                algorithmType != null ? algorithmType : ClusteringAlgorithmType.AFFINITY_PROPAGATION);
        Map<Integer, List<Integer>> clusters = algorithm.cluster(similarity, params);

        // 转换为组件
        List<RecoveredComponent> components = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : clusters.entrySet()) {
            List<Integer> memberIndices = entry.getValue();
            List<String> classNames = memberIndices.stream()
                    .map(i -> targetClasses.get(i).getFullyQualifiedName())
                    .collect(Collectors.toList());

            // 用第一个成员的类名作为初始组件名（兼容所有算法）
            int representativeIdx = memberIndices.get(0);
            // AP 算法的 key 是 exemplar index，优先使用
            if (entry.getKey() >= 0 && entry.getKey() < targetClasses.size()) {
                representativeIdx = entry.getKey();
            }
            String exemplarName = targetClasses.get(representativeIdx).getSimpleName();

            RecoveredComponent comp = new RecoveredComponent();
            comp.setProjectId(projectId);
            comp.setRecoveryResultId(recoveryResultId);
            comp.setName("Cluster_" + exemplarName);
            comp.setLevel(1);
            comp.setClassNames(String.join(",", classNames));
            comp.setSource("CLUSTERING");

            components.add(comp);
        }

        log.info("Phase2完成: {} 个聚类组件", components.size());
        return components;
    }

    /**
     * 兼容旧接口（不传预加载数据，使用默认AP算法）
     */
    public List<RecoveredComponent> recover(Long projectId, Long recoveryResultId,
                                             List<String> unrecoveredClassNames,
                                             Double structWeight, Double semanticWeight) {
        return recover(projectId, recoveryResultId, unrecoveredClassNames,
                structWeight, semanticWeight, null, null,
                ClusteringAlgorithmType.AFFINITY_PROPAGATION, null);
    }

    /**
     * 构建聚类参数
     */
    private ClusteringParams buildClusteringParams(com.example.javaparser.dto.RecoveryRequest request,
                                                    double[][] similarity) {
        ClusteringParams.ClusteringParamsBuilder builder = ClusteringParams.builder();

        // AP 参数
        builder.damping(recoveryProperties.getDamping());
        builder.maxIterations(recoveryProperties.getMaxIterations());
        double apPercentile = recoveryProperties.getApPreferencePercentile() != null ?
                recoveryProperties.getApPreferencePercentile() : 0.0;
        builder.preference(AffinityPropagation.percentilePreference(similarity, apPercentile));

        // 通用参数
        builder.threshold(request != null && request.getThreshold() != null ?
                request.getThreshold() : recoveryProperties.getThreshold());
        builder.enableParallel(recoveryProperties.getEnableParallelClustering());
        builder.parallelThreshold(recoveryProperties.getParallelThreshold());

        // DBSCAN 参数
        builder.eps(request != null && request.getDbscanEps() != null ?
                request.getDbscanEps() : recoveryProperties.getDbscanEps());
        builder.minPts(request != null && request.getDbscanMinPts() != null ?
                request.getDbscanMinPts() : recoveryProperties.getDbscanMinPts());

        // Spectral/KMeans 参数
        builder.numClusters(request != null && request.getNumClusters() != null ?
                request.getNumClusters() : recoveryProperties.getSpectralNumClusters());
        builder.kMin(recoveryProperties.getKmeansKMin());
        builder.kMax(recoveryProperties.getKmeansKMax());
        builder.kmeansMaxIter(recoveryProperties.getKmeansMaxIter());

        // Agglomerative 参数
        builder.linkage(request != null && request.getLinkage() != null ?
                request.getLinkage() : recoveryProperties.getAgglomerativeLinkage());

        return builder.build();
    }

    /**
     * 大型项目分块聚类：按包前缀将类分成多个块，每块独立聚类，最后合并结果。
     * 避免对 >500 类构建 O(n²) 相似度矩阵和 O(n³) 聚类的性能瓶颈。
     *
     * 策略：
     * 1. 按3级包前缀分组
     * 2. 每个分组独立执行聚类（矩阵规模大幅缩小）
     * 3. 过小的分组（<3类）直接作为单独组件
     */
    private List<RecoveredComponent> recoverByBlocks(Long projectId, Long recoveryResultId,
                                                      List<String> unrecoveredClassNames,
                                                      Double structWeight, Double semanticWeight,
                                                      List<ClassInfo> preloadedClasses,
                                                      List<ClassRelation> preloadedRelations,
                                                      ClusteringAlgorithmType algorithmType,
                                                      com.example.javaparser.dto.RecoveryRequest request) {
        List<ClassInfo> allClasses = preloadedClasses != null ?
                preloadedClasses : classInfoRepository.findByProjectId(projectId);
        List<ClassRelation> relations = preloadedRelations != null ?
                preloadedRelations : classRelationRepository.findByProjectId(projectId);

        Set<String> unrecoveredSet = new HashSet<>(unrecoveredClassNames);

        // 按3级包前缀分组
        Map<String, List<ClassInfo>> blocks = new LinkedHashMap<>();
        for (ClassInfo ci : allClasses) {
            if (!unrecoveredSet.contains(ci.getFullyQualifiedName())) continue;
            String pkg = ci.getPackageName() != null ? ci.getPackageName() : "default";
            String prefix = getPackagePrefix(pkg, 3);
            blocks.computeIfAbsent(prefix, k -> new ArrayList<>()).add(ci);
        }

        log.info("Phase2分块聚类: {} 个类分为 {} 个包块", unrecoveredClassNames.size(), blocks.size());

        List<RecoveredComponent> allComponents = new ArrayList<>();
        int blockIdx = 0;

        for (Map.Entry<String, List<ClassInfo>> entry : blocks.entrySet()) {
            List<ClassInfo> blockClasses = entry.getValue();
            blockIdx++;

            if (blockClasses.size() < 2) {
                // 单个类直接作为组件
                if (blockClasses.size() == 1) {
                    RecoveredComponent comp = new RecoveredComponent();
                    comp.setProjectId(projectId);
                    comp.setRecoveryResultId(recoveryResultId);
                    comp.setName("Block_" + blockClasses.get(0).getSimpleName());
                    comp.setLevel(1);
                    comp.setClassNames(blockClasses.get(0).getFullyQualifiedName());
                    comp.setSource("CLUSTERING");
                    allComponents.add(comp);
                }
                continue;
            }

            // 对每个块独立构建相似度矩阵并聚类
            double[][] similarity = similarityCalculator.buildContextAwareSimilarityMatrix(
                    blockClasses, allClasses, relations, structWeight, semanticWeight);

            ClusteringParams params = buildClusteringParams(request, similarity);

            ClusteringAlgorithm algorithm = algorithmFactory.getAlgorithm(
                    algorithmType != null ? algorithmType : ClusteringAlgorithmType.AFFINITY_PROPAGATION);
            Map<Integer, List<Integer>> clusters = algorithm.cluster(similarity, params);

            for (Map.Entry<Integer, List<Integer>> clusterEntry : clusters.entrySet()) {
                List<Integer> memberIndices = clusterEntry.getValue();
                List<String> classNames = memberIndices.stream()
                        .map(i -> blockClasses.get(i).getFullyQualifiedName())
                        .collect(Collectors.toList());

                int representativeIdx = memberIndices.get(0);
                if (clusterEntry.getKey() >= 0 && clusterEntry.getKey() < blockClasses.size()) {
                    representativeIdx = clusterEntry.getKey();
                }
                String exemplarName = blockClasses.get(representativeIdx).getSimpleName();

                RecoveredComponent comp = new RecoveredComponent();
                comp.setProjectId(projectId);
                comp.setRecoveryResultId(recoveryResultId);
                comp.setName("Cluster_" + exemplarName);
                comp.setLevel(1);
                comp.setClassNames(String.join(",", classNames));
                comp.setSource("CLUSTERING");
                allComponents.add(comp);
            }

            if (blockIdx % 10 == 0) {
                log.info("Phase2分块进度: {}/{} 块完成", blockIdx, blocks.size());
            }
        }

        log.info("Phase2分块聚类完成: {} 个组件", allComponents.size());
        return allComponents;
    }

    private String getPackagePrefix(String packageName, int depth) {
        String[] parts = packageName.split("\\.");
        if (parts.length <= depth) return packageName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
