package com.example.javaparser.controller;

import com.example.javaparser.dto.RecoveryRequest;
import com.example.javaparser.dto.RecoveryResultDTO;
import com.example.javaparser.entity.Project;
import com.example.javaparser.repository.ProjectRepository;
import com.example.javaparser.service.JavaToUML2Service;
import com.example.javaparser.service.JavaToXMIService;
import com.example.javaparser.service.recovery.ArchitectureRecoveryOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Java源码解析控制器
 * 支持生成EMF格式和UML2.0格式的XMI文件
 */
@Slf4j
@RestController
@RequestMapping("/api/parser")
@CrossOrigin(origins = "*")
public class JavaParserController {

    @Autowired
    private JavaToXMIService javaToXMIService;

    @Autowired
    private JavaToUML2Service javaToUML2Service;

    @Autowired
    private com.example.javaparser.service.ComponentGenerationService componentGenerationService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ArchitectureRecoveryOrchestrator architectureRecoveryOrchestrator;

    private static final String TEMP_DIR = System.getProperty("user.dir") + File.separator + "tmp";
    private static final String UPLOAD_DIR = TEMP_DIR + File.separator + "java-uploads";
    private static final String OUTPUT_DIR = TEMP_DIR + File.separator + "xmi-outputs";

    /**
     * 上传Java源码ZIP并生成EMF格式XMI（原有功能）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndParse(@RequestParam("file") MultipartFile file) {
        return processUpload(file, false);
    }

    /**
     * 上传Java源码ZIP并生成UML 2.0格式XMI（新功能）
     */
    @PostMapping(value = "/upload/uml2", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndParseUML2(@RequestParam("file") MultipartFile file) {
        return processUpload(file, true);
    }

    /**
     * 上传Java源码ZIP并生成组件图（Component Diagram）
     */
    @PostMapping(value = "/upload/component", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndGenerateComponent(@RequestParam("file") MultipartFile file) {
        return processUploadForComponent(file);
    }

    /**
     * 上传Java源码ZIP，自动完成源码解析、FSK生成、架构恢复
     * 返回sessionId，可用于 /api/diagrams/{sessionId}/render 获取渲染图片
     */
    @PostMapping(value = "/upload/recovery", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndRecover(@RequestParam("file") MultipartFile file) {
        return processUploadForRecovery(file);
    }

    /**
     * 处理文件上传并生成XMI
     * @param file 上传的ZIP文件
     * @param useUML2 是否使用UML2.0格式
     */
    private ResponseEntity<?> processUpload(MultipartFile file, boolean useUML2) {
        try {
            log.info("接收到文件上传请求: {} (UML2模式: {})",
                    file.getOriginalFilename(), useUML2);

            // 验证文件
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "文件为空"));
            }

            if (!file.getOriginalFilename().endsWith(".zip")) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "只支持ZIP格式的文件"));
            }

            // 创建临时目录
            String sessionId = UUID.randomUUID().toString();
            File uploadDir = new File(UPLOAD_DIR, sessionId);
            File outputDir = new File(OUTPUT_DIR, sessionId);
            uploadDir.mkdirs();
            outputDir.mkdirs();

            // 保存上传的文件
            File zipFile = new File(uploadDir, file.getOriginalFilename());
            file.transferTo(zipFile);

            // 解压ZIP文件
            File extractDir = new File(uploadDir, "extracted");
            extractDir.mkdirs();
            unzip(zipFile, extractDir);

            log.info("文件解压完成: {}", extractDir.getAbsolutePath());

            // 查找Java源码根目录
            File sourceRoot = findSourceRoot(extractDir);
            if (sourceRoot == null) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "未找到Java源码目录"));
            }

            log.info("找到源码根目录: {}", sourceRoot.getAbsolutePath());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("sessionId", sessionId);
            response.put("format", useUML2 ? "UML2.0" : "EMF");

            if (useUML2) {
                // 使用UML2服务生成
                JavaToUML2Service.XMIGenerationResult result =
                        javaToUML2Service.parseAndGenerateXMI(
                                sourceRoot.getAbsolutePath(),
                                outputDir.getAbsolutePath()
                        );

                response.put("modelXMI", "model.uml");
                response.put("packageXMI", "package.uml");
                response.put("classXMI", "class.uml");
                response.put("totalPackages", result.getTotalPackages());
                response.put("totalClasses", result.getTotalClasses());

                log.info("UML2解析完成 - 包: {}, 类: {}",
                        result.getTotalPackages(), result.getTotalClasses());
            } else {
                // 使用原有EMF服务生成
                JavaToXMIService.XMIGenerationResult result =
                        javaToXMIService.parseAndGenerateXMI(
                                sourceRoot.getAbsolutePath(),
                                outputDir.getAbsolutePath()
                        );

                response.put("packageXMI", "package.xmi");
                response.put("classXMI", "class.xmi");
                response.put("totalPackages", result.getTotalPackages());
                response.put("totalClasses", result.getTotalClasses());

                log.info("EMF解析完成 - 包: {}, 类: {}",
                        result.getTotalPackages(), result.getTotalClasses());
            }

            response.put("message", "解析成功");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("文件处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "处理失败: " + e.getMessage()));
        }
    }

    /**
     * 下载生成的XMI文件
     */
    @GetMapping("/download/{sessionId}/{filename}")
    public ResponseEntity<Resource> downloadXMI(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        try {
            File outputDir = new File(OUTPUT_DIR, sessionId);
            File xmiFile = new File(outputDir, filename);

            if (!xmiFile.exists()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(xmiFile);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=" + filename);
            headers.setContentType(MediaType.APPLICATION_XML);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (Exception e) {
            log.error("下载文件失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 下载所有XMI文件（打包为ZIP）
     */
    @GetMapping("/download/{sessionId}/all")
    public ResponseEntity<Resource> downloadAllXMI(@PathVariable String sessionId) {
        try {
            File outputDir = new File(OUTPUT_DIR, sessionId);
            if (!outputDir.exists()) {
                return ResponseEntity.notFound().build();
            }

            // 创建ZIP文件
            File zipFile = new File(TEMP_DIR, sessionId + "-xmi.zip");
            createZipFromDirectory(outputDir, zipFile);

            Resource resource = new FileSystemResource(zipFile);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=xmi-files.zip");
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (Exception e) {
            log.error("创建ZIP文件失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 使用指定路径解析 - EMF格式
     */
    @PostMapping("/parse-path")
    public ResponseEntity<?> parsePath(@RequestBody Map<String, String> request) {
        return parsePathInternal(request, false);
    }

    /**
     * 使用指定路径解析 - UML2格式
     */
    @PostMapping("/parse-path/uml2")
    public ResponseEntity<?> parsePathUML2(@RequestBody Map<String, String> request) {
        return parsePathInternal(request, true);
    }

    /**
     * 内部路径解析方法
     */
    private ResponseEntity<?> parsePathInternal(Map<String, String> request, boolean useUML2) {
        try {
            String sourcePath = request.get("sourcePath");
            String outputPath = request.get("outputPath");

            if (sourcePath == null || outputPath == null) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "缺少sourcePath或outputPath参数"));
            }

            File sourceDir = new File(sourcePath);
            if (!sourceDir.exists() || !sourceDir.isDirectory()) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "源码目录不存在"));
            }

            File outputDir = new File(outputPath);
            outputDir.mkdirs();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("format", useUML2 ? "UML2.0" : "EMF");

            if (useUML2) {
                JavaToUML2Service.XMIGenerationResult result =
                        javaToUML2Service.parseAndGenerateXMI(sourcePath, outputPath);

                response.put("modelXMIPath", result.getModelXMIPath());
                response.put("packageXMIPath", result.getPackageXMIPath());
                response.put("classXMIPath", result.getClassXMIPath());
                response.put("totalPackages", result.getTotalPackages());
                response.put("totalClasses", result.getTotalClasses());
            } else {
                JavaToXMIService.XMIGenerationResult result =
                        javaToXMIService.parseAndGenerateXMI(sourcePath, outputPath);

                response.put("packageXMIPath", result.getPackageXMIPath());
                response.put("classXMIPath", result.getClassXMIPath());
                response.put("totalPackages", result.getTotalPackages());
                response.put("totalClasses", result.getTotalClasses());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("解析失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "解析失败: " + e.getMessage()));
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> healthInfo = new HashMap<>();
        healthInfo.put("status", "ok");
        healthInfo.put("service", "Java Parser with EMF, UML2.0 and Component Diagram Generation");
        healthInfo.put("supportedFormats", new String[]{"EMF", "UML2.0", "Component"});
        return ResponseEntity.ok(healthInfo);
    }

    /**
     * 处理组件图生成的文件上传
     */
    private ResponseEntity<?> processUploadForComponent(MultipartFile file) {
        try {
            log.info("接收到组件图生成请求: {}", file.getOriginalFilename());

            // 验证文件
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "文件为空"));
            }

            if (!file.getOriginalFilename().endsWith(".zip")) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "只支持ZIP格式的文件"));
            }

            // 创建临时目录
            String sessionId = UUID.randomUUID().toString();
            File uploadDir = new File(UPLOAD_DIR, sessionId);
            File outputDir = new File(OUTPUT_DIR, sessionId);
            uploadDir.mkdirs();
            outputDir.mkdirs();

            // 保存上传的文件
            File zipFile = new File(uploadDir, file.getOriginalFilename());
            file.transferTo(zipFile);

            // 解压ZIP文件
            File extractDir = new File(uploadDir, "extracted");
            extractDir.mkdirs();
            unzip(zipFile, extractDir);

            log.info("文件解压完成: {}", extractDir.getAbsolutePath());

            // 查找Java源码根目录
            File sourceRoot = findSourceRoot(extractDir);
            if (sourceRoot == null) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "未找到Java源码目录"));
            }

            log.info("找到源码根目录: {}", sourceRoot.getAbsolutePath());

            // 将sessionId和源码路径保存到Project表，供后续架构恢复复用
            Project project = new Project();
            project.setSessionId(sessionId);
            project.setName("Project-" + sessionId.substring(0, Math.min(8, sessionId.length())));
            project.setSourcePath(sourceRoot.getAbsolutePath());
            project.setStatus("UPLOADED");
            projectRepository.save(project);

            // 生成组件图
            com.example.javaparser.service.ComponentGenerationService.ComponentGenerationResult result =
                    componentGenerationService.generateComponentDiagram(
                            sourceRoot.getAbsolutePath(),
                            outputDir.getAbsolutePath()
                    );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("sessionId", sessionId);
            response.put("format", "Component");
            response.put("componentXMI", "component.xmi");
            response.put("totalEntities", result.getTotalEntities());
            response.put("totalModules", result.getTotalModules());
            response.put("totalComponents", result.getTotalComponents());
            response.put("processingTimeMs", result.getProcessingTimeMs());
            response.put("message", "组件图生成成功");

            log.info("组件图生成完成 - 实体: {}, 模块: {}, 组件: {}",
                    result.getTotalEntities(), result.getTotalModules(), result.getTotalComponents());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("组件图生成失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "处理失败: " + e.getMessage()));
        }
    }

    /**
     * 处理一键架构恢复的文件上传
     * 流程：解压ZIP → 解析源码 → 生成FSK → 架构恢复
     * 返回的sessionId可用于 GET /api/diagrams/{sessionId}/render?type=component&format=png 获取渲染图片
     */
    private ResponseEntity<?> processUploadForRecovery(MultipartFile file) {
        try {
            log.info("接收到一键架构恢复请求: {}", file.getOriginalFilename());

            // 验证文件
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "文件为空"));
            }

            if (!file.getOriginalFilename().endsWith(".zip")) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "只支持ZIP格式的文件"));
            }

            // 创建临时目录
            String sessionId = UUID.randomUUID().toString();
            File uploadDir = new File(UPLOAD_DIR, sessionId);
            uploadDir.mkdirs();

            // 创建输出目录（与其他上传流程保持一致）
            File outputDir = new File(OUTPUT_DIR, sessionId);
            outputDir.mkdirs();

            // 保存上传的文件
            File zipFile = new File(uploadDir, file.getOriginalFilename());
            file.transferTo(zipFile);

            // 解压ZIP文件
            File extractDir = new File(uploadDir, "extracted");
            extractDir.mkdirs();
            unzip(zipFile, extractDir);

            log.info("文件解压完成: {}", extractDir.getAbsolutePath());

            // 查找Java源码根目录
            File sourceRoot = findSourceRoot(extractDir);
            if (sourceRoot == null) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "未找到Java源码目录"));
            }

            log.info("找到源码根目录: {}", sourceRoot.getAbsolutePath());

            // 创建Project记录
            Project project = new Project();
            project.setSessionId(sessionId);
            project.setName("Project-" + sessionId.substring(0, Math.min(8, sessionId.length())));
            project.setSourcePath(sourceRoot.getAbsolutePath());
            project.setOutputPath(outputDir.getAbsolutePath());  // 新增：保存输出路径
            project.setStatus("UPLOADED");
            projectRepository.save(project);

            // 执行架构恢复（内部自动完成：源码解析 → FSK生成 → embedding → 架构恢复）
            RecoveryRequest request = new RecoveryRequest();
            RecoveryResultDTO result = architectureRecoveryOrchestrator.recover(
                project.getId(), request, outputDir.getAbsolutePath());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("sessionId", sessionId);
            response.put("projectId", project.getId());
            response.put("totalComponents", result.getTotalComponents());
            response.put("totalClasses", result.getTotalClasses());
            response.put("processingTimeMs", result.getProcessingTimeMs());
            response.put("components", result.getComponents());
            response.put("diagramUrl", "/api/diagrams/" + sessionId + "/render?type=component&format=png");
            response.put("message", "架构恢复完成");

            log.info("一键架构恢复完成 - sessionId={}, 组件: {}, 类: {}",
                    sessionId, result.getTotalComponents(), result.getTotalClasses());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("一键架构恢复失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "处理失败: " + e.getMessage()));
        }
    }

    /**
     * 使用指定路径生成组件图
     */
    @PostMapping("/parse-path/component")
    public ResponseEntity<?> parsePathComponent(@RequestBody Map<String, String> request) {
        try {
            String sourcePath = request.get("sourcePath");
            String outputPath = request.get("outputPath");

            if (sourcePath == null || outputPath == null) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "缺少sourcePath或outputPath参数"));
            }

            File sourceDir = new File(sourcePath);
            if (!sourceDir.exists() || !sourceDir.isDirectory()) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", "源码目录不存在"));
            }

            File outputDir = new File(outputPath);
            outputDir.mkdirs();

            // 生成组件图
            com.example.javaparser.service.ComponentGenerationService.ComponentGenerationResult result =
                    componentGenerationService.generateComponentDiagram(sourcePath, outputPath);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("format", "Component");
            response.put("componentXMIPath", result.getComponentXMIPath());
            response.put("totalEntities", result.getTotalEntities());
            response.put("totalModules", result.getTotalModules());
            response.put("totalComponents", result.getTotalComponents());
            response.put("processingTimeMs", result.getProcessingTimeMs());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("组件图生成失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "生成失败: " + e.getMessage()));
        }
    }

    /**
     * 解压ZIP文件
     */
    private void unzip(File zipFile, File destDir) throws IOException {
        byte[] buffer = new byte[1024];
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {

            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                String entryName = zipEntry.getName();

                // 跳过macOS资源分支文件（__MACOSX目录和._前缀文件）
                if (entryName.startsWith("__MACOSX/") || entryName.contains("/__MACOSX/")) {
                    zipEntry = zis.getNextEntry();
                    continue;
                }
                String fileName = entryName.contains("/")
                        ? entryName.substring(entryName.lastIndexOf('/') + 1)
                        : entryName;
                if (fileName.startsWith("._")) {
                    zipEntry = zis.getNextEntry();
                    continue;
                }

                File newFile = new File(destDir, entryName);

                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (java.io.FileOutputStream fos =
                                 new java.io.FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    /**
     * 查找Java源码根目录
     */
    private File findSourceRoot(File directory) {
        File srcDir = new File(directory, "src");
        if (srcDir.exists() && srcDir.isDirectory()) {
            File mainJava = new File(srcDir, "main/java");
            if (mainJava.exists() && mainJava.isDirectory()) {
                return mainJava;
            }
            return srcDir;
        }

        if (containsJavaFiles(directory)) {
            return directory;
        }

        File[] subdirs = directory.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                File result = findSourceRoot(subdir);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 检查目录是否包含Java文件
     */
    private boolean containsJavaFiles(File directory) {
        try {
            return Files.walk(directory.toPath())
                    .anyMatch(path -> path.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 创建ZIP文件
     */
    private void createZipFromDirectory(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new java.io.FileOutputStream(zipFile))) {

            Files.walk(sourceDir.toPath())
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        ZipEntry zipEntry = new ZipEntry(
                                sourceDir.toPath().relativize(path).toString()
                        );
                        try {
                            zos.putNextEntry(zipEntry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            log.error("添加文件到ZIP失败: {}", path, e);
                        }
                    });
        }
    }
}