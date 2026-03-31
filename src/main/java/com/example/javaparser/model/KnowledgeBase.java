package com.example.javaparser.model;

import lombok.Data;
import java.util.*;

/**
 * 功能结构知识库
 */
@Data
public class KnowledgeBase {

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 所有代码实体
     */
    private Map<String, CodeEntity> entities = new HashMap<>();

    /**
     * 所有功能模块
     */
    private Map<String, FunctionalModule> modules = new HashMap<>();

    /**
     * 术语表
     */
    private Map<String, TermDefinition> glossary = new HashMap<>();

    /**
     * 依赖关系图
     */
    private DependencyGraph dependencyGraph = new DependencyGraph();

    /**
     * 架构约束
     */
    private List<ArchitectureConstraint> constraints = new ArrayList<>();

    @Data
    public static class TermDefinition {
        private String term;
        private String definition;
        private String category;
        private List<String> relatedTerms = new ArrayList<>();
    }

    @Data
    public static class ArchitectureConstraint {
        private String name;
        private String description;
        private ConstraintType type;

        public enum ConstraintType {
            LAYERING,           // 分层约束
            DEPENDENCY,         // 依赖约束
            NAMING_CONVENTION,  // 命名约定
            PATTERN            // 模式约束
        }
    }

    @Data
    public static class DependencyGraph {
        private Map<String, Set<String>> edges = new HashMap<>();

        public void addDependency(String from, String to) {
            edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        }

        public Set<String> getDependencies(String entity) {
            return edges.getOrDefault(entity, new HashSet<>());
        }
    }
}
