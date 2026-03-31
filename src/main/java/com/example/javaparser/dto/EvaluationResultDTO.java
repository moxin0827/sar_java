package com.example.javaparser.dto;

import lombok.Data;

import java.util.List;

/**
 * 评价结果 DTO
 */
@Data
public class EvaluationResultDTO {
    private String algorithm;
    private Double mojoFM;
    private Double precision;
    private Double recall;
    private Double f1;
    private Double turboMQ;
    private Integer componentCount;
    private Integer groundTruthComponentCount;
    private Integer totalClasses;
    private Long timeMs;
    /** Phase0(包结构预分组)覆盖的GT类占比，>0.9时评估主要反映包结构质量而非聚类算法质量 */
    private Double phase0Coverage;

    /**
     * 多算法对比结果
     */
    @Data
    public static class CompareResultDTO {
        private String sessionId;
        private String groundTruthFile;
        private Integer totalClasses;
        private List<EvaluationResultDTO> results;
    }
}
