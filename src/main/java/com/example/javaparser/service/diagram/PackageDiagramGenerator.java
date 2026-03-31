package com.example.javaparser.service.diagram;

import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.ClassRelation;
import com.example.javaparser.entity.RecoveredComponent;
import com.example.javaparser.repository.ClassInfoRepository;
import com.example.javaparser.repository.ClassRelationRepository;
import com.example.javaparser.repository.RecoveredComponentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PackageDiagramGenerator {

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    @Autowired
    private RecoveredComponentRepository recoveredComponentRepository;

    /**
     * 生成包图（支持深度折叠）
     *
     * 布局策略：
     * 1. 箭头只连接包与包（包alias直连），线条简洁不重叠
     * 2. 包内用浮动note展示类名列表，视觉上在包矩形内部，但不会成为箭头端点
     * 3. 同一对包之间只有一条箭头，标注依赖权重
     * 4. 循环依赖用红色粗线标注
     * 5. top to bottom方向 + ortho路由，包间关系整齐有序
     */
    public String generate(Long projectId, int maxDepth) {
        List<ClassInfo> classes = classInfoRepository.findByProjectId(projectId);
        List<ClassRelation> relations = classRelationRepository.findByProjectId(projectId);

        // 按包分组
        Map<String, List<ClassInfo>> byPackage = classes.stream()
                .collect(Collectors.groupingBy(
                        c -> truncatePackage(c.getPackageName(), maxDepth),
                        LinkedHashMap::new, Collectors.toList()));

        // 类FQN -> 所属包名
        Map<String, String> fqnToPackage = new LinkedHashMap<>();
        for (ClassInfo ci : classes) {
            fqnToPackage.put(ci.getFullyQualifiedName(), truncatePackage(ci.getPackageName(), maxDepth));
        }

        // 包 -> 组件名
        Map<String, String> packageToComponent = buildPackageToComponentMap(projectId, classes, maxDepth);

        // 为每个包生成唯一alias
        Map<String, String> pkgAliasMap = new LinkedHashMap<>();
        int idx = 0;
        for (String pkg : byPackage.keySet()) {
            pkgAliasMap.put(pkg, "P" + (idx++));
        }

        // ========== 计算跨包依赖（包级别聚合） ==========
        Map<String, Integer> pkgDepWeights = new LinkedHashMap<>();
        for (ClassRelation rel : relations) {
            String srcPkg = fqnToPackage.get(rel.getSourceClassName());
            String tgtPkg = fqnToPackage.get(rel.getTargetClassName());
            if (srcPkg != null && tgtPkg != null && !srcPkg.equals(tgtPkg)) {
                pkgDepWeights.merge(srcPkg + "|" + tgtPkg, 1, Integer::sum);
            }
        }

        // 检测循环依赖
        Set<String> circularPkgPairs = new HashSet<>();
        for (String key : pkgDepWeights.keySet()) {
            String[] parts = key.split("\\|", 2);
            if (pkgDepWeights.containsKey(parts[1] + "|" + parts[0])) {
                circularPkgPairs.add(key);
            }
        }

        // ========== 生成PlantUML ==========
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("scale max 4096 width\n");
        sb.append("top to bottom direction\n\n");
        appendSkinParams(sb);

        // ========== 声明包（包内用浮动note展示类名） ==========
        String[] colors = {
                "#EBF5FB", "#FEF9E7", "#F5EEF8", "#EAFAF1", "#FDEDEC",
                "#F0F3F4", "#FDF2E9", "#E8F8F5", "#F9EBEA", "#EAF2F8"
        };
        int colorIdx = 0;
        for (Map.Entry<String, List<ClassInfo>> entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            List<ClassInfo> pkgClasses = entry.getValue();
            String alias = pkgAliasMap.get(pkg);
            String color = colors[colorIdx % colors.length];
            colorIdx++;

            // 包标签
            String compName = packageToComponent.get(pkg);
            String label = pkg + " (" + pkgClasses.size() + ")";
            if (compName != null) {
                label += "\\n[" + compName + "]";
            }

            sb.append("package \"").append(label).append("\" as ")
                    .append(alias).append(" ").append(color).append(" {\n");

            // 包内浮动note列出类名，不产生可连接节点
            sb.append("  note \"");
            for (int i = 0; i < pkgClasses.size(); i++) {
                ClassInfo ci = pkgClasses.get(i);
                String classType = ci.getClassType();
                if ("Interface".equals(classType)) {
                    sb.append("<<I>> ");
                } else if ("Enumeration".equals(classType)) {
                    sb.append("<<E>> ");
                }
                sb.append(ci.getSimpleName());
                if (i < pkgClasses.size() - 1) {
                    sb.append("\\n");
                }
            }
            sb.append("\" as ").append(alias).append("_N\n");

            sb.append("}\n\n");
        }

        // ========== 包间依赖箭头（包alias直连） ==========
        pkgDepWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    String srcAlias = pkgAliasMap.get(parts[0]);
                    String tgtAlias = pkgAliasMap.get(parts[1]);
                    if (srcAlias == null || tgtAlias == null) return;

                    int weight = e.getValue();
                    boolean isCircular = circularPkgPairs.contains(e.getKey());

                    sb.append(srcAlias);
                    if (isCircular) {
                        sb.append(" -[#FF0000,bold]-> ");
                    } else if (weight >= 5) {
                        sb.append(" -[#5B8DEF,bold]-> ");
                    } else {
                        sb.append(" -[#6C757D]-> ");
                    }
                    sb.append(tgtAlias);
                    sb.append(" : ").append(weight);
                    if (isCircular) {
                        sb.append(" //circular//");
                    }
                    sb.append("\n");
                });

        sb.append("\n@enduml\n");
        return sb.toString();
    }

    public String generate(Long projectId) {
        return generate(projectId, 0);
    }

    // ==================== 辅助方法 ====================

    private Map<String, String> buildPackageToComponentMap(Long projectId,
                                                            List<ClassInfo> classes, int maxDepth) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            List<RecoveredComponent> components = recoveredComponentRepository.findByProjectId(projectId);
            Map<String, String> classToPackage = classes.stream()
                    .collect(Collectors.toMap(
                            ClassInfo::getFullyQualifiedName,
                            c -> truncatePackage(c.getPackageName(), maxDepth),
                            (a, b) -> a));

            for (RecoveredComponent comp : components) {
                if (comp.getClassNames() == null || comp.getClassNames().isEmpty()) continue;
                for (String className : comp.getClassNames().split(",")) {
                    String pkg = classToPackage.get(className.trim());
                    if (pkg != null) {
                        result.putIfAbsent(pkg, comp.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取组件映射失败: {}", e.getMessage());
        }
        return result;
    }

    private String truncatePackage(String packageName, int maxDepth) {
        if (packageName == null) return "default";
        if (maxDepth <= 0) return packageName;
        String[] parts = packageName.split("\\.");
        if (parts.length <= maxDepth) return packageName;
        return String.join(".", Arrays.copyOf(parts, maxDepth));
    }

    private void appendSkinParams(StringBuilder sb) {
        sb.append("skinparam dpi 150\n");
        sb.append("skinparam packageStyle rectangle\n");
        sb.append("skinparam shadowing false\n");
        sb.append("skinparam defaultFontName \"Microsoft YaHei\"\n");
        sb.append("skinparam defaultFontSize 12\n");
        sb.append("skinparam linetype ortho\n");
        sb.append("skinparam Padding 10\n\n");

        sb.append("skinparam package {\n");
        sb.append("  BackgroundColor #F8F9FA\n");
        sb.append("  BorderColor #5B8DEF\n");
        sb.append("  FontColor #2C3E50\n");
        sb.append("  FontSize 13\n");
        sb.append("  FontStyle bold\n");
        sb.append("}\n\n");

        sb.append("skinparam note {\n");
        sb.append("  BackgroundColor transparent\n");
        sb.append("  BorderColor transparent\n");
        sb.append("  FontColor #495057\n");
        sb.append("  FontSize 11\n");
        sb.append("}\n\n");

        sb.append("skinparam arrow {\n");
        sb.append("  Color #6C757D\n");
        sb.append("  FontSize 10\n");
        sb.append("  Thickness 1.5\n");
        sb.append("}\n\n");
    }
}
