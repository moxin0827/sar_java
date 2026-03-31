package com.example.javaparser.model;

import java.util.*;

/**
 * 功能结构知识库
 * 包含功能层级、术语表、功能约束等信息
 */
public class FunctionalKnowledgeBase {

    /**
     * 功能层级结构
     */
    private FunctionalHierarchy functionalHierarchy;

    /**
     * 术语表（领域术语及其定义）
     */
    private Map<String, TermDefinition> glossary;

    /**
     * 功能约束（功能之间的约束关系）
     */
    private List<FunctionalConstraint> constraints;

    /**
     * 功能块映射（代码实体到功能块的映射）
     */
    private Map<String, FunctionalBlock> functionalBlocks;

    /**
     * 业务规则
     */
    private List<BusinessRule> businessRules;

    /**
     * 功能依赖图
     */
    private Map<String, Set<String>> functionalDependencies;

    public FunctionalKnowledgeBase() {
        this.glossary = new HashMap<>();
        this.constraints = new ArrayList<>();
        this.functionalBlocks = new HashMap<>();
        this.businessRules = new ArrayList<>();
        this.functionalDependencies = new HashMap<>();
    }

    /**
     * 功能层级结构
     */
    public static class FunctionalHierarchy {
        private FunctionalNode root;
        private Map<String, FunctionalNode> nodeMap;

        public FunctionalHierarchy() {
            this.nodeMap = new HashMap<>();
        }

        public FunctionalNode getRoot() {
            return root;
        }

        public void setRoot(FunctionalNode root) {
            this.root = root;
        }

        public Map<String, FunctionalNode> getNodeMap() {
            return nodeMap;
        }

        public void setNodeMap(Map<String, FunctionalNode> nodeMap) {
            this.nodeMap = nodeMap;
        }

        public void addNode(String parentId, FunctionalNode node) {
            nodeMap.put(node.getId(), node);
            if (parentId != null && nodeMap.containsKey(parentId)) {
                FunctionalNode parent = nodeMap.get(parentId);
                parent.getChildren().add(node);
                node.setParent(parent);
            }
        }
    }

    /**
     * 功能节点
     */
    public static class FunctionalNode {
        private String id;
        private String name;
        private String description;
        private FunctionalLevel level;
        private FunctionalNode parent;
        private List<FunctionalNode> children;
        private Set<String> relatedEntities;
        private Map<String, String> attributes;

        public FunctionalNode() {
            this.children = new ArrayList<>();
            this.relatedEntities = new HashSet<>();
            this.attributes = new HashMap<>();
        }

        public enum FunctionalLevel {
            SYSTEM,      // 系统级
            SUBSYSTEM,   // 子系统级
            MODULE,      // 模块级
            COMPONENT,   // 组件级
            FEATURE      // 特性级
        }

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public FunctionalLevel getLevel() {
            return level;
        }

        public void setLevel(FunctionalLevel level) {
            this.level = level;
        }

        public FunctionalNode getParent() {
            return parent;
        }

        public void setParent(FunctionalNode parent) {
            this.parent = parent;
        }

        public List<FunctionalNode> getChildren() {
            return children;
        }

        public void setChildren(List<FunctionalNode> children) {
            this.children = children;
        }

        public Set<String> getRelatedEntities() {
            return relatedEntities;
        }

        public void setRelatedEntities(Set<String> relatedEntities) {
            this.relatedEntities = relatedEntities;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
        }
    }

    /**
     * 术语定义
     */
    public static class TermDefinition {
        private String term;
        private String definition;
        private String category;
        private List<String> synonyms;
        private List<String> relatedTerms;
        private String source;

        public TermDefinition() {
            this.synonyms = new ArrayList<>();
            this.relatedTerms = new ArrayList<>();
        }

        // Getters and Setters
        public String getTerm() {
            return term;
        }

        public void setTerm(String term) {
            this.term = term;
        }

        public String getDefinition() {
            return definition;
        }

        public void setDefinition(String definition) {
            this.definition = definition;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public List<String> getSynonyms() {
            return synonyms;
        }

        public void setSynonyms(List<String> synonyms) {
            this.synonyms = synonyms;
        }

        public List<String> getRelatedTerms() {
            return relatedTerms;
        }

        public void setRelatedTerms(List<String> relatedTerms) {
            this.relatedTerms = relatedTerms;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }
    }

