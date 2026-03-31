package com.example.javaparser.entity;

import lombok.Data;

import javax.persistence.*;

@Data
@Entity
@Table(name = "components")
public class RecoveredComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long recoveryResultId;

    @Column(nullable = false)
    private String name;

    private Long parentComponentId;

    private Integer level;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String classNames;

    private String source;
}
