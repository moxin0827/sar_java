package com.example.javaparser.dto;

import lombok.Data;

import java.util.List;

@Data
public class RecoveryResultDTO {
    private Long resultId;
    private Long projectId;
    private Integer totalComponents;
    private Integer totalClasses;
    private Long processingTimeMs;
    private String clusteringAlgorithm;
    private List<ComponentDTO> components;

    @Data
    public static class ComponentDTO {
        private Long id;
        private String name;
        private Long parentComponentId;
        private Integer level;
        private List<String> classNames;
        private String source;
    }
}
