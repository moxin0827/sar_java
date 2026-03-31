package com.example.javaparser.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recovery_results")
public class RecoveryResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    private Double structWeight;

    private Double semanticWeight;

    private Double threshold;

    private Integer totalComponents;

    private Integer totalClasses;

    private Long processingTimeMs;

    /** 使用的聚类算法 */
    private String clusteringAlgorithm;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
