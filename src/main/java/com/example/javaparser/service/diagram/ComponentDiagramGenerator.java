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

/**
 * 组件图生成器（优化版）
 *
 * 优化点：
 * 1. 组件内部展示关键类列表（通过note附注）
 * 2. 按架构层次分组布局（Controller/Service/Repository/Entity/Infrastructure）
 * 3. 依赖权重标注与线条粗细区分
 * 4. 循环依赖检测与红色标注
 * 5. 布局参数优化（正交路由、间距控制）
 * 6. 组件来源视觉区分（FSK蓝色/CLUSTERING绿色）
 * 7. 提供/需求接口建模（可选）
 * 8. 过滤参数支持（source/layer/componentName/maxComponents）
 * 9. alias唯一性保证（使用索引号）
 * 10. 异常信息安全处理
 */
@Slf4j
@Service
public class ComponentDiagramGenerator {

    @Autowired
    private RecoveredComponentRepository recoveredComponentRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    @Autowired
    private ClassInfoRepository classInfoRepository;

    // 架构层次定义
    private static final String LAYER_CONTROLLER = "Controller Layer";
    private static final String LAYER_SERVICE = "Service Layer";
    private static final String LAYER_REPOSITORY = "Repository Layer";
    private static final String LAYER_ENTITY = "Entity Layer";
    private static final String LAYER_INFRASTRUCTURE = "Infrastructure";

    // 层次颜色
    private static final Map<String, String> LAYER_COLORS = new LinkedHashMap<>();
    static {
        LAYER_COLORS.put(LAYER_CONTROLLER, "#F0F8FF");
        LAYER_COLORS.put(LAYER_SERVICE, "#F0FFF0");
        LAYER_COLORS.put(LAYER_REPOSITORY, "#FFF8F0");
        LAYER_COLORS.put(LAYER_ENTITY, "#FFF0F5");
        LAYER_COLORS.put(LAYER_INFRASTRUCTURE, "#F5F5F5");
    }

