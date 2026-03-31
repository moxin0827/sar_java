package com.example.javaparser.service;

import com.example.javaparser.component.ArchitectureRecoveryService;
import com.example.javaparser.component.ComponentDiagramService;
import com.example.javaparser.component.FunctionalKnowledgeBaseBuilder;
import com.example.javaparser.component.PreprocessingService;
import com.example.javaparser.model.FunctionalKnowledgeBase;
import com.example.javaparser.model.KnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

/**
 * 组件图生成服务 - 整合预处理、功能结构知识库构建、架构恢复和组件图生成
 */
@Slf4j
@Service
public class ComponentGenerationService {

    @Autowired
    private PreprocessingService preprocessingService;

    @Autowired
    private FunctionalKnowledgeBaseBuilder functionalKnowledgeBaseBuilder;

    @Autowired
    private ArchitectureRecoveryService architectureRecoveryService;

    @Autowired
    private ComponentDiagramService componentDiagramService;

    /**
     * 完整的组件图生成流程
     *
     * @param sourceDirectory 源码目录
     * @param outputDirectory 输出目录
     * @return 生成结果
     */
    public ComponentGenerationResult generateComponentDiagram(String sourceDirectory,
                                                              String outputDirectory) throws IOException {
        log.info("开始完整的组件图生成流程...");
        log.info("源码目录: {}", sourceDirectory);
        log.info("输出目录: {}", outputDirectory);

        File sourceDir = new File(sourceDirectory);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new IllegalArgumentException("无效的源码目录: " + sourceDirectory);
        }

        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        long startTime = System.currentTimeMillis();

        // 步骤1: 预处理 - 解析源码并提取语义信息
        log.info("=== 步骤1: 预处理 ===");
        KnowledgeBase knowledgeBase = preprocessingService.preprocessProject(sourceDirectory);
        log.info("预处理完成，共 {} 个实体", knowledgeBase.getEntities().size());

        // 步骤2: 构建功能结构知识库 - 利用LLM构建功能层级、术语表、功能约束等
        log.info("=== 步骤2: 构建功能结构知识库 ===");
        FunctionalKnowledgeBase functionalKB = functionalKnowledgeBaseBuilder.buildFunctionalKnowledgeBase(knowledgeBase);
        log.info("功能结构知识库构建完成");
        log.info("- 功能层级节点: {}", functionalKB.getFunctionalHierarchy().getNodeMap().size());
        log.info("- 术语表条目: {}", functionalKB.getGlossary().size());
        log.info("- 功能块: {}", functionalKB.getFunctionalBlocks().size());
        log.info("- 功能约束: {}", functionalKB.getConstraints().size());
        log.info("- 业务规则: {}", functionalKB.getBusinessRules().size());

        // 步骤3: 架构恢复 - 识别功能模块和依赖关系
        log.info("=== 步骤3: 架构恢复 ===");
        architectureRecoveryService.recoverArchitecture(knowledgeBase);
        log.info("架构恢复完成，共 {} 个模块", knowledgeBase.getModules().size());

        // 步骤4: 组件图生成 - 创建UML组件图并导出XMI
        log.info("=== 步骤4: 组件图生成 ===");
        ComponentDiagramService.ComponentGenerationResult diagramResult =
                componentDiagramService.generateComponentDiagram(knowledgeBase, outputDirectory);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // 构建结果
        ComponentGenerationResult result = new ComponentGenerationResult();
        result.setComponentXMIPath(diagramResult.getComponentXMIPath());
        result.setTotalEntities(knowledgeBase.getEntities().size());
        result.setTotalModules(knowledgeBase.getModules().size());
        result.setTotalComponents(diagramResult.getTotalComponents());
        result.setProcessingTimeMs(duration);
        result.setKnowledgeBase(knowledgeBase);
        result.setFunctionalKnowledgeBase(functionalKB);

        log.info("组件图生成完成！");
        log.info("- 总实体数: {}", result.getTotalEntities());
        log.info("- 总模块数: {}", result.getTotalModules());
        log.info("- 总组件数: {}", result.getTotalComponents());
        log.info("- 处理时间: {} ms", duration);

        return result;
    }

    /**
     * 组件图生成结果
     */
    public static class ComponentGenerationResult {
        private String componentXMIPath;
        private int totalEntities;
        private int totalModules;
        private int totalComponents;
        private long processingTimeMs;
        private KnowledgeBase knowledgeBase;
        private FunctionalKnowledgeBase functionalKnowledgeBase;

        public String getComponentXMIPath() {
            return componentXMIPath;
        }

        public void setComponentXMIPath(String componentXMIPath) {
            this.componentXMIPath = componentXMIPath;
        }

        public int getTotalEntities() {
            return totalEntities;
        }

        public void setTotalEntities(int totalEntities) {
            this.totalEntities = totalEntities;
        }

        public int getTotalModules() {
            return totalModules;
        }

        public void setTotalModules(int totalModules) {
            this.totalModules = totalModules;
        }

        public int getTotalComponents() {
            return totalComponents;
        }

        public void setTotalComponents(int totalComponents) {
            this.totalComponents = totalComponents;
        }

        public long getProcessingTimeMs() {
            return processingTimeMs;
        }

        public void setProcessingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
        }

        public KnowledgeBase getKnowledgeBase() {
            return knowledgeBase;
        }

        public void setKnowledgeBase(KnowledgeBase knowledgeBase) {
            this.knowledgeBase = knowledgeBase;
        }

        public FunctionalKnowledgeBase getFunctionalKnowledgeBase() {
            return functionalKnowledgeBase;
        }

        public void setFunctionalKnowledgeBase(FunctionalKnowledgeBase functionalKnowledgeBase) {
            this.functionalKnowledgeBase = functionalKnowledgeBase;
        }
    }
}
