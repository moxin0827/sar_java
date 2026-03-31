package com.example.javaparser.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 组件图生成配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "component")
public class ComponentConfig {

    private ClusteringConfig clustering = new ClusteringConfig();
    private ModuleDetectionConfig moduleDetection = new ModuleDetectionConfig();

    @Data
    public static class ClusteringConfig {
        /**
         * 最小聚类大小
         */
        private Integer minClusterSize = 3;

        /**
         * 最大聚类大小
         */
        private Integer maxClusterSize = 20;

        /**
         * 相似度阈值
         */
        private Double similarityThreshold = 0.6;
    }

    @Data
    public static class ModuleDetectionConfig {
        /**
         * 使用包结构
         */
        private Boolean usePackageStructure = true;

        /**
         * 使用命名约定
         */
        private Boolean useNamingConvention = true;

        /**
         * 使用依赖分析
         */
        private Boolean useDependencyAnalysis = true;

        /**
         * 使用LLM语义分析
         */
        private Boolean useLlmSemantic = true;
    }
}
