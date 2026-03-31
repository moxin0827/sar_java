package com.example.javaparser.entity;

import lombok.Data;

import javax.persistence.*;

@Data
@Entity
@Table(name = "function_knowledge")
public class FunctionKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String functionName;

    @Column(length = 2048)
    private String description;

    @Column(length = 2048)
    private String relatedTerms;

    private Long parentFunctionId;

    @Column(columnDefinition = "TEXT")
    private String relatedClassNames;

    private Boolean isLeaf;

    private String source;
}