    /**
     * 生成组件图（带过滤参数）
     */
    public String generate(Long projectId, String source, String layer,
                           String componentName, int maxComponents, boolean showInterfaces) {
        // 获取恢复结果的组件
        List<RecoveredComponent> components = recoveredComponentRepository.findByProjectId(projectId);
        if (components.isEmpty()) {
            return "@startuml\nnote \"No recovery result found.\\nPlease run architecture recovery first.\" as N1\n@enduml\n";
        }

        // 加载ClassInfo用于层次推断和类列表展示
        List<ClassInfo> allClassInfos = classInfoRepository.findByProjectId(projectId);
        Map<String, ClassInfo> classInfoMap = new HashMap<>();
        for (ClassInfo ci : allClassInfos) {
            classInfoMap.put(ci.getFullyQualifiedName(), ci);
        }

        // 推断每个组件的架构层次
        Map<String, String> componentLayerMap = new LinkedHashMap<>();
        for (RecoveredComponent comp : components) {
            componentLayerMap.put(comp.getName(), detectComponentLayer(comp, classInfoMap));
        }

        // === 过滤 ===
        List<RecoveredComponent> filtered = new ArrayList<>(components);

        if (source != null && !source.isEmpty() && !"ALL".equalsIgnoreCase(source)) {
            filtered = filtered.stream()
                    .filter(c -> source.equalsIgnoreCase(c.getSource()))
                    .collect(Collectors.toList());
        }
        if (layer != null && !layer.isEmpty()) {
            filtered = filtered.stream()
                    .filter(c -> {
                        String compLayer = componentLayerMap.getOrDefault(c.getName(), LAYER_INFRASTRUCTURE);
                        return compLayer.toLowerCase().contains(layer.toLowerCase());
                    })
                    .collect(Collectors.toList());
        }
        if (componentName != null && !componentName.isEmpty()) {
            filtered = filtered.stream()
                    .filter(c -> c.getName().toLowerCase().contains(componentName.toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (maxComponents > 0 && filtered.size() > maxComponents) {
            filtered = filtered.subList(0, maxComponents);
        }

        if (filtered.isEmpty()) {
            return "@startuml\nnote \"No components match the filter criteria\" as N1\n@enduml\n";
        }

        // === 构建alias映射（唯一性保证）===
        Map<String, String> aliasMap = new LinkedHashMap<>();
        int idx = 0;
        for (RecoveredComponent comp : filtered) {
            aliasMap.put(comp.getName(), "C" + (idx++));
        }

        // === 构建类名到组件名的映射 ===
        Map<String, String> classToComponent = new HashMap<>();
        Set<String> filteredCompNames = new HashSet<>();
        for (RecoveredComponent comp : filtered) {
            filteredCompNames.add(comp.getName());
            if (comp.getClassNames() != null) {
                for (String cls : comp.getClassNames().split(",")) {
                    classToComponent.put(cls.trim(), comp.getName());
                }
            }
        }

        // === 构建组件间依赖（含权重和关系类型）===
        List<ClassRelation> relations = classRelationRepository.findByProjectId(projectId);
        Map<String, Map<String, DepInfo>> componentDeps = new LinkedHashMap<>();
        for (ClassRelation rel : relations) {
            String srcComp = classToComponent.get(rel.getSourceClassName());
            String tgtComp = classToComponent.get(rel.getTargetClassName());
            if (srcComp != null && tgtComp != null && !srcComp.equals(tgtComp)
                    && filteredCompNames.contains(srcComp) && filteredCompNames.contains(tgtComp)) {
                componentDeps
                        .computeIfAbsent(srcComp, k -> new LinkedHashMap<>())
                        .computeIfAbsent(tgtComp, k -> new DepInfo())
                        .addRelation(rel.getRelationType());
            }
        }

        // === 检测循环依赖 ===
        Set<String> circularPairs = new HashSet<>();
        for (Map.Entry<String, Map<String, DepInfo>> entry : componentDeps.entrySet()) {
            String src = entry.getKey();
            for (String tgt : entry.getValue().keySet()) {
                Map<String, DepInfo> reverseDeps = componentDeps.get(tgt);
                if (reverseDeps != null && reverseDeps.containsKey(src)) {
                    String pairKey = src.compareTo(tgt) < 0 ? src + "|" + tgt : tgt + "|" + src;
                    circularPairs.add(pairKey);
                }
            }
        }

        // === 收集接口信息（可选）===
        Map<String, List<String>> componentProvidedInterfaces = new LinkedHashMap<>();
        Map<String, Set<String>> componentRequiredInterfaces = new LinkedHashMap<>();
        if (showInterfaces) {
            collectInterfaceInfo(filtered, classInfoMap, classToComponent,
                    relations, componentProvidedInterfaces, componentRequiredInterfaces);
        }

        // === 按架构层分组 ===
        Map<String, List<RecoveredComponent>> layerGroups = new LinkedHashMap<>();
        for (String l : LAYER_COLORS.keySet()) {
            layerGroups.put(l, new ArrayList<>());
        }
        for (RecoveredComponent comp : filtered) {
            String compLayer = componentLayerMap.getOrDefault(comp.getName(), LAYER_INFRASTRUCTURE);
            layerGroups.computeIfAbsent(compLayer, k -> new ArrayList<>()).add(comp);
        }

        // === 生成PlantUML ===
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");

        appendSkinParams(sb);

        // 按层次渲染组件
        for (Map.Entry<String, List<RecoveredComponent>> layerEntry : layerGroups.entrySet()) {
            List<RecoveredComponent> layerComps = layerEntry.getValue();
            if (layerComps.isEmpty()) continue;

            String layerName = layerEntry.getKey();
            String layerColor = LAYER_COLORS.getOrDefault(layerName, "#F5F5F5");
            String layerAlias = "L" + layerName.replaceAll("[^a-zA-Z0-9]", "");

            sb.append("rectangle \"").append(layerName).append("\" as ")
              .append(layerAlias).append(" ").append(layerColor).append(" {\n");

            for (RecoveredComponent comp : layerComps) {
                appendComponentDeclaration(sb, comp, aliasMap, classInfoMap);
            }

            sb.append("}\n\n");
        }

        // 组件内部类列表（用note附注，放在层次容器外面）
        appendClassNotes(sb, filtered, aliasMap, classInfoMap);

        // 渲染接口（可选）
        if (showInterfaces) {
            appendInterfaceDeclarations(sb, filtered, aliasMap,
                    componentProvidedInterfaces, componentRequiredInterfaces);
        }

        // 渲染组件间依赖
        appendDependencies(sb, componentDeps, aliasMap, circularPairs);

        sb.append("\n@enduml\n");
        return sb.toString();
    }

    /**
     * 兼容旧接口（无过滤参数）
     */
    public String generate(Long projectId) {
        return generate(projectId, null, null, null, 0, false);
    }

    // ==================== 私有方法 ====================

    /**
     * 布局参数
     */
    private void appendSkinParams(StringBuilder sb) {
        sb.append("top to bottom direction\n");
        sb.append("skinparam linetype ortho\n\n");

        sb.append("skinparam dpi 150\n");
        sb.append("skinparam componentStyle uml2\n");
        sb.append("skinparam shadowing false\n");
        sb.append("skinparam defaultFontName \"Microsoft YaHei\"\n");
        sb.append("skinparam defaultFontSize 12\n");
        sb.append("skinparam wrapWidth 200\n");
        sb.append("scale max 4096 width\n\n");

        sb.append("skinparam component {\n");
        sb.append("  BorderColor #5B8DEF\n");
        sb.append("  FontColor #2C3E50\n");
        sb.append("  FontSize 13\n");
        sb.append("  FontStyle bold\n");
        sb.append("  StereotypeFontSize 10\n");
        sb.append("  StereotypeFontColor #7F8C8D\n");
        sb.append("}\n\n");

        sb.append("skinparam arrow {\n");
        sb.append("  Color #6C757D\n");
        sb.append("  FontSize 10\n");
        sb.append("  Thickness 1.5\n");
        sb.append("}\n\n");

        sb.append("skinparam rectangle {\n");
        sb.append("  BorderColor #CCCCCC\n");
        sb.append("  FontSize 11\n");
        sb.append("  FontStyle italic\n");
        sb.append("  StereotypeFontSize 10\n");
        sb.append("}\n\n");

        sb.append("skinparam note {\n");
        sb.append("  BackgroundColor #FFFFF0\n");
        sb.append("  BorderColor #DDDDDD\n");
        sb.append("  FontSize 10\n");
        sb.append("}\n\n");
    }

    /**
     * 组件声明（不含内部body，PlantUML component不支持自由文本body）
     */
    private void appendComponentDeclaration(StringBuilder sb, RecoveredComponent comp,
                                             Map<String, String> aliasMap,
                                             Map<String, ClassInfo> classInfoMap) {
        String alias = aliasMap.get(comp.getName());
        int classCount = comp.getClassNames() != null ? comp.getClassNames().split(",").length : 0;

        // 来源颜色区分
        String bgColor;
        if ("FSK".equalsIgnoreCase(comp.getSource())) {
            bgColor = "#E8F0FE";
        } else if ("CLUSTERING".equalsIgnoreCase(comp.getSource())) {
            bgColor = "#E8F8E8";
        } else {
            bgColor = "#F0F0F0";
        }

        sb.append("  component \"").append(escapeUml(comp.getName()))
          .append(" (").append(classCount).append(")\" as ")
          .append(alias);
        if (comp.getSource() != null) {
            sb.append(" <<").append(comp.getSource()).append(">>");
        }
        sb.append(" ").append(bgColor).append("\n");
    }

    /**
     * 用note为每个组件附注关键类列表
     */
    private void appendClassNotes(StringBuilder sb, List<RecoveredComponent> components,
                                   Map<String, String> aliasMap,
                                   Map<String, ClassInfo> classInfoMap) {
        for (RecoveredComponent comp : components) {
            if (comp.getClassNames() == null || comp.getClassNames().isEmpty()) continue;

            String alias = aliasMap.get(comp.getName());
            if (alias == null) continue;

            String[] classNames = comp.getClassNames().split(",");
            if (classNames.length == 0) continue;

            int showCount = Math.min(5, classNames.length);
            StringBuilder noteContent = new StringBuilder();
            for (int i = 0; i < showCount; i++) {
                String fqn = classNames[i].trim();
                ClassInfo ci = classInfoMap.get(fqn);
                String simpleName = ci != null ? ci.getSimpleName() : extractSimpleName(fqn);
                if (i > 0) noteContent.append("\\n");
                noteContent.append(escapeUml(simpleName));
            }
            if (classNames.length > 5) {
                noteContent.append("\\n.. ").append(classNames.length - 5).append(" more ..");
            }

            sb.append("note right of ").append(alias)
              .append(" : ").append(noteContent).append("\n");
        }
        sb.append("\n");
    }

    /**
     * 渲染组件间依赖（权重标注 + 循环检测）
     */
    private void appendDependencies(StringBuilder sb,
                                     Map<String, Map<String, DepInfo>> componentDeps,
                                     Map<String, String> aliasMap,
                                     Set<String> circularPairs) {
        Set<String> rendered = new HashSet<>();

        for (Map.Entry<String, Map<String, DepInfo>> srcEntry : componentDeps.entrySet()) {
            String srcComp = srcEntry.getKey();
            String srcAlias = aliasMap.get(srcComp);
            if (srcAlias == null) continue;

            for (Map.Entry<String, DepInfo> tgtEntry : srcEntry.getValue().entrySet()) {
                String tgtComp = tgtEntry.getKey();
                String tgtAlias = aliasMap.get(tgtComp);
                if (tgtAlias == null) continue;

                DepInfo dep = tgtEntry.getValue();
                int totalWeight = dep.totalCount;

                // 检查是否为循环依赖
                String pairKey = srcComp.compareTo(tgtComp) < 0 ?
                        srcComp + "|" + tgtComp : tgtComp + "|" + srcComp;
                boolean isCircular = circularPairs.contains(pairKey);

                String renderKey = srcAlias + "->" + tgtAlias;
                if (rendered.contains(renderKey)) continue;
                rendered.add(renderKey);

                // 构建箭头和标签
                String arrow;
                String label = String.valueOf(totalWeight);

                if (isCircular) {
                    // 循环依赖用红色粗线
                    arrow = "-[#FF0000,bold]->";
                    label += " <<circular>>";
                } else if (totalWeight >= 10) {
                    // 强耦合：蓝色粗线
                    arrow = "-[#5B8DEF,bold]->";
                } else if (totalWeight >= 5) {
                    // 中等耦合：深灰色
                    arrow = "-[#6C757D]->";
                } else {
                    // 弱耦合：浅灰色虚线
                    arrow = "-[#AAAAAA]->";
                }

                sb.append(srcAlias).append(" ").append(arrow)
                  .append(" ").append(tgtAlias)
                  .append(" : ").append(label).append("\n");
            }
        }
    }

    /**
     * 收集接口信息
     */
    private void collectInterfaceInfo(List<RecoveredComponent> components,
                                       Map<String, ClassInfo> classInfoMap,
                                       Map<String, String> classToComponent,
                                       List<ClassRelation> relations,
                                       Map<String, List<String>> providedInterfaces,
                                       Map<String, Set<String>> requiredInterfaces) {
        for (RecoveredComponent comp : components) {
            if (comp.getClassNames() == null) continue;
            List<String> provided = new ArrayList<>();
            for (String fqn : comp.getClassNames().split(",")) {
                ClassInfo ci = classInfoMap.get(fqn.trim());
                if (ci != null && "Interface".equals(ci.getClassType())) {
                    provided.add(ci.getSimpleName());
                }
            }
            if (!provided.isEmpty()) {
                providedInterfaces.put(comp.getName(), provided);
            }
        }

        for (ClassRelation rel : relations) {
            String srcComp = classToComponent.get(rel.getSourceClassName());
            String tgtComp = classToComponent.get(rel.getTargetClassName());
            if (srcComp != null && tgtComp != null && !srcComp.equals(tgtComp)) {
                ClassInfo targetCi = classInfoMap.get(rel.getTargetClassName());
                if (targetCi != null && "Interface".equals(targetCi.getClassType())) {
                    requiredInterfaces
                            .computeIfAbsent(srcComp, k -> new LinkedHashSet<>())
                            .add(targetCi.getSimpleName());
                }
            }
        }
    }

    /**
     * 渲染接口声明
     */
    private void appendInterfaceDeclarations(StringBuilder sb,
                                              List<RecoveredComponent> components,
                                              Map<String, String> aliasMap,
                                              Map<String, List<String>> providedInterfaces,
                                              Map<String, Set<String>> requiredInterfaces) {
        Set<String> allInterfaces = new LinkedHashSet<>();
        providedInterfaces.values().forEach(allInterfaces::addAll);
        requiredInterfaces.values().forEach(allInterfaces::addAll);

        if (allInterfaces.isEmpty()) return;

        int maxInterfaces = 15;
        List<String> interfaceList = new ArrayList<>(allInterfaces);
        if (interfaceList.size() > maxInterfaces) {
            interfaceList = interfaceList.subList(0, maxInterfaces);
        }

        sb.append("\n' --- Interfaces ---\n");
        Map<String, String> ifaceAliasMap = new HashMap<>();
        for (int i = 0; i < interfaceList.size(); i++) {
            String ifaceName = interfaceList.get(i);
            String ifaceAlias = "IF" + i;
            ifaceAliasMap.put(ifaceName, ifaceAlias);
            sb.append("() \"").append(escapeUml(ifaceName)).append("\" as ").append(ifaceAlias).append("\n");
        }

        for (Map.Entry<String, List<String>> entry : providedInterfaces.entrySet()) {
            String compAlias = aliasMap.get(entry.getKey());
            if (compAlias == null) continue;
            for (String ifaceName : entry.getValue()) {
                String ifaceAlias = ifaceAliasMap.get(ifaceName);
                if (ifaceAlias != null) {
                    sb.append(compAlias).append(" -- ").append(ifaceAlias).append("\n");
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : requiredInterfaces.entrySet()) {
            String compAlias = aliasMap.get(entry.getKey());
            if (compAlias == null) continue;
            for (String ifaceName : entry.getValue()) {
                String ifaceAlias = ifaceAliasMap.get(ifaceName);
                if (ifaceAlias != null) {
                    sb.append(compAlias).append(" ..> ").append(ifaceAlias).append("\n");
                }
            }
        }
    }

    /**
     * 推断组件所属架构层
     */
    private String detectComponentLayer(RecoveredComponent comp, Map<String, ClassInfo> classInfoMap) {
        if (comp.getClassNames() == null || comp.getClassNames().isEmpty()) {
            return LAYER_INFRASTRUCTURE;
        }

        Map<String, Integer> layerCounts = new HashMap<>();
        for (String fqn : comp.getClassNames().split(",")) {
            ClassInfo ci = classInfoMap.get(fqn.trim());
            String detectedLayer = detectClassLayer(ci, fqn.trim());
            layerCounts.merge(detectedLayer, 1, Integer::sum);
        }

        return layerCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(LAYER_INFRASTRUCTURE);
    }

    /**
     * 推断单个类的架构层（基于注解和命名）
     */
    private String detectClassLayer(ClassInfo ci, String fqn) {
        String name = ci != null ? ci.getSimpleName() : extractSimpleName(fqn);
        String annotations = ci != null ? ci.getAnnotations() : "";
        String pkg = ci != null ? ci.getPackageName() : "";

        if (annotations == null) annotations = "";
        if (pkg == null) pkg = "";

        // 注解优先
        if (annotations.contains("Controller") || annotations.contains("RestController")) {
            return LAYER_CONTROLLER;
        }
        if (annotations.contains("Service")) {
            return LAYER_SERVICE;
        }
        if (annotations.contains("Repository")) {
            return LAYER_REPOSITORY;
        }
        if (annotations.contains("Entity") || annotations.contains("Table")) {
            return LAYER_ENTITY;
        }

        // 类名后缀
        if (name.endsWith("Controller") || name.endsWith("Resource") || name.endsWith("Endpoint")) {
            return LAYER_CONTROLLER;
        }
        if (name.endsWith("Service") || name.endsWith("ServiceImpl") || name.endsWith("Manager")
                || name.endsWith("Handler") || name.endsWith("Processor")) {
            return LAYER_SERVICE;
        }
        if (name.endsWith("Repository") || name.endsWith("Dao") || name.endsWith("Mapper")) {
            return LAYER_REPOSITORY;
        }
        if (name.endsWith("Entity") || name.endsWith("Model") || name.endsWith("DTO")
                || name.endsWith("VO") || name.endsWith("PO")) {
            return LAYER_ENTITY;
        }

        // 包名
        if (pkg.contains("controller") || pkg.contains("web") || pkg.contains("api")) {
            return LAYER_CONTROLLER;
        }
        if (pkg.contains("service")) {
            return LAYER_SERVICE;
        }
        if (pkg.contains("repository") || pkg.contains("dao") || pkg.contains("mapper")) {
            return LAYER_REPOSITORY;
        }
        if (pkg.contains("entity") || pkg.contains("model") || pkg.contains("domain")) {
            return LAYER_ENTITY;
        }

        return LAYER_INFRASTRUCTURE;
    }

    private String extractSimpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot > 0 ? fqn.substring(dot + 1) : fqn;
    }

    /**
     * 转义PlantUML特殊字符
     */
    private String escapeUml(String text) {
        if (text == null) return "";
        return text.replace("\"", "'").replace("\n", " ").replace("\r", "");
    }

    private static final List<String> PRIORITY_ORDER = Arrays.asList(
            "GENERALIZATION", "REALIZATION", "COMPOSITION", "AGGREGATION",
            "ASSOCIATION", "DEPENDENCY", "INHERITANCE", "IMPLEMENTATION"
    );

    // ==================== 内部类 ====================

    private static class DepInfo {
        final Map<String, Integer> typeCounts = new LinkedHashMap<>();
        int totalCount = 0;

        void addRelation(String relationType) {
            typeCounts.merge(relationType, 1, Integer::sum);
            totalCount++;
        }

        String getDominantType() {
            String dominant = null;
            int maxCount = 0;
            int bestPriority = Integer.MAX_VALUE;

            for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
                int priority = PRIORITY_ORDER.indexOf(e.getKey());
                if (priority < 0) priority = PRIORITY_ORDER.size();
                if (e.getValue() > maxCount || (e.getValue() == maxCount && priority < bestPriority)) {
                    dominant = e.getKey();
                    maxCount = e.getValue();
                    bestPriority = priority;
                }
            }
            return dominant != null ? dominant : "DEPENDENCY";
        }
    }
}
