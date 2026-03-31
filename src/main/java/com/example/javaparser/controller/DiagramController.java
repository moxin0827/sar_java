package com.example.javaparser.controller;

import com.example.javaparser.entity.Project;
import com.example.javaparser.repository.ProjectRepository;
import com.example.javaparser.service.diagram.ClassDiagramGenerator;
import com.example.javaparser.service.diagram.ComponentDiagramGenerator;
import com.example.javaparser.service.diagram.PackageDiagramGenerator;
import com.example.javaparser.service.diagram.PlantUmlRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/diagrams")
public class DiagramController {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PackageDiagramGenerator packageDiagramGenerator;

    @Autowired
    private ClassDiagramGenerator classDiagramGenerator;

    @Autowired
    private ComponentDiagramGenerator componentDiagramGenerator;

    @Autowired
    private PlantUmlRenderer plantUmlRenderer;

    /**
     * 获取包图PlantUML文本
     * @param maxDepth 包层级深度（0=不折叠，默认0）
     */
    @GetMapping("/{sessionId}/package")
    public ResponseEntity<?> packageDiagram(@PathVariable String sessionId,
                                            @RequestParam(defaultValue = "0") int maxDepth) {
        Project project = findProject(sessionId);
        if (project == null) return ResponseEntity.notFound().build();

        String puml = packageDiagramGenerator.generate(project.getId(), maxDepth);
        return ResponseEntity.ok(Map.of("plantuml", puml));
    }

    /**
     * 获取类图PlantUML文本
     * @param packageFilter 包名前缀过滤（可选）
     * @param componentName 按组件名过滤（可选）
     * @param maxClasses    最大类数量（默认50）
     */
    @GetMapping("/{sessionId}/class")
    public ResponseEntity<?> classDiagram(@PathVariable String sessionId,
                                          @RequestParam(required = false) String packageFilter,
                                          @RequestParam(required = false) String componentName,
                                          @RequestParam(defaultValue = "50") int maxClasses) {
        Project project = findProject(sessionId);
        if (project == null) return ResponseEntity.notFound().build();

        String puml = classDiagramGenerator.generate(project.getId(), packageFilter, componentName, maxClasses);
        return ResponseEntity.ok(Map.of("plantuml", puml));
    }

    /**
     * 获取组件图PlantUML文本
     * @param source         按来源过滤: FSK / CLUSTERING / ALL（可选）
     * @param layer          按架构层过滤: Controller / Service / Repository / Entity（可选）
     * @param componentName  按组件名模糊匹配（可选）
     * @param maxComponents  最大组件数量（0=不限制，默认0）
     * @param showInterfaces 是否显示接口建模（默认false）
     */
    @GetMapping("/{sessionId}/component")
    public ResponseEntity<?> componentDiagram(@PathVariable String sessionId,
                                              @RequestParam(required = false) String source,
                                              @RequestParam(required = false) String layer,
                                              @RequestParam(required = false) String componentName,
                                              @RequestParam(defaultValue = "0") int maxComponents,
                                              @RequestParam(defaultValue = "false") boolean showInterfaces) {
        Project project = findProject(sessionId);
        if (project == null) return ResponseEntity.notFound().build();

        String puml = componentDiagramGenerator.generate(project.getId(),
                source, layer, componentName, maxComponents, showInterfaces);
        return ResponseEntity.ok(Map.of("plantuml", puml));
    }

    /**
     * 渲染图表为PNG/SVG
     * 支持所有图表类型的过滤参数
     * @param scale 缩放因子（仅PNG格式，默认1.0，推荐大型项目使用1.5-2.0）
     */
    @GetMapping("/{sessionId}/render")
    public ResponseEntity<?> renderDiagram(@PathVariable String sessionId,
                                           @RequestParam(defaultValue = "component") String type,
                                           @RequestParam(defaultValue = "png") String format,
                                           @RequestParam(defaultValue = "1.0") double scale,
                                           @RequestParam(required = false) String packageFilter,
                                           @RequestParam(required = false) String componentName,
                                           @RequestParam(defaultValue = "50") int maxClasses,
                                           @RequestParam(defaultValue = "0") int maxDepth,
                                           @RequestParam(required = false) String source,
                                           @RequestParam(required = false) String layer,
                                           @RequestParam(defaultValue = "0") int maxComponents,
                                           @RequestParam(defaultValue = "false") boolean showInterfaces) {
        Project project = findProject(sessionId);
        if (project == null) return ResponseEntity.notFound().build();

        String puml;
        switch (type) {
            case "package":
                puml = packageDiagramGenerator.generate(project.getId(), maxDepth);
                break;
            case "class":
                puml = classDiagramGenerator.generate(project.getId(), packageFilter, componentName, maxClasses);
                break;
            case "component":
                puml = componentDiagramGenerator.generate(project.getId(),
                        source, layer, componentName, maxComponents, showInterfaces);
                break;
            default:
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid type: " + type));
        }

        byte[] image;
        MediaType mediaType;
        String extension;

        if ("svg".equalsIgnoreCase(format)) {
            image = plantUmlRenderer.renderToSvg(puml);
            mediaType = MediaType.valueOf("image/svg+xml");
            extension = "svg";
        } else {
            // PNG格式支持自定义缩放
            image = plantUmlRenderer.renderToPng(puml, scale);
            mediaType = MediaType.IMAGE_PNG;
            extension = "png";
        }

        if (image.length == 0) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Rendering failed"));
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + type + "_diagram." + extension + "\"")
                .body(image);
    }

    private Project findProject(String sessionId) {
        return projectRepository.findBySessionId(sessionId).orElse(null);
    }
}
