package com.example.javaparser.controller;

import com.example.javaparser.dto.RecoveryRequest;
import com.example.javaparser.dto.RecoveryResultDTO;
import com.example.javaparser.entity.FunctionKnowledge;
import com.example.javaparser.entity.Project;
import com.example.javaparser.repository.FunctionKnowledgeRepository;
import com.example.javaparser.repository.ProjectRepository;
import com.example.javaparser.service.SourceCodePersistService;
import com.example.javaparser.service.llm.FskGenerationService;
import com.example.javaparser.service.recovery.ArchitectureRecoveryOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/recovery")
public class RecoveryController {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SourceCodePersistService sourceCodePersistService;

    @Autowired
    private FskGenerationService fskGenerationService;

    @Autowired
    private FunctionKnowledgeRepository functionKnowledgeRepository;

    @Autowired
    private ArchitectureRecoveryOrchestrator orchestrator;

    /**
     * 触发源码解析并持久化
     */
    @PostMapping("/{sessionId}/parse")
    public ResponseEntity<?> parse(@PathVariable String sessionId,
                                   @RequestBody(required = false) Map<String, String> body) {
        try {
            Project project = getOrCreateProject(sessionId);

            String sourcePath = body != null ? body.get("sourcePath") : null;
            if (sourcePath != null) {
                project.setSourcePath(sourcePath);
            }

            // 创建输出目录
            String outputDir = System.getProperty("user.dir") + "/tmp/xmi-outputs/" + sessionId;
            new java.io.File(outputDir).mkdirs();
            project.setOutputPath(outputDir);

            projectRepository.save(project);

            // 如果没有sourcePath，尝试从先前上传的临时目录中查找源码
            if (project.getSourcePath() == null) {
                String uploadDir = System.getProperty("user.dir") + "/tmp/java-uploads/" + sessionId + "/extracted";
                java.io.File extractDir = new java.io.File(uploadDir);
                if (extractDir.exists() && extractDir.isDirectory()) {
                    java.io.File sourceRoot = findSourceRoot(extractDir);
                    if (sourceRoot != null) {
                        project.setSourcePath(sourceRoot.getAbsolutePath());
                        projectRepository.save(project);
                        log.info("自动从上传目录找到源码: {}", sourceRoot.getAbsolutePath());
                    }
                }
            }

            if (project.getSourcePath() == null) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "sourcePath is required, and no previously uploaded source found for this sessionId"));
            }

            sourceCodePersistService.parseAndPersist(project.getId(), project.getSourcePath(), project.getOutputPath());
            project.setStatus("PARSED");
            projectRepository.save(project);

            return ResponseEntity.ok(Map.of(
                    "projectId", project.getId(),
                    "sessionId", sessionId,
                    "status", "PARSED"
            ));
        } catch (Exception e) {
            log.error("解析失败: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 生成FSK
     */
    @PostMapping("/{sessionId}/fsk/generate")
    public ResponseEntity<?> generateFsk(@PathVariable String sessionId) {
        try {
            Project project = findProject(sessionId);
            if (project == null) {
                return ResponseEntity.notFound().build();
            }

            List<FunctionKnowledge> fskNodes = fskGenerationService.generateFsk(
                    project.getId(), project.getName());

            return ResponseEntity.ok(Map.of(
                    "projectId", project.getId(),
                    "fskNodeCount", fskNodes.size(),
                    "nodes", fskNodes
            ));
        } catch (Exception e) {
            log.error("FSK生成失败: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取FSK数据
     */
    @GetMapping("/{sessionId}/fsk")
    public ResponseEntity<?> getFsk(@PathVariable String sessionId) {
        Project project = findProject(sessionId);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }

        List<FunctionKnowledge> nodes = functionKnowledgeRepository.findByProjectId(project.getId());
        return ResponseEntity.ok(nodes);
    }

    /**
     * 编辑FSK条目
     */
    @PutMapping("/{sessionId}/fsk/{fskId}")
    public ResponseEntity<?> editFsk(@PathVariable String sessionId,
                                     @PathVariable Long fskId,
                                     @RequestBody FunctionKnowledge update) {
        Project project = findProject(sessionId);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }

        java.util.Optional<FunctionKnowledge> opt = functionKnowledgeRepository.findById(fskId);
        if (!opt.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        FunctionKnowledge existing = opt.get();
        if (!existing.getProjectId().equals(project.getId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "FSK node does not belong to this project"));
        }
        if (update.getFunctionName() != null) existing.setFunctionName(update.getFunctionName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getRelatedClassNames() != null) existing.setRelatedClassNames(update.getRelatedClassNames());
        if (update.getRelatedTerms() != null) existing.setRelatedTerms(update.getRelatedTerms());
        existing.setSource("MANUAL");
        functionKnowledgeRepository.save(existing);
        return ResponseEntity.ok(existing);
    }

    /**
     * 执行架构恢复
     */
    @PostMapping("/{sessionId}/recover")
    public ResponseEntity<?> recover(@PathVariable String sessionId,
                                     @RequestBody(required = false) RecoveryRequest request) {
        try {
            Project project = findProject(sessionId);
            if (project == null) {
                return ResponseEntity.notFound().build();
            }

            if (request == null) {
                request = new RecoveryRequest();
            }

            RecoveryResultDTO result = orchestrator.recover(project.getId(), request, project.getOutputPath());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("架构恢复失败: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取恢复结果
     */
    @GetMapping("/{sessionId}/result")
    public ResponseEntity<?> getResult(@PathVariable String sessionId) {
        Project project = findProject(sessionId);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }

        RecoveryResultDTO result = orchestrator.getLatestResult(project.getId());
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    private Project getOrCreateProject(String sessionId) {
        return projectRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    Project p = new Project();
                    p.setSessionId(sessionId);
                    p.setName("Project-" + sessionId.substring(0, Math.min(8, sessionId.length())));
                    return projectRepository.save(p);
                });
    }

    private Project findProject(String sessionId) {
        return projectRepository.findBySessionId(sessionId).orElse(null);
    }

    /**
     * 查找Java源码根目录（与JavaParserController逻辑一致）
     */
    private java.io.File findSourceRoot(java.io.File directory) {
        java.io.File srcDir = new java.io.File(directory, "src");
        if (srcDir.exists() && srcDir.isDirectory()) {
            java.io.File mainJava = new java.io.File(srcDir, "main/java");
            if (mainJava.exists() && mainJava.isDirectory()) {
                return mainJava;
            }
            return srcDir;
        }

        if (containsJavaFiles(directory)) {
            return directory;
        }

        java.io.File[] subdirs = directory.listFiles(java.io.File::isDirectory);
        if (subdirs != null) {
            for (java.io.File subdir : subdirs) {
                java.io.File result = findSourceRoot(subdir);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    private boolean containsJavaFiles(java.io.File directory) {
        try {
            return java.nio.file.Files.walk(directory.toPath())
                    .anyMatch(path -> path.toString().endsWith(".java"));
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
