package com.example.javaparser.entity;

import lombok.Data;

import javax.persistence.*;

@Data
@Entity
@Table(name = "class_relations")
public class ClassRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 512)
    private String sourceClassName;

    @Column(nullable = false, length = 512)
    private String targetClassName;

    @Column(nullable = false)
    private String relationType;

    private String sourceMultiplicity;

    private String targetMultiplicity;

    @Column(length = 256)
    private String associationName;
}