    /**
     * 功能约束
     */
    public static class FunctionalConstraint {
        private String id;
        private ConstraintType type;
        private String source;
        private String target;
        private String description;
        private double strength;

        public enum ConstraintType {
            REQUIRES,        // 需要
            EXCLUDES,        // 互斥
            OPTIONAL,        // 可选
            MANDATORY,       // 强制
            CONDITIONAL,     // 条件
            SEQUENCE         // 顺序
        }

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public ConstraintType getType() {
            return type;
        }

        public void setType(ConstraintType type) {
            this.type = type;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public double getStrength() {
            return strength;
        }

        public void setStrength(double strength) {
            this.strength = strength;
        }
    }

    /**
     * 功能块
     */
    public static class FunctionalBlock {
        private String id;
        private String name;
        private String functionalDescription;
        private String businessPurpose;
        private Set<String> entities;
        private Set<String> capabilities;
        private Map<String, String> interfaces;
        private String hierarchyNodeId;

        public FunctionalBlock() {
            this.entities = new HashSet<>();
            this.capabilities = new HashSet<>();
            this.interfaces = new HashMap<>();
        }

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFunctionalDescription() {
            return functionalDescription;
        }

        public void setFunctionalDescription(String functionalDescription) {
            this.functionalDescription = functionalDescription;
        }

        public String getBusinessPurpose() {
            return businessPurpose;
        }

        public void setBusinessPurpose(String businessPurpose) {
            this.businessPurpose = businessPurpose;
        }

        public Set<String> getEntities() {
            return entities;
        }

        public void setEntities(Set<String> entities) {
            this.entities = entities;
        }

        public Set<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(Set<String> capabilities) {
            this.capabilities = capabilities;
        }

        public Map<String, String> getInterfaces() {
            return interfaces;
        }

        public void setInterfaces(Map<String, String> interfaces) {
            this.interfaces = interfaces;
        }

        public String getHierarchyNodeId() {
            return hierarchyNodeId;
        }

        public void setHierarchyNodeId(String hierarchyNodeId) {
            this.hierarchyNodeId = hierarchyNodeId;
        }
    }

    /**
     * 业务规则
     */
    public static class BusinessRule {
        private String id;
        private String name;
        private String description;
        private RuleType type;
        private String condition;
        private String action;
        private Set<String> affectedEntities;

        public enum RuleType {
            VALIDATION,      // 验证规则
            CALCULATION,     // 计算规则
            INFERENCE,       // 推理规则
            CONSTRAINT,      // 约束规则
            WORKFLOW         // 工作流规则
        }

        public BusinessRule() {
            this.affectedEntities = new HashSet<>();
        }

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public RuleType getType() {
            return type;
        }

        public void setType(RuleType type) {
            this.type = type;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public Set<String> getAffectedEntities() {
            return affectedEntities;
        }

        public void setAffectedEntities(Set<String> affectedEntities) {
            this.affectedEntities = affectedEntities;
        }
    }

    // Main class Getters and Setters
    public FunctionalHierarchy getFunctionalHierarchy() {
        return functionalHierarchy;
    }

    public void setFunctionalHierarchy(FunctionalHierarchy functionalHierarchy) {
        this.functionalHierarchy = functionalHierarchy;
    }

    public Map<String, TermDefinition> getGlossary() {
        return glossary;
    }

    public void setGlossary(Map<String, TermDefinition> glossary) {
        this.glossary = glossary;
    }

    public List<FunctionalConstraint> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<FunctionalConstraint> constraints) {
        this.constraints = constraints;
    }

    public Map<String, FunctionalBlock> getFunctionalBlocks() {
        return functionalBlocks;
    }

    public void setFunctionalBlocks(Map<String, FunctionalBlock> functionalBlocks) {
        this.functionalBlocks = functionalBlocks;
    }

    public List<BusinessRule> getBusinessRules() {
        return businessRules;
    }

    public void setBusinessRules(List<BusinessRule> businessRules) {
        this.businessRules = businessRules;
    }

    public Map<String, Set<String>> getFunctionalDependencies() {
        return functionalDependencies;
    }

    public void setFunctionalDependencies(Map<String, Set<String>> functionalDependencies) {
        this.functionalDependencies = functionalDependencies;
    }
}
