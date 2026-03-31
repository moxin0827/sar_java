package com.example.javaparser.service.recovery;

import com.example.javaparser.config.RecoveryProperties;
import com.example.javaparser.dto.RecoveryRequest;
import com.example.javaparser.dto.RecoveryResultDTO;
import com.example.javaparser.entity.*;
import com.example.javaparser.repository.*;
import com.example.javaparser.service.SourceCodePersistService;
import com.example.javaparser.service.llm.ComponentNamingService;
import com.example.javaparser.service.llm.FskGenerationService;
import com.example.javaparser.service.llm.SemanticEmbeddingService;
import com.example.javaparser.service.recovery.clustering.ClusteringAlgorithmType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class ArchitectureRecoveryOrchestrator {

    @Autowired
    private SourceCodePersistService sourceCodePersistService;

    @Autowired
    private FskGenerationService fskGenerationService;

    @Autowired
    private SemanticEmbeddingService semanticEmbeddingService;

    @Autowired
    private Phase1FskRecovery phase1FskRecovery;

    @Autowired
    private Phase2ClusteringRecovery phase2ClusteringRecovery;

    @Autowired
    private ComponentNamingService componentNamingService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RecoveryResultRepository recoveryResultRepository;

    @Autowired
    private RecoveredComponentRepository recoveredComponentRepository;

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    @Autowired
    private RecoveredComponentToXmiService recoveredComponentToXmiService;

    @Autowired
    private RecoveryProperties recoveryProperties;

    @Autowired
    private SimilarityCalculator similarityCalculator;

    public RecoveryResultDTO recover(Long projectId, RecoveryRequest request, String outputDir) {
        long startTime = System.currentTimeMillis();
        log.info("开始架构恢复: projectId={}, outputDir={}", projectId, outputDir);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        // 1. 确保已解析
        if (!sourceCodePersistService.ensureParsed(projectId)) {
            if (project.getSourcePath() != null) {
                try {
                    sourceCodePersistService.parseAndPersist(projectId, project.getSourcePath(), outputDir);
                } catch (Exception e) {
                    throw new RuntimeException("源码解析失败: " + e.getMessage(), e);
                }
            } else {
                throw new RuntimeException("项目未解析且无源码路径");
            }
        }

        // 优化：FSK生成和Embedding生成并行执行（两者互不依赖）
        long parallelStartTime = System.currentTimeMillis();
        boolean useFsk = request.getUseFskRecovery() != null ? request.getUseFskRecovery() : true;

        CompletableFuture<Void> fskFuture = CompletableFuture.completedFuture(null);
        if (useFsk) {
            final String projectName = project.getName();
            fskFuture = CompletableFuture.runAsync(() -> {
                try {
                    long t1 = System.currentTimeMillis();
                    fskGenerationService.generateFsk(projectId, projectName);
                    log.info("FSK生成耗时: {}ms", System.currentTimeMillis() - t1);
                } catch (Exception e) {
                    log.error("FSK生成失败", e);
                }
            });
        }

        CompletableFuture<Void> embeddingFuture = CompletableFuture.runAsync(() -> {
            try {
                long t2 = System.currentTimeMillis();
                semanticEmbeddingService.generateAllEmbeddings(projectId);
                log.info("Embedding生成耗时: {}ms", System.currentTimeMillis() - t2);
            } catch (Exception e) {
                log.error("Embedding生成失败", e);
            }
        });

        // 等待两者完成
        try {
            CompletableFuture.allOf(fskFuture, embeddingFuture).get(30, TimeUnit.MINUTES);
            long parallelElapsed = System.currentTimeMillis() - parallelStartTime;
            log.info("FSK与Embedding并行生成完成，总耗时: {}ms", parallelElapsed);
        } catch (Exception e) {
            log.error("并行生成FSK/Embedding超时或失败", e);
        }

        // 优化：统一预加载数据，传递给后续阶段，避免重复DB查询
        List<ClassInfo> allClassesRaw = classInfoRepository.findByProjectId(projectId);
        List<ClassRelation> allRelations = classRelationRepository.findByProjectId(projectId);

        // 过滤非核心类（测试类、内部类等），提高恢复精确度
        List<ClassInfo> allClasses = filterCoreClasses(allClassesRaw);
        if (allClasses.size() < allClassesRaw.size()) {
            log.info("类过滤: {} -> {} 个核心类（过滤 {} 个测试/内部/辅助类）",
                    allClassesRaw.size(), allClasses.size(), allClassesRaw.size() - allClasses.size());
        }

        // 4. 创建RecoveryResult记录
        ClusteringAlgorithmType algorithmType = resolveAlgorithmType(request);
        RecoveryResult result = new RecoveryResult();
        result.setProjectId(projectId);
        result.setStructWeight(request.getStructWeight() != null ?
                request.getStructWeight() : recoveryProperties.getStructWeight());
        result.setSemanticWeight(request.getSemanticWeight() != null ?
                request.getSemanticWeight() : recoveryProperties.getSemanticWeight());
        result.setThreshold(request.getThreshold() != null ?
                request.getThreshold() : recoveryProperties.getThreshold());
        result.setClusteringAlgorithm(algorithmType.name());
        recoveryResultRepository.save(result);

        List<RecoveredComponent> allComponents = new ArrayList<>();

        // 收集已分配的类名（跨Phase共享）
        Set<String> assignedClasses = new HashSet<>();

        // 5a. Phase 0: 大型项目包结构预分组（>= 200类时启用）
        //     利用包层次结构天然的模块边界，将共享顶层包前缀的类预分组
        //     对 junit5 这种 JPMS 模块项目效果极好（每个模块有独立包前缀）
        int LARGE_PROJECT_THRESHOLD = 200;
        if (allClasses.size() >= LARGE_PROJECT_THRESHOLD) {
            long phase0StartTime = System.currentTimeMillis();
            List<RecoveredComponent> packageComponents = packageBasedPreGrouping(
                    allClasses, projectId, result.getId());
            if (!packageComponents.isEmpty()) {
                allComponents.addAll(packageComponents);
                for (RecoveredComponent comp : packageComponents) {
                    if (comp.getClassNames() != null) {
                        assignedClasses.addAll(Arrays.asList(comp.getClassNames().split(",")));
                    }
                }
                log.info("Phase0 包结构预分组耗时: {}ms, 分配 {} 个组件 ({} 个类)",
                        System.currentTimeMillis() - phase0StartTime,
                        packageComponents.size(), assignedClasses.size());
            }
        }

        // 5b. Phase 1: FSK-based recovery（传入预加载数据，排除Phase0已分配的类）
        long phase1StartTime = System.currentTimeMillis();
        List<String> unrecoveredClasses;
        if (useFsk) {
            // 过滤掉Phase0已分配的类，避免重复分配
            List<ClassInfo> phase1Candidates = allClasses;
            if (!assignedClasses.isEmpty()) {
                phase1Candidates = allClasses.stream()
                        .filter(ci -> !assignedClasses.contains(ci.getFullyQualifiedName()))
                        .collect(Collectors.toList());
                log.info("Phase1输入: {} 个类（排除Phase0已分配的 {} 个类）",
                        phase1Candidates.size(), assignedClasses.size());
            }
            Phase1FskRecovery.Phase1Result phase1Result =
                    phase1FskRecovery.recover(projectId, result.getId(), phase1Candidates);
            allComponents.addAll(phase1Result.components);
            // 未恢复类 = Phase1未分配的类（已排除Phase0的）
            unrecoveredClasses = phase1Result.unrecoveredClassNames;
            log.info("Phase1 FSK恢复耗时: {}ms, 恢复 {} 个组件, 剩余 {} 个类",
                    System.currentTimeMillis() - phase1StartTime,
                    phase1Result.components.size(),
                    unrecoveredClasses.size());
        } else {
            // 所有未被Phase0分配的类进入Phase2
            unrecoveredClasses = allClasses.stream()
                    .map(ClassInfo::getFullyQualifiedName)
                    .filter(fqn -> !assignedClasses.contains(fqn))
                    .collect(Collectors.toList());
        }

        // 5c. FSK组件拆分检查（小型项目优化）
        //     如果一个FSK组件内的类跨越多个不同的顶层包前缀，按包前缀拆分
        if (!allComponents.isEmpty() && allClasses.size() < LARGE_PROJECT_THRESHOLD) {
            int beforeSplit = allComponents.size();
            allComponents = splitCrossPackageComponents(allComponents, projectId, result.getId(), allClasses.size());
            if (allComponents.size() > beforeSplit) {
                log.info("FSK组件拆分: {} -> {} 个组件", beforeSplit, allComponents.size());
            }
        }

        // 6. Phase 2: Clustering recovery（传入预加载数据）
        boolean useClustering = request.getUseClusteringRecovery() != null ?
                request.getUseClusteringRecovery() : true;
        if (useClustering && !unrecoveredClasses.isEmpty()) {
            long phase2StartTime = System.currentTimeMillis();
            List<RecoveredComponent> phase2Components = phase2ClusteringRecovery.recover(
                    projectId, result.getId(), unrecoveredClasses,
                    request.getStructWeight(), request.getSemanticWeight(),
                    allClasses, allRelations, algorithmType, request);
            allComponents.addAll(phase2Components);
            log.info("Phase2 {}聚类恢复耗时: {}ms, 恢复 {} 个组件",
                    algorithmType,
                    System.currentTimeMillis() - phase2StartTime,
                    phase2Components.size());
        }

        // 7. 后处理：合并小组件
        if (Boolean.TRUE.equals(recoveryProperties.getEnableMergeSmallComponents())) {
            long mergeStartTime = System.currentTimeMillis();
            int beforeMerge = allComponents.size();
            allComponents = mergeSmallComponents(allComponents, allClasses, allRelations,
                    request.getStructWeight(), request.getSemanticWeight());
            log.info("组件合并后处理耗时: {}ms, {} -> {} 个组件",
                    System.currentTimeMillis() - mergeStartTime, beforeMerge, allComponents.size());
        }

        // 8. 优化：批量LLM命名（一次调用完成所有组件命名）
        long namingStartTime = System.currentTimeMillis();
        componentNamingService.batchGenerateNames(allComponents, allClasses);
        log.info("组件命名耗时: {}ms", System.currentTimeMillis() - namingStartTime);

        // 9. 保存所有组件
        recoveredComponentRepository.saveAll(allComponents);

        // 10. 生成 component.xmi（如果指定了输出目录）
        if (outputDir != null && !outputDir.isEmpty()) {
            try {
                recoveredComponentToXmiService.generateComponentXmi(projectId, allComponents, outputDir);
            } catch (Exception e) {
                log.error("生成 component.xmi 失败（不影响恢复结果）", e);
            }
        }

        // 11. 更新结果统计
        long elapsed = System.currentTimeMillis() - startTime;
        int totalClasses = allComponents.stream()
                .mapToInt(c -> c.getClassNames() != null ?
                        c.getClassNames().split(",").length : 0)
                .sum();

        result.setTotalComponents(allComponents.size());
        result.setTotalClasses(totalClasses);
        result.setProcessingTimeMs(elapsed);
        recoveryResultRepository.save(result);

        // 更新项目状态
        project.setStatus("RECOVERED");
        projectRepository.save(project);

        log.info("架构恢复完成: {} 个组件, {} 个类, 耗时 {}ms",
                allComponents.size(), totalClasses, elapsed);

        return buildResultDTO(result, allComponents);
    }

    /**
     * 多算法对比恢复：前置步骤（解析/FSK/Embedding/Phase1）只执行一次，
     * 然后对每个算法分别执行 Phase2 + 合并 + 命名，返回各算法的恢复结果。
     *
     * @param projectId  项目ID
     * @param request    基础请求参数（权重等）
     * @param outputDir  输出目录
     * @param algorithms 要对比的算法列表
     * @return 算法名 -> 恢复结果 的有序映射
     */
    public Map<String, RecoveryResultDTO> recoverMultiAlgorithm(Long projectId, RecoveryRequest request,
                                                                  String outputDir, List<String> algorithms) {
        long totalStartTime = System.currentTimeMillis();
        log.info("开始多算法对比恢复: projectId={}, algorithms={}", projectId, algorithms);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        // ========== 前置步骤（只执行一次） ==========

        // 1. 确保已解析
        if (!sourceCodePersistService.ensureParsed(projectId)) {
            if (project.getSourcePath() != null) {
                try {
                    sourceCodePersistService.parseAndPersist(projectId, project.getSourcePath(), outputDir);
                } catch (Exception e) {
                    throw new RuntimeException("源码解析失败: " + e.getMessage(), e);
                }
            } else {
                throw new RuntimeException("项目未解析且无源码路径");
            }
        }

        // 2. FSK + Embedding 并行生成（只执行一次）
        long parallelStartTime = System.currentTimeMillis();
        boolean useFsk = request.getUseFskRecovery() != null ? request.getUseFskRecovery() : true;

        CompletableFuture<Void> fskFuture = CompletableFuture.completedFuture(null);
        if (useFsk) {
            final String projectName = project.getName();
            fskFuture = CompletableFuture.runAsync(() -> {
                try {
                    long t1 = System.currentTimeMillis();
                    fskGenerationService.generateFsk(projectId, projectName);
                    log.info("[多算法共享] FSK生成耗时: {}ms", System.currentTimeMillis() - t1);
                } catch (Exception e) {
                    log.error("FSK生成失败", e);
                }
            });
        }

        CompletableFuture<Void> embeddingFuture = CompletableFuture.runAsync(() -> {
            try {
                long t2 = System.currentTimeMillis();
                semanticEmbeddingService.generateAllEmbeddings(projectId);
                log.info("[多算法共享] Embedding生成耗时: {}ms", System.currentTimeMillis() - t2);
            } catch (Exception e) {
                log.error("Embedding生成失败", e);
            }
        });

        try {
            CompletableFuture.allOf(fskFuture, embeddingFuture).get(30, TimeUnit.MINUTES);
            log.info("[多算法共享] FSK与Embedding并行生成完成，总耗时: {}ms",
                    System.currentTimeMillis() - parallelStartTime);
        } catch (Exception e) {
            log.error("并行生成FSK/Embedding超时或失败", e);
        }

        // 3. 预加载数据（只查询一次）
        List<ClassInfo> allClassesRaw = classInfoRepository.findByProjectId(projectId);
        List<ClassRelation> allRelations = classRelationRepository.findByProjectId(projectId);

        // 过滤非核心类
        List<ClassInfo> allClasses = filterCoreClasses(allClassesRaw);
        if (allClasses.size() < allClassesRaw.size()) {
            log.info("[多算法共享] 类过滤: {} -> {} 个核心类", allClassesRaw.size(), allClasses.size());
        }

        // 4. Phase 0: 大型项目包结构预分组（与 recover() 一致）
        int LARGE_PROJECT_THRESHOLD = 200;
        List<RecoveredComponent> phase0Components = new ArrayList<>();
        Set<String> phase0AssignedClasses = new HashSet<>();
        if (allClasses.size() >= LARGE_PROJECT_THRESHOLD) {
            long phase0Start = System.currentTimeMillis();
            phase0Components = packageBasedPreGrouping(allClasses, projectId, null);
            for (RecoveredComponent comp : phase0Components) {
                if (comp.getClassNames() != null) {
                    phase0AssignedClasses.addAll(Arrays.asList(comp.getClassNames().split(",")));
                }
            }
            log.info("[多算法共享] Phase0包预分组耗时: {}ms, {} 个组件, {} 个类",
                    System.currentTimeMillis() - phase0Start,
                    phase0Components.size(), phase0AssignedClasses.size());
        }

        // 4b. Phase 1: FSK恢复（只执行一次，结果被所有算法共享）
        List<RecoveredComponent> phase1Components = new ArrayList<>();
        List<String> unrecoveredClasses;
        if (useFsk) {
            long phase1Start = System.currentTimeMillis();
            // 排除Phase0已分配的类
            List<ClassInfo> phase1Candidates = allClasses;
            if (!phase0AssignedClasses.isEmpty()) {
                phase1Candidates = allClasses.stream()
                        .filter(ci -> !phase0AssignedClasses.contains(ci.getFullyQualifiedName()))
                        .collect(Collectors.toList());
            }
            Phase1FskRecovery.Phase1Result phase1Result =
                    phase1FskRecovery.recover(projectId, null, phase1Candidates);
            phase1Components = phase1Result.components;
            unrecoveredClasses = phase1Result.unrecoveredClassNames;
            log.info("[多算法共享] Phase1 FSK恢复耗时: {}ms, {} 个组件, {} 个未恢复类",
                    System.currentTimeMillis() - phase1Start,
                    phase1Components.size(), unrecoveredClasses.size());
        } else {
            unrecoveredClasses = allClasses.stream()
                    .map(ClassInfo::getFullyQualifiedName)
                    .filter(fqn -> !phase0AssignedClasses.contains(fqn))
                    .collect(Collectors.toList());
        }

        // 4c. FSK组件拆分（小型项目）
        if (!phase1Components.isEmpty() && allClasses.size() < LARGE_PROJECT_THRESHOLD) {
            int beforeSplit = phase1Components.size();
            phase1Components = splitCrossPackageComponents(phase1Components, projectId, null, allClasses.size());
            if (phase1Components.size() > beforeSplit) {
                log.info("[多算法共享] FSK组件拆分: {} -> {}", beforeSplit, phase1Components.size());
            }
        }

        long sharedElapsed = System.currentTimeMillis() - totalStartTime;
        log.info("[多算法共享] 前置步骤总耗时: {}ms", sharedElapsed);

        // ========== 每个算法独立执行 Phase2 + 后处理 ==========

        Map<String, RecoveryResultDTO> resultMap = new LinkedHashMap<>();

        for (String algoName : algorithms) {
            long algoStartTime = System.currentTimeMillis();
            log.info("开始算法 {} 的恢复", algoName);

            try {
                ClusteringAlgorithmType algorithmType;
                try {
                    algorithmType = ClusteringAlgorithmType.valueOf(algoName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("未知聚类算法: {}, 跳过", algoName);
                    continue;
                }

                // 创建本算法的 RecoveryResult 记录
                RecoveryResult result = new RecoveryResult();
                result.setProjectId(projectId);
                result.setStructWeight(request.getStructWeight() != null ?
                        request.getStructWeight() : recoveryProperties.getStructWeight());
                result.setSemanticWeight(request.getSemanticWeight() != null ?
                        request.getSemanticWeight() : recoveryProperties.getSemanticWeight());
                result.setThreshold(request.getThreshold() != null ?
                        request.getThreshold() : recoveryProperties.getThreshold());
                result.setClusteringAlgorithm(algorithmType.name());
                recoveryResultRepository.save(result);

                // 复制 Phase0 + Phase1 组件（每个算法需要独立副本，因为后续合并会修改）
                List<RecoveredComponent> allComponents = new ArrayList<>();
                // 先复制Phase0组件
                for (RecoveredComponent p0 : phase0Components) {
                    RecoveredComponent copy = new RecoveredComponent();
                    copy.setProjectId(p0.getProjectId());
                    copy.setRecoveryResultId(result.getId());
                    copy.setName(p0.getName());
                    copy.setParentComponentId(p0.getParentComponentId());
                    copy.setLevel(p0.getLevel());
                    copy.setClassNames(p0.getClassNames());
                    copy.setSource(p0.getSource());
                    allComponents.add(copy);
                }
                // 再复制Phase1组件
                for (RecoveredComponent p1 : phase1Components) {
                    RecoveredComponent copy = new RecoveredComponent();
                    copy.setProjectId(p1.getProjectId());
                    copy.setRecoveryResultId(result.getId());
                    copy.setName(p1.getName());
                    copy.setParentComponentId(p1.getParentComponentId());
                    copy.setLevel(p1.getLevel());
                    copy.setClassNames(p1.getClassNames());
                    copy.setSource(p1.getSource());
                    allComponents.add(copy);
                }

                // Phase 2: 聚类恢复
                boolean useClustering = request.getUseClusteringRecovery() != null ?
                        request.getUseClusteringRecovery() : true;
                if (useClustering && !unrecoveredClasses.isEmpty()) {
                    long phase2Start = System.currentTimeMillis();
                    RecoveryRequest algoRequest = new RecoveryRequest();
                    algoRequest.setClusteringAlgorithm(algoName);
                    algoRequest.setStructWeight(request.getStructWeight());
                    algoRequest.setSemanticWeight(request.getSemanticWeight());
                    algoRequest.setThreshold(request.getThreshold());
                    algoRequest.setDbscanEps(request.getDbscanEps());
                    algoRequest.setDbscanMinPts(request.getDbscanMinPts());
                    algoRequest.setNumClusters(request.getNumClusters());
                    algoRequest.setLinkage(request.getLinkage());

                    List<RecoveredComponent> phase2Components = phase2ClusteringRecovery.recover(
                            projectId, result.getId(), unrecoveredClasses,
                            request.getStructWeight(), request.getSemanticWeight(),
                            allClasses, allRelations, algorithmType, algoRequest);
                    allComponents.addAll(phase2Components);
                    log.info("[{}] Phase2聚类恢复耗时: {}ms, {} 个组件",
                            algoName, System.currentTimeMillis() - phase2Start, phase2Components.size());
                }

                // 后处理：合并小组件
                if (Boolean.TRUE.equals(recoveryProperties.getEnableMergeSmallComponents())) {
                    long mergeStart = System.currentTimeMillis();
                    int beforeMerge = allComponents.size();
                    allComponents = mergeSmallComponents(allComponents, allClasses, allRelations,
                            request.getStructWeight(), request.getSemanticWeight());
                    log.info("[{}] 组件合并后处理耗时: {}ms, {} -> {} 个组件",
                            algoName, System.currentTimeMillis() - mergeStart,
                            beforeMerge, allComponents.size());
                }

                // 批量命名
                componentNamingService.batchGenerateNames(allComponents, allClasses);

                // 保存组件
                recoveredComponentRepository.saveAll(allComponents);

                // 生成 XMI
                if (outputDir != null && !outputDir.isEmpty()) {
                    try {
                        recoveredComponentToXmiService.generateComponentXmi(projectId, allComponents, outputDir);
                    } catch (Exception e) {
                        log.error("[{}] 生成 component.xmi 失败", algoName, e);
                    }
                }

                // 更新结果统计
                long algoElapsed = System.currentTimeMillis() - algoStartTime;
                int totalClassCount = allComponents.stream()
                        .mapToInt(c -> c.getClassNames() != null ?
                                c.getClassNames().split(",").length : 0)
                        .sum();
                result.setTotalComponents(allComponents.size());
                result.setTotalClasses(totalClassCount);
                result.setProcessingTimeMs(sharedElapsed + algoElapsed);
                recoveryResultRepository.save(result);

                resultMap.put(algoName, buildResultDTO(result, allComponents));
                log.info("[{}] 恢复完成: {} 个组件, 算法耗时 {}ms, 总耗时 {}ms",
                        algoName, allComponents.size(), algoElapsed, sharedElapsed + algoElapsed);

            } catch (Exception e) {
                log.error("[{}] 恢复失败", algoName, e);
            }
        }

        // 更新项目状态
        project.setStatus("RECOVERED");
        projectRepository.save(project);

        log.info("多算法对比恢复完成: {} 个算法, 总耗时 {}ms",
                resultMap.size(), System.currentTimeMillis() - totalStartTime);
        return resultMap;
    }

    public RecoveryResultDTO getLatestResult(Long projectId) {
        RecoveryResult result = recoveryResultRepository
                .findTopByProjectIdOrderByCreatedAtDesc(projectId)
                .orElse(null);
        if (result == null) return null;

        List<RecoveredComponent> components =
                recoveredComponentRepository.findByRecoveryResultId(result.getId());
        return buildResultDTO(result, components);
    }

    private RecoveryResultDTO buildResultDTO(RecoveryResult result,
                                              List<RecoveredComponent> components) {
        RecoveryResultDTO dto = new RecoveryResultDTO();
        dto.setResultId(result.getId());
        dto.setProjectId(result.getProjectId());
        dto.setTotalComponents(result.getTotalComponents());
        dto.setTotalClasses(result.getTotalClasses());
        dto.setProcessingTimeMs(result.getProcessingTimeMs());
        dto.setClusteringAlgorithm(result.getClusteringAlgorithm());

        List<RecoveryResultDTO.ComponentDTO> compDTOs = components.stream().map(c -> {
            RecoveryResultDTO.ComponentDTO cd = new RecoveryResultDTO.ComponentDTO();
            cd.setId(c.getId());
            cd.setName(c.getName());
            cd.setParentComponentId(c.getParentComponentId());
            cd.setLevel(c.getLevel());
            cd.setClassNames(c.getClassNames() != null ?
                    Arrays.asList(c.getClassNames().split(",")) : Collections.emptyList());
            cd.setSource(c.getSource());
            return cd;
        }).collect(Collectors.toList());

        dto.setComponents(compDTOs);
        return dto;
    }

    /**
     * 解析聚类算法类型（请求参数覆盖配置文件默认值）
     */
    private ClusteringAlgorithmType resolveAlgorithmType(RecoveryRequest request) {
        String algo = request.getClusteringAlgorithm();
        if (algo == null || algo.isEmpty()) {
            algo = recoveryProperties.getClusteringAlgorithm();
        }
        try {
            return ClusteringAlgorithmType.valueOf(algo.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知聚类算法: {}, 回退到AP", algo);
            return ClusteringAlgorithmType.AFFINITY_PROPAGATION;
        }
    }

    /**
     * 后处理：将小组件合并到最相似的大组件中
     *
     * 策略：
     * 1. 将组件分为"大组件"(>= minSize) 和"小组件"(< minSize)
     * 2. 计算每个小组件与所有大组件之间的平均类间相似度
     * 3. 将小组件合并到最相似的大组件
     * 4. 如果没有大组件超过相似度阈值，将小组件之间按包名聚合
     * 5. 聚合后仍然过小的归入 Misc 组件
     */
    private List<RecoveredComponent> mergeSmallComponents(List<RecoveredComponent> components,
                                                           List<ClassInfo> allClasses,
                                                           List<ClassRelation> allRelations,
                                                           Double structWeight, Double semanticWeight) {
        int minSize = recoveryProperties.getMinComponentSize() != null ?
                recoveryProperties.getMinComponentSize() : 3;
        double mergeThreshold = recoveryProperties.getMergeSimThreshold() != null ?
                recoveryProperties.getMergeSimThreshold() : 0.1;

        if (components.size() <= 1) return components;

        // 构建 FQN -> ClassInfo 索引
        Map<String, ClassInfo> classInfoMap = new HashMap<>();
        for (ClassInfo ci : allClasses) {
            classInfoMap.put(ci.getFullyQualifiedName(), ci);
        }

        // 分离大组件和小组件
        List<RecoveredComponent> largeComponents = new ArrayList<>();
        List<RecoveredComponent> smallComponents = new ArrayList<>();
        for (RecoveredComponent comp : components) {
            int classCount = getClassCount(comp);
            if (classCount >= minSize) {
                largeComponents.add(comp);
            } else {
                smallComponents.add(comp);
            }
        }

        if (smallComponents.isEmpty()) {
            log.info("无需合并: 所有 {} 个组件均 >= {} 个类", components.size(), minSize);
            return components;
        }

        log.info("组件合并: {} 个大组件, {} 个小组件 (阈值={})",
                largeComponents.size(), smallComponents.size(), minSize);

        // 如果没有大组件，先将小组件按包名前缀聚合
        if (largeComponents.isEmpty()) {
            largeComponents = aggregateByPackage(smallComponents, minSize);
            // 重新分离
            smallComponents = new ArrayList<>();
            List<RecoveredComponent> newLarge = new ArrayList<>();
            for (RecoveredComponent comp : largeComponents) {
                if (getClassCount(comp) >= minSize) {
                    newLarge.add(comp);
                } else {
                    smallComponents.add(comp);
                }
            }
            largeComponents = newLarge;
        }

        if (largeComponents.isEmpty()) {
            // 仍然没有大组件，直接返回原始结果
            log.info("包名聚合后仍无大组件，跳过合并");
            return components;
        }

        // 预加载 embedding 和包名缓存（小组件合并 + 迭代合并共用）
        Map<String, double[]> embeddingCache = new HashMap<>(classInfoMap.size() * 2);
        Map<String, String> packageCache = new HashMap<>(classInfoMap.size() * 2);
        int embDim = 0;
        for (Map.Entry<String, ClassInfo> entry : classInfoMap.entrySet()) {
            ClassInfo ci = entry.getValue();
            double[] emb = semanticEmbeddingService.getEmbeddingVector(ci);
            embeddingCache.put(entry.getKey(), emb);
            packageCache.put(entry.getKey(), ci.getPackageName());
            if (emb.length > 0 && embDim == 0) embDim = emb.length;
        }

        // 构建依赖关系映射
        Map<String, Set<String>> depMap = new HashMap<>();
        for (ClassRelation rel : allRelations) {
            depMap.computeIfAbsent(rel.getSourceClassName(), k -> new HashSet<>())
                    .add(rel.getTargetClassName());
        }

        // 将每个小组件合并到最相似的大组件（使用质心+依赖+包名分布）
        for (RecoveredComponent small : smallComponents) {
            List<String> smallClassNames = getClassNames(small);
            Set<String> smallClassSet = new HashSet<>(smallClassNames);
            double[] smallCentroid = computeCentroid(smallClassNames, embeddingCache, embDim);
            Map<String, Integer> smallPkgDist = computePackageDistribution(smallClassNames, packageCache);

            double bestSim = -1;
            int bestIdx = -1;

            for (int i = 0; i < largeComponents.size(); i++) {
                List<String> largeClassNames = getClassNames(largeComponents.get(i));
                Set<String> largeClassSet = new HashSet<>(largeClassNames);
                double[] largeCentroid = computeCentroid(largeClassNames, embeddingCache, embDim);
                Map<String, Integer> largePkgDist = computePackageDistribution(largeClassNames, packageCache);

                double sim = computeComponentSimilarityCentroid(
                        smallCentroid, largeCentroid,
                        smallPkgDist, largePkgDist,
                        smallClassSet, largeClassSet, depMap);
                if (sim > bestSim) {
                    bestSim = sim;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0 && bestSim >= mergeThreshold) {
                RecoveredComponent target = largeComponents.get(bestIdx);
                List<String> merged = new ArrayList<>(getClassNames(target));
                merged.addAll(smallClassNames);
                target.setClassNames(String.join(",", merged));
                log.debug("合并组件 '{}' ({} 类) -> '{}' (相似度={})",
                        small.getName(), smallClassNames.size(), target.getName(), bestSim);
            } else {
                RecoveredComponent misc = findOrCreateMisc(largeComponents, small);
                List<String> merged = new ArrayList<>(getClassNames(misc));
                merged.addAll(smallClassNames);
                misc.setClassNames(String.join(",", merged));
            }
        }

        log.info("小组件合并完成: {} 个组件", largeComponents.size());

        // === 迭代合并阶段：当组件数远超目标时，反复合并最相似的两个组件 ===
        int totalClasses = allClasses.size();
        // 目标组件数：使用 log2(N) 更贴近实际架构（与FSK prompt一致）
        int targetCount = recoveryProperties.getTargetComponentCount() != null
                && recoveryProperties.getTargetComponentCount() > 0 ?
                recoveryProperties.getTargetComponentCount() :
                Math.max(3, Math.min(12, (int)(Math.log(totalClasses) / Math.log(2))));

        // 优化2: Phase0组件数作为targetCount下限，避免强制合并高置信度的包结构组件
        int phase0Count = 0;
        for (RecoveredComponent comp : largeComponents) {
            if ("PACKAGE".equals(comp.getSource())) {
                phase0Count++;
            }
        }
        if (phase0Count > targetCount) {
            log.info("targetCount自适应: {} -> {} (Phase0组件数={})", targetCount, phase0Count, phase0Count);
            targetCount = phase0Count;
        }

        int upperBound = (int) (targetCount * 1.2);

        if (largeComponents.size() > upperBound) {
            log.info("迭代合并开始: 当前 {} 个组件, 目标上限 {} (targetCount={})",
                    largeComponents.size(), upperBound, targetCount);

            // 小项目（组件数<=50）使用逐类采样精确算法，大项目使用预计算精确矩阵+增量更新
            int FAST_MODE_THRESHOLD = 50;
            if (largeComponents.size() <= FAST_MODE_THRESHOLD) {
                log.info("使用精确模式迭代合并（组件数 <= {}）", FAST_MODE_THRESHOLD);
                largeComponents = iterativeMergeExact(largeComponents, embeddingCache, packageCache, depMap, embDim, mergeThreshold, upperBound);
            } else {
                log.info("使用预计算精确矩阵+增量更新模式迭代合并（组件数 > {}）", FAST_MODE_THRESHOLD);
                largeComponents = iterativeMergePrecomputed(largeComponents, embeddingCache, packageCache, depMap, embDim, mergeThreshold, upperBound);
            }

            log.info("迭代合并完成: {} 个组件", largeComponents.size());
        }

        return largeComponents;
    }

    /**
     * 精确模式迭代合并（小项目，组件数<=50）：使用质心+依赖+包名分布
     * 每轮重算所有质心，精度最高，适用于组件数较少的场景
     */
    private List<RecoveredComponent> iterativeMergeExact(List<RecoveredComponent> components,
                                                           Map<String, double[]> embeddingCache,
                                                           Map<String, String> packageCache,
                                                           Map<String, Set<String>> depMap,
                                                           int embDim,
                                                           double mergeThreshold, int upperBound) {
        List<RecoveredComponent> result = new ArrayList<>(components);

        while (result.size() > upperBound) {
            // 每轮重新计算所有组件的质心、包名分布、类集合
            int n = result.size();
            double[][] centroids = new double[n][];
            List<Map<String, Integer>> pkgDists = new ArrayList<>(n);
            List<Set<String>> classSets = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                List<String> classes = getClassNames(result.get(i));
                centroids[i] = computeCentroid(classes, embeddingCache, embDim);
                pkgDists.add(computePackageDistribution(classes, packageCache));
                classSets.add(new HashSet<>(classes));
            }

            double bestSim = -1;
            int bestI = -1, bestJ = -1;

            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    // 优化1: 禁止两个PACKAGE source组件互相合并（保护Phase0高置信度边界）
                    if ("PACKAGE".equals(result.get(i).getSource())
                            && "PACKAGE".equals(result.get(j).getSource())) {
                        continue;
                    }
                    double sim = computeComponentSimilarityCentroid(
                            centroids[i], centroids[j],
                            pkgDists.get(i), pkgDists.get(j),
                            classSets.get(i), classSets.get(j), depMap);
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }

            if (bestSim < mergeThreshold || bestI < 0) {
                log.info("精确迭代合并终止: 最高相似度 {} < 阈值 {}", bestSim, mergeThreshold);
                break;
            }

            RecoveredComponent compI = result.get(bestI);
            RecoveredComponent compJ = result.get(bestJ);
            List<String> merged = new ArrayList<>(getClassNames(compI));
            merged.addAll(getClassNames(compJ));
            compI.setClassNames(String.join(",", merged));
            // 合并后继承吸收方的source（PACKAGE吸收其他类型后仍为PACKAGE）
            log.debug("精确合并: '{}' + '{}' -> {} 类 (sim={})",
                    compI.getName(), compJ.getName(), merged.size(), bestSim);
            result.remove(bestJ);
        }

        return result;
    }

    /**
     * 预计算质心+依赖+包名矩阵的迭代合并（大项目）
     *
     * 相比旧版逐对采样方案的改进：
     * 1. 用组件质心 embedding 替代逐对采样 → 消除采样偏差，O(dim) vs O(sample²×dim)
     * 2. 引入依赖内聚度 → 保留结构信息（旧版完全丢失）
     * 3. 包名用分布相似度 → 比逐对平均更稳定
     * 4. 质心增量更新 O(dim) → 比重算逐对相似度快得多
     */
    private List<RecoveredComponent> iterativeMergePrecomputed(List<RecoveredComponent> components,
                                                                 Map<String, double[]> embeddingCache,
                                                                 Map<String, String> packageCache,
                                                                 Map<String, Set<String>> depMap,
                                                                 int embDim,
                                                                 double mergeThreshold, int upperBound) {
        int n = components.size();
        long startTime = System.currentTimeMillis();

        // 预计算每个组件的类名列表、质心 embedding、包名分布、类集合
        List<List<String>> compClassNames = new ArrayList<>(n);
        double[][] centroids = new double[n][];
        List<Map<String, Integer>> compPkgDists = new ArrayList<>(n);
        List<Set<String>> compClassSets = new ArrayList<>(n);
        final int finalEmbDim = embDim;

        for (int i = 0; i < n; i++) {
            List<String> classes = getClassNames(components.get(i));
            compClassNames.add(classes);
            centroids[i] = computeCentroid(classes, embeddingCache, finalEmbDim);
            compPkgDists.add(computePackageDistribution(classes, packageCache));
            compClassSets.add(new HashSet<>(classes));
        }

        // 4. 并行计算完整相似度矩阵（质心+包名分布+依赖内聚度）
        long matrixStart = System.currentTimeMillis();
        double[][] simMatrix = new double[n][n];
        IntStream.range(0, n).parallel().forEach(i -> {
            for (int j = i + 1; j < n; j++) {
                double sim = computeComponentSimilarityCentroid(
                        centroids[i], centroids[j],
                        compPkgDists.get(i), compPkgDists.get(j),
                        compClassSets.get(i), compClassSets.get(j), depMap);
                simMatrix[i][j] = sim;
                simMatrix[j][i] = sim;
            }
        });
        log.info("并行预计算质心相似度矩阵完成: {}x{}, 耗时 {}ms",
                n, n, System.currentTimeMillis() - matrixStart);

        // 5. 用活跃索引集合追踪未被合并的组件
        List<Integer> active = new ArrayList<>(n);
        for (int i = 0; i < n; i++) active.add(i);

        while (active.size() > upperBound) {
            // 找最相似的一对
            double bestSim = -1;
            int bestAi = -1, bestAj = -1;
            for (int ai = 0; ai < active.size(); ai++) {
                int i = active.get(ai);
                for (int aj = ai + 1; aj < active.size(); aj++) {
                    int j = active.get(aj);
                    // 优化1: 禁止两个PACKAGE source组件互相合并（保护Phase0高置信度边界）
                    if ("PACKAGE".equals(components.get(i).getSource())
                            && "PACKAGE".equals(components.get(j).getSource())) {
                        continue;
                    }
                    if (simMatrix[i][j] > bestSim) {
                        bestSim = simMatrix[i][j];
                        bestAi = ai;
                        bestAj = aj;
                    }
                }
            }

            if (bestSim < mergeThreshold || bestAi < 0) {
                log.info("预计算迭代合并终止: 最高相似度 {} < 阈值 {}", bestSim, mergeThreshold);
                break;
            }

            int idxI = active.get(bestAi);
            int idxJ = active.get(bestAj);

            // 合并 J 到 I（PACKAGE吸收其他类型后仍保留PACKAGE source）
            RecoveredComponent compI = components.get(idxI);
            List<String> mergedClasses = new ArrayList<>(compClassNames.get(idxI));
            mergedClasses.addAll(compClassNames.get(idxJ));
            compI.setClassNames(String.join(",", mergedClasses));
            compClassNames.set(idxI, mergedClasses);

            // 增量更新质心：加权平均（O(dim)，无需重新遍历所有类）
            int sizeI = compClassSets.get(idxI).size();
            int sizeJ = compClassSets.get(idxJ).size();
            centroids[idxI] = mergeCentroids(centroids[idxI], sizeI, centroids[idxJ], sizeJ, finalEmbDim);

            // 增量更新包名分布
            Map<String, Integer> mergedPkgDist = new HashMap<>(compPkgDists.get(idxI));
            for (Map.Entry<String, Integer> e : compPkgDists.get(idxJ).entrySet()) {
                mergedPkgDist.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            compPkgDists.set(idxI, mergedPkgDist);

            // 增量更新类集合
            Set<String> mergedSet = compClassSets.get(idxI);
            mergedSet.addAll(compClassSets.get(idxJ));

            // 增量更新相似度矩阵：只重算 idxI 与其他活跃组件的相似度
            for (int ai = 0; ai < active.size(); ai++) {
                int k = active.get(ai);
                if (k == idxI || k == idxJ) continue;
                double newSim = computeComponentSimilarityCentroid(
                        centroids[idxI], centroids[k],
                        compPkgDists.get(idxI), compPkgDists.get(k),
                        compClassSets.get(idxI), compClassSets.get(k), depMap);
                simMatrix[idxI][k] = newSim;
                simMatrix[k][idxI] = newSim;
            }

            // 移除 J
            active.remove(bestAj);

            if (active.size() % 50 == 0) {
                log.info("预计算迭代合并进度: 剩余 {} 个组件, 耗时 {}ms",
                        active.size(), System.currentTimeMillis() - startTime);
            }
        }

        log.info("预计算迭代合并总耗时: {}ms", System.currentTimeMillis() - startTime);

        // 收集结果
        List<RecoveredComponent> result = new ArrayList<>(active.size());
        for (int idx : active) {
            result.add(components.get(idx));
        }
        return result;
    }

    /**
     * 计算组件质心 embedding：所有类 embedding 的均值向量
     */
    private double[] computeCentroid(List<String> classes, Map<String, double[]> embeddingCache, int embDim) {
        if (embDim == 0) return new double[0];
        double[] centroid = new double[embDim];
        int count = 0;
        for (String fqn : classes) {
            double[] emb = embeddingCache.get(fqn);
            if (emb != null && emb.length == embDim) {
                for (int d = 0; d < embDim; d++) {
                    centroid[d] += emb[d];
                }
                count++;
            }
        }
        if (count > 0) {
            for (int d = 0; d < embDim; d++) {
                centroid[d] /= count;
            }
        }
        return centroid;
    }

    /**
     * 增量合并两个质心：加权平均，O(dim)
     */
    private double[] mergeCentroids(double[] centroidA, int sizeA, double[] centroidB, int sizeB, int embDim) {
        if (embDim == 0 || centroidA.length == 0 && centroidB.length == 0) return new double[0];
        if (centroidA.length == 0) return centroidB;
        if (centroidB.length == 0) return centroidA;
        double[] merged = new double[embDim];
        int total = sizeA + sizeB;
        if (total == 0) return merged;
        for (int d = 0; d < embDim; d++) {
            merged[d] = (centroidA[d] * sizeA + centroidB[d] * sizeB) / total;
        }
        return merged;
    }

    /**
     * 计算组件的包名分布：包名 -> 该包下的类数量
     */
    private Map<String, Integer> computePackageDistribution(List<String> classes, Map<String, String> packageCache) {
        Map<String, Integer> dist = new HashMap<>();
        for (String fqn : classes) {
            String pkg = packageCache.get(fqn);
            if (pkg != null) {
                dist.merge(pkg, 1, Integer::sum);
            }
        }
        return dist;
    }

    /**
     * 基于质心+包名分布+依赖内聚度的组件相似度计算
     *
     * 三维度加权：
     * - 语义相似度 (0.5)：质心 embedding 余弦相似度
     * - 包名分布相似度 (0.25)：两组件包名分布的重叠度
     * - 依赖内聚度 (0.25)：两组件间跨组件依赖占总依赖的比例
     */
    private double computeComponentSimilarityCentroid(double[] centroidA, double[] centroidB,
                                                        Map<String, Integer> pkgDistA, Map<String, Integer> pkgDistB,
                                                        Set<String> classSetA, Set<String> classSetB,
                                                        Map<String, Set<String>> depMap) {
        double sim = 0;

        // 1. 语义相似度：质心余弦相似度 (权重0.5)
        if (centroidA.length > 0 && centroidB.length > 0) {
            sim += 0.5 * Math.max(0, SemanticEmbeddingService.cosineSimilarity(centroidA, centroidB));
        }

        // 2. 包名分布相似度 (权重0.25)：计算两个分布的重叠比例
        sim += 0.25 * computePackageDistSimilarity(pkgDistA, pkgDistB);

        // 3. 依赖内聚度 (权重0.25)：A->B 和 B->A 的依赖数 / 两组件总外部依赖数
        sim += 0.25 * computeDependencyCohesion(classSetA, classSetB, depMap);

        return sim;
    }

    /**
     * 包名分布相似度：使用渐进式前缀匹配的加权重叠
     * 对每对包名计算前缀相似度，按类数加权
     */
    private double computePackageDistSimilarity(Map<String, Integer> distA, Map<String, Integer> distB) {
        if (distA.isEmpty() || distB.isEmpty()) return 0;

        int totalA = distA.values().stream().mapToInt(Integer::intValue).sum();
        int totalB = distB.values().stream().mapToInt(Integer::intValue).sum();
        if (totalA == 0 || totalB == 0) return 0;

        // 精确匹配的包名重叠
        double exactOverlap = 0;
        for (Map.Entry<String, Integer> eA : distA.entrySet()) {
            Integer countB = distB.get(eA.getKey());
            if (countB != null) {
                // 两边都有这个包，取较小占比
                exactOverlap += Math.min((double) eA.getValue() / totalA, (double) countB / totalB);
            }
        }

        // 前缀匹配的包名相似度（对非精确匹配的包名计算前缀相似度）
        double prefixSim = 0;
        double weightSum = 0;
        for (Map.Entry<String, Integer> eA : distA.entrySet()) {
            String pkgA = eA.getKey();
            double wA = (double) eA.getValue() / totalA;
            // 找 distB 中最相似的包
            double bestPkgSim = 0;
            for (Map.Entry<String, Integer> eB : distB.entrySet()) {
                String pkgB = eB.getKey();
                if (pkgA.equals(pkgB)) {
                    bestPkgSim = 1.0;
                    break;
                }
                String[] partsA = pkgA.split("\\.");
                String[] partsB = pkgB.split("\\.");
                int common = 0;
                for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
                    if (partsA[i].equals(partsB[i])) common++;
                    else break;
                }
                if (common > 0) {
                    double s = (double) common / Math.max(partsA.length, partsB.length);
                    if (s > bestPkgSim) bestPkgSim = s;
                }
            }
            prefixSim += wA * bestPkgSim;
            weightSum += wA;
        }

        double prefixScore = weightSum > 0 ? prefixSim / weightSum : 0;
        // 综合：精确重叠占60%，前缀相似度占40%
        return 0.6 * exactOverlap + 0.4 * prefixScore;
    }

    /**
     * 依赖内聚度：两组件间的跨组件依赖比例
     * = (A->B依赖数 + B->A依赖数) / (A的总外部依赖数 + B的总外部依赖数 + A<->B依赖数)
     * 值域 [0, 1]，越高说明两组件依赖越紧密
     */
    private double computeDependencyCohesion(Set<String> classSetA, Set<String> classSetB,
                                               Map<String, Set<String>> depMap) {
        int crossDeps = 0;
        int totalExternalA = 0;
        int totalExternalB = 0;

        // A 的类对外依赖
        for (String fqn : classSetA) {
            Set<String> deps = depMap.get(fqn);
            if (deps == null) continue;
            for (String dep : deps) {
                if (classSetA.contains(dep)) continue; // 内部依赖，跳过
                if (classSetB.contains(dep)) {
                    crossDeps++;
                }
                totalExternalA++;
            }
        }

        // B 的类对外依赖
        for (String fqn : classSetB) {
            Set<String> deps = depMap.get(fqn);
            if (deps == null) continue;
            for (String dep : deps) {
                if (classSetB.contains(dep)) continue; // 内部依赖，跳过
                if (classSetA.contains(dep)) {
                    crossDeps++;
                }
                totalExternalB++;
            }
        }

        int denom = totalExternalA + totalExternalB;
        if (denom == 0) return 0;
        return (double) crossDeps / denom;
    }

    /**
     * 按包名前缀聚合小组件
     */
    private List<RecoveredComponent> aggregateByPackage(List<RecoveredComponent> smallComponents, int minSize) {
        // 按顶层包名分组
        Map<String, List<RecoveredComponent>> packageGroups = new LinkedHashMap<>();
        for (RecoveredComponent comp : smallComponents) {
            List<String> classNames = getClassNames(comp);
            String topPackage = getTopPackage(classNames);
            packageGroups.computeIfAbsent(topPackage, k -> new ArrayList<>()).add(comp);
        }

        List<RecoveredComponent> result = new ArrayList<>();
        for (Map.Entry<String, List<RecoveredComponent>> entry : packageGroups.entrySet()) {
            List<RecoveredComponent> group = entry.getValue();
            if (group.size() == 1) {
                result.add(group.get(0));
                continue;
            }
            // 合并同包组件
            RecoveredComponent merged = group.get(0);
            List<String> allClassNames = new ArrayList<>(getClassNames(merged));
            for (int i = 1; i < group.size(); i++) {
                allClassNames.addAll(getClassNames(group.get(i)));
            }
            merged.setClassNames(String.join(",", allClassNames));
            merged.setName("Package_" + entry.getKey());
            result.add(merged);
        }
        return result;
    }

    /**
     * 获取类名列表中最常见的顶层包名（取前2级）
     */
    private String getTopPackage(List<String> classNames) {
        Map<String, Integer> pkgCount = new HashMap<>();
        for (String fqn : classNames) {
            String[] parts = fqn.split("\\.");
            String topPkg = parts.length >= 3 ?
                    parts[0] + "." + parts[1] + "." + parts[2] : fqn;
            pkgCount.merge(topPkg, 1, Integer::sum);
        }
        return pkgCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("misc");
    }

    private RecoveredComponent findOrCreateMisc(List<RecoveredComponent> components,
                                                  RecoveredComponent template) {
        for (RecoveredComponent comp : components) {
            if (comp.getName() != null && comp.getName().startsWith("Misc_")) {
                return comp;
            }
        }
        // 创建新的 Misc 组件
        RecoveredComponent misc = new RecoveredComponent();
        misc.setProjectId(template.getProjectId());
        misc.setRecoveryResultId(template.getRecoveryResultId());
        misc.setName("Misc_Uncategorized");
        misc.setLevel(1);
        misc.setClassNames("");
        misc.setSource("MERGE");
        components.add(misc);
        return misc;
    }

    private int getClassCount(RecoveredComponent comp) {
        if (comp.getClassNames() == null || comp.getClassNames().isEmpty()) return 0;
        return comp.getClassNames().split(",").length;
    }

    private List<String> getClassNames(RecoveredComponent comp) {
        if (comp.getClassNames() == null || comp.getClassNames().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(comp.getClassNames().split(",")));
    }

    /**
     * 过滤非核心类，只保留参与架构恢复的核心类。
     * 过滤规则：
     * 1. 测试类：simpleName 以 Test/Tests/TestCase/IT 结尾，或包名含 test/tests
     * 2. 内部类：FQN 包含 $
     * 3. package-info / module-info
     * 4. 测试辅助类：Mock*, Stub*, Fake*, *TestHelper, *TestUtil
     * 5. 生成代码：*_Generated, *Impl_ (某些框架生成)
     */
    private List<ClassInfo> filterCoreClasses(List<ClassInfo> allClasses) {
        List<ClassInfo> filtered = new ArrayList<>();
        for (ClassInfo ci : allClasses) {
            if (isNonCoreClass(ci)) continue;
            filtered.add(ci);
        }
        return filtered;
    }

    private boolean isNonCoreClass(ClassInfo ci) {
        String fqn = ci.getFullyQualifiedName();
        String simpleName = ci.getSimpleName();
        String pkg = ci.getPackageName() != null ? ci.getPackageName().toLowerCase() : "";

        // 内部类（包含$）
        if (fqn.contains("$")) return true;

        // package-info / module-info
        if ("package-info".equals(simpleName) || "module-info".equals(simpleName)) return true;

        // 测试包
        if (pkg.contains(".test.") || pkg.contains(".tests.")
                || pkg.endsWith(".test") || pkg.endsWith(".tests")
                || pkg.contains(".testing.") || pkg.endsWith(".testing")) {
            return true;
        }

        // 测试类名模式
        if (simpleName.endsWith("Test") || simpleName.endsWith("Tests")
                || simpleName.endsWith("TestCase") || simpleName.endsWith("IT")
                || simpleName.endsWith("Spec")) {
            return true;
        }
        if (simpleName.startsWith("Test") && simpleName.length() > 4
                && Character.isUpperCase(simpleName.charAt(4))) {
            return true;
        }

        // 测试辅助类
        if (simpleName.startsWith("Mock") || simpleName.startsWith("Stub")
                || simpleName.startsWith("Fake") || simpleName.startsWith("Dummy")
                || simpleName.endsWith("TestHelper") || simpleName.endsWith("TestUtil")
                || simpleName.endsWith("TestFixture") || simpleName.endsWith("TestBase")) {
            return true;
        }

        return false;
    }

    /**
     * Phase0: 大型项目包结构预分组
     *
     * 对于大型项目（>200类），利用包层次结构天然的模块边界信息进行预分组。
     * 算法：
     * 1. 收集所有类的包名，按层级构建包树
     * 2. 自适应选择分割深度：找到使分组数在 [3, sqrt(N)] 范围内的最浅包层级
     * 3. 过滤掉过小的分组（<3类），这些类留给后续Phase处理
     *
     * 对 junit5 这种 JPMS 模块项目效果极好（每个模块有独立的4级包前缀）。
     */
    private List<RecoveredComponent> packageBasedPreGrouping(List<ClassInfo> allClasses,
                                                              Long projectId, Long recoveryResultId) {
        int n = allClasses.size();
        int targetMin = 3;
        int targetMax = Math.max(15, (int) Math.ceil(Math.sqrt(n)));

        // 尝试不同的包深度（从深到浅），找到最佳分割
        int bestDepth = -1;
        Map<String, List<ClassInfo>> bestGroups = null;

        for (int depth = 5; depth >= 2; depth--) {
            Map<String, List<ClassInfo>> groups = new LinkedHashMap<>();
            for (ClassInfo ci : allClasses) {
                String pkg = ci.getPackageName();
                if (pkg == null) pkg = "default";
                String prefix = getPackagePrefix(pkg, depth);
                groups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(ci);
            }

            // 过滤掉过小的分组
            int validGroups = 0;
            int assignedCount = 0;
            for (List<ClassInfo> group : groups.values()) {
                if (group.size() >= 3) {
                    validGroups++;
                    assignedCount += group.size();
                }
            }

            // 检查分组数是否在合理范围内，且覆盖率足够高
            double coverage = (double) assignedCount / n;
            if (validGroups >= targetMin && validGroups <= targetMax && coverage >= 0.7) {
                bestDepth = depth;
                bestGroups = groups;
                break;
            }
        }

        if (bestGroups == null || bestDepth < 0) {
            log.info("Phase0: 未找到合适的包分割深度，跳过预分组");
            return Collections.emptyList();
        }

        // 构建组件
        List<RecoveredComponent> components = new ArrayList<>();
        int skippedClasses = 0;
        for (Map.Entry<String, List<ClassInfo>> entry : bestGroups.entrySet()) {
            List<ClassInfo> group = entry.getValue();
            if (group.size() < 3) {
                skippedClasses += group.size();
                continue; // 过小的分组留给Phase1/Phase2
            }

            List<String> classNames = group.stream()
                    .map(ClassInfo::getFullyQualifiedName)
                    .collect(Collectors.toList());

            RecoveredComponent comp = new RecoveredComponent();
            comp.setProjectId(projectId);
            comp.setRecoveryResultId(recoveryResultId);
            comp.setName("Pkg_" + entry.getKey());
            comp.setLevel(1);
            comp.setClassNames(String.join(",", classNames));
            comp.setSource("PACKAGE");
            components.add(comp);
        }

        log.info("Phase0包预分组: depth={}, {} 个组件, {} 个类跳过（过小分组）",
                bestDepth, components.size(), skippedClasses);
        return components;
    }

    /**
     * 获取包名的前N级前缀
     */
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

    /**
     * FSK组件拆分：检查每个FSK组件内的类是否跨越多个不同的顶层包前缀。
     * 如果是，按包前缀拆分为多个子组件。
     *
     * 这解决了小型项目中FSK过度聚合的问题——LLM可能把不同包的类错误地归入同一功能。
     */
    private List<RecoveredComponent> splitCrossPackageComponents(List<RecoveredComponent> components,
                                                                  Long projectId, Long recoveryResultId,
                                                                  int totalClassCount) {
        List<RecoveredComponent> result = new ArrayList<>();

        // 优化3: 小型项目(<100类)使用更激进的拆分参数
        int minSplitSize = totalClassCount < 100 ? 3 : 4;
        double splitThreshold = totalClassCount < 100 ? 0.6 : 0.8;

        for (RecoveredComponent comp : components) {
            List<String> classNames = getClassNames(comp);
            if (classNames.size() < minSplitSize) {
                result.add(comp);
                continue;
            }

            // 按2级包前缀分组
            Map<String, List<String>> byPrefix = new LinkedHashMap<>();
            for (String fqn : classNames) {
                int lastDot = fqn.lastIndexOf('.');
                String pkg = lastDot > 0 ? fqn.substring(0, lastDot) : "default";
                String prefix = getPackagePrefix(pkg, 2);
                byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(fqn);
            }

            // 如果只有1个前缀，不拆分
            if (byPrefix.size() <= 1) {
                result.add(comp);
                continue;
            }

            // 检查是否有明显的跨包情况
            int maxGroupSize = byPrefix.values().stream().mapToInt(List::size).max().orElse(0);
            if (maxGroupSize >= classNames.size() * splitThreshold) {
                result.add(comp); // 主要集中在一个包，不拆分
                continue;
            }

            // 拆分：每个包前缀一个子组件
            for (Map.Entry<String, List<String>> entry : byPrefix.entrySet()) {
                List<String> subClasses = entry.getValue();
                if (subClasses.isEmpty()) continue;

                RecoveredComponent subComp = new RecoveredComponent();
                subComp.setProjectId(projectId);
                subComp.setRecoveryResultId(recoveryResultId);
                subComp.setName(comp.getName() + "_" + entry.getKey());
                subComp.setLevel(comp.getLevel());
                subComp.setClassNames(String.join(",", subClasses));
                subComp.setSource(comp.getSource());
                result.add(subComp);
            }
        }

        return result;
    }
}
