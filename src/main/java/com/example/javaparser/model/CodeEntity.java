package com.example.javaparser.model;

import lombok.Data;
import java.util.*;

/**
 * 代码实体 - 表示类、接口、枚举等
 */
@Data
public class CodeEntity {

    /**
     * 实体ID
     */
    private String id;

    /**
     * 实体名称
     */
    private String name;

    /**
     * 完全限定名
     */
    private String qualifiedName;

    /**
     * 包名
     */
    private String packageName;

    /**
     * 实体类型: CLASS, INTERFACE, ENUM, ABSTRACT_CLASS
     */
    private EntityType type;

    /**
     * 注释文本
     */
    private String comment;

    /**
     * 方法列表
     */
    private List<MethodInfo> methods = new ArrayList<>();

    /**
     * 字段列表
     */
    private List<FieldInfo> fields = new ArrayList<>();

    /**
     * 依赖的其他实体
     */
    private Set<String> dependencies = new HashSet<>();

    /**
     * 继承的父类
     */
    private String superClass;

    /**
     * 实现的接口
     */
    private Set<String> interfaces = new HashSet<>();

    /**
     * 注解列表
     */
    private List<String> annotations = new ArrayList<>();

    /**
     * 语义信息（由LLM提取）
     */
    private SemanticInfo semanticInfo;

    /**
     * 所属模块ID
     */
    private String moduleId;

    public enum EntityType {
        CLASS, INTERFACE, ENUM, ABSTRACT_CLASS
    }

    @Data
    public static class MethodInfo {
        private String name;
        private String returnType;
        private List<String> parameters;
        private String comment;
        private boolean isPublic;
        private boolean isStatic;
    }

    @Data
    public static class FieldInfo {
        private String name;
        private String type;
        private String comment;
        private boolean isPublic;
        private boolean isStatic;
        private boolean isFinal;
    }
}
