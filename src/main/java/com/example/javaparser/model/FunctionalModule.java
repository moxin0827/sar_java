package com.example.javaparser.model;

import lombok.Data;
import java.util.*;

/**
 * 功能模块 - 表示组件图中的一个组件
 */
@Data
public class FunctionalModule {

    /**
     * 模块ID
     */
    private String id;

    /**
     * 模块名称
     */
    private String name;

    /**
     * 模块描述
     */
    private String description;

    /**
     * 包含的代码实体
     */
    private Set<String> entities = new HashSet<>();

    /**
     * 依赖的其他模块
     */
    private Set<String> dependencies = new HashSet<>();

    /**
     * 被依赖的模块
     */
    private Set<String> dependents = new HashSet<>();

    /**
     * 模块类型: COMPONENT, SUBSYSTEM, LAYER
     */
    private ModuleType type = ModuleType.COMPONENT;

    /**
     * 架构层次
     */
    private SemanticInfo.ArchitectureLayer layer;

    /**
     * 功能标签
     */
    private List<String> functionalTags = new ArrayList<>();

    /**
     * 关键术语
     */
    private Set<String> keyTerms = new HashSet<>();

    /**
     * 内聚度分数 (0-1)
     */
    private Double cohesion;

    /**
     * 耦合度分数 (0-1)
     */
    private Double coupling;

    /**
     * 优化建议
     */
    private String optimizationSuggestion;

    public enum ModuleType {
        COMPONENT,   // 组件
        SUBSYSTEM,   // 子系统
        LAYER        // 层
    }
}
