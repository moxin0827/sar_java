package com.example.javaparser.controller;

import com.example.javaparser.dto.EvaluationResultDTO;
import com.example.javaparser.entity.Project;
import com.example.javaparser.repository.ProjectRepository;
import com.example.javaparser.service.evaluation.EvaluationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评价 API 端点
 */
@Slf4j
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private ProjectRepository projectRepository;

    /**
     * 评价已有恢复结果
     *
     * POST /api/evaluation/run
     * Body: { "sessionId": "xxx", "groundTruthFile": "jpetstore.json" }
     */
    @PostMapping("/run")
    public ResponseEntity<?> evaluate(@RequestBody Map<String, String> body) {
        try {
            String sessionId = body.get("sessionId");
            String groundTruthFile = body.get("groundTruthFile");

            if (sessionId == null || groundTruthFile == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "sessionId 和 groundTruthFile 为必填参数"));
            }

            Project project = projectRepository.findBySessionId(sessionId).orElse(null);
            if (project == null) {
                return ResponseEntity.notFound().build();
            }

            EvaluationResultDTO result = evaluationService.evaluate(
                    project.getId(), groundTruthFile);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("评价失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 多算法对比评价
     *
     * POST /api/evaluation/compare
     * Body: {
     *   "sessionId": "xxx",
     *   "groundTruthFile": "jpetstore.json",
     *   "algorithms": ["AFFINITY_PROPAGATION", "AGGLOMERATIVE", "DBSCAN", "SPECTRAL", "KMEANS_SILHOUETTE"]
     * }
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/compare")
    public ResponseEntity<?> compare(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = (String) body.get("sessionId");
            String groundTruthFile = (String) body.get("groundTruthFile");
            List<String> algorithms = (List<String>) body.get("algorithms");

            if (sessionId == null || groundTruthFile == null || algorithms == null || algorithms.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "sessionId, groundTruthFile 和 algorithms 为必填参数"));
            }

            Project project = projectRepository.findBySessionId(sessionId).orElse(null);
            if (project == null) {
                return ResponseEntity.notFound().build();
            }

            String outputDir = project.getOutputPath();

            EvaluationResultDTO.CompareResultDTO result = evaluationService.compare(
                    project.getId(), groundTruthFile, algorithms, outputDir);
            result.setSessionId(sessionId);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("对比评价失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
