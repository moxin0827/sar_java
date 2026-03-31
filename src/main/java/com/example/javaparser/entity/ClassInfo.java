package com.example.javaparser.entity;

import lombok.Data;

import javax.persistence.*;

@Data
@Entity
@Table(name = "class_info")
public class ClassInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 512)
    private String fullyQualifiedName;

    private String simpleName;

    private String packageName;

    private String classType;

    private String modifiers;

    @Column(length = 512)
    private String superClass;

    @Column(length = 2048)
    private String interfaces;

    @Column(length = 2048)
    private String annotations;

    @Column(columnDefinition = "TEXT")
    private String javadocComment;

    @Column(columnDefinition = "TEXT")
    private String methodNames;

    @Column(columnDefinition = "TEXT")
    private String fieldNames;

    @Column(columnDefinition = "TEXT")
    private String fieldDetails;

    @Column(columnDefinition = "TEXT")
    private String methodDetails;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String semanticEmbedding;

    @Column(length = 2048)
    private String functionalSummary;
}
