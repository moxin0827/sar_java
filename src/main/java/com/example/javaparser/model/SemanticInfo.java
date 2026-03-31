package com.example.javaparser.model;

import lombok.Data;
import java.util.*;

/**
 * 语义信息 - 由LLM提取的功能语义
 */
@Data
public class SemanticInfo {

    /**
     * 功能描述
     */
    private String functionalDescription;

    /**
     * 业务领域
     */
    private String businessDomain;

    /**
     * 关键术语
     */
    private List<String> keyTerms = new ArrayList<>();

    /**
     * 功能标签
     */
    private List<String> functionalTags = new ArrayList<>();

    /**
     * 架构层次: PRESENTATION, SERVICE, DOMAIN, INFRASTRUCTURE, UTILITY
     */
    private ArchitectureLayer architectureLayer;

    /**
     * 设计模式
     */
    private List<String> designPatterns = new ArrayList<>();

    /**
     * 职责描述
     */
    private String responsibility;

    /**
     * 相关性分数（与其他实体的语义相关性）
     */
    private Map<String, Double> semanticSimilarity = new HashMap<>();

    public enum ArchitectureLayer {
        PRESENTATION,    // 表示层
        SERVICE,         // 服务层
        DOMAIN,          // 领域层
        INFRASTRUCTURE,  // 基础设施层
        UTILITY,         // 工具层
        UNKNOWN          // 未知
    }
}
