package com.example.javaparser.service.diagram;

import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.ClassRelation;
import com.example.javaparser.entity.RecoveredComponent;
import com.example.javaparser.repository.ClassInfoRepository;
import com.example.javaparser.repository.ClassRelationRepository;
import com.example.javaparser.repository.RecoveredComponentRepository;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 类图生成器（优化版）
 *
 * 适配 PlantUML 1.2024.3，与组件图/包图风格统一：
 * 1. 使用 package 容器按包分组（不使用 namespace + namespaceSeparator，避免兼容性问题）
 * 2. 关系箭头颜色内嵌 -[#color]-> 语法
 * 3. 继承/实现关系优先渲染，依赖关系最后
 * 4. 架构层 stereotype 着色区分
 * 5. 字段/方法截断保护，特殊字符转义
 */
@Slf4j
@Service
public class ClassDiagramGenerator {

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private ClassRelationRepository classRelationRepository;

    @Autowired
    private RecoveredComponentRepository recoveredComponentRepository;

    private final Gson gson = new Gson();

    private static final Type FIELD_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();
    private static final Type METHOD_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    // 架构层 spot 颜色（PlantUML spot letter + color）
    private static final Map<String, String> LAYER_SPOT = new LinkedHashMap<>();
    static {
        LAYER_SPOT.put("Controller", "#E8F0FE");
        LAYER_SPOT.put("Service", "#F0FFF0");
        LAYER_SPOT.put("Repository", "#FFF8F0");
        LAYER_SPOT.put("Entity", "#FFF0F5");
    }

    /**
     * 生成类图（支持过滤参数）
     */
    public String generate(Long projectId, String packageFilter, String componentName, int maxClasses) {
        List<ClassInfo> classes = classInfoRepository.findByProjectId(projectId);
        List<ClassRelation> relations = classRelationRepository.findByProjectId(projectId);

        // 按包名前缀过滤
        if (packageFilter != null && !packageFilter.isEmpty()) {
            classes = classes.stream()
                    .filter(c -> c.getPackageName() != null && c.getPackageName().startsWith(packageFilter))
                    .collect(Collectors.toList());
        }

        // 按组件名过滤
        if (componentName != null && !componentName.isEmpty()) {
            Set<String> compClassNames = findClassNamesByComponent(projectId, componentName);
            if (compClassNames != null) {
                classes = classes.stream()
                        .filter(c -> compClassNames.contains(c.getFullyQualifiedName()))
                        .collect(Collectors.toList());
            }
        }

        // 限制最大类数量
        if (classes.size() > maxClasses) {
            classes = classes.subList(0, maxClasses);
        }

        if (classes.isEmpty()) {
            return "@startuml\nnote \"No classes found matching the filter criteria\" as N1\n@enduml\n";
        }

        // 构建FQN集合用于过滤关系
        Set<String> fqnSet = classes.stream()
                .map(ClassInfo::getFullyQualifiedName)
                .collect(Collectors.toSet());

        // FQN -> alias 映射（避免FQN中的.号被PlantUML误解析）
        Map<String, String> aliasMap = new LinkedHashMap<>();
        int idx = 0;
        for (ClassInfo ci : classes) {
            aliasMap.put(ci.getFullyQualifiedName(), "CL" + (idx++));
        }

        // 构建类到组件的映射
        Map<String, String> classToComponent = buildClassToComponentMap(projectId);

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("scale max 4096 width\n");
        sb.append("top to bottom direction\n\n");

        appendSkinParams(sb);

        // 按包分组
        Map<String, List<ClassInfo>> byPackage = classes.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getPackageName() != null ? c.getPackageName() : "default",
                        LinkedHashMap::new, Collectors.toList()));

        // 包容器颜色轮转
        String[] pkgColors = {
                "#EBF5FB", "#FEF9E7", "#F5EEF8", "#EAFAF1", "#FDEDEC",
                "#F0F3F4", "#FDF2E9", "#E8F8F5", "#F9EBEA", "#EAF2F8"
        };
        int colorIdx = 0;

        for (Map.Entry<String, List<ClassInfo>> entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            List<ClassInfo> pkgClasses = entry.getValue();
            String pkgColor = pkgColors[colorIdx % pkgColors.length];
            colorIdx++;

            sb.append("package \"").append(escapeUml(pkg))
              .append(" (").append(pkgClasses.size()).append(")\" ")
              .append(pkgColor).append(" {\n");

            for (ClassInfo ci : pkgClasses) {
                renderClassDeclaration(sb, ci, aliasMap, classToComponent);
            }

            sb.append("}\n\n");
        }

        // 渲染关系
        renderRelations(sb, relations, fqnSet, aliasMap);

        sb.append("\n@enduml\n");
        return sb.toString();
    }

    /**
     * 无参版本，保持向后兼容
     */
    public String generate(Long projectId) {
        return generate(projectId, null, null, 50);
    }

    // ==================== 私有方法 ====================

    /**
     * 渲染单个类的声明
     */
    private void renderClassDeclaration(StringBuilder sb, ClassInfo ci,
                                         Map<String, String> aliasMap,
                                         Map<String, String> classToComponent) {
        String alias = aliasMap.get(ci.getFullyQualifiedName());
        String stereotype = getStereotype(ci);
        if (stereotype == null) {
            String compName = classToComponent.get(ci.getFullyQualifiedName());
            if (compName != null) {
                stereotype = compName;
            }
        }

        // 背景色：按架构层区分
        String bgColor = null;
        String layer = detectLayer(ci);
        if (LAYER_SPOT.containsKey(layer)) {
            bgColor = LAYER_SPOT.get(layer);
        }

        // 类型关键字
        sb.append("  ");
        if ("Interface".equals(ci.getClassType())) {
            sb.append("interface");
        } else if ("Enumeration".equals(ci.getClassType())) {
            sb.append("enum");
        } else {
            String modifiers = ci.getModifiers();
            if (modifiers != null && modifiers.contains("abstract")) {
                sb.append("abstract class");
            } else {
                sb.append("class");
            }
        }

        sb.append(" \"").append(escapeUml(ci.getSimpleName())).append("\" as ").append(alias);

        if (stereotype != null) {
            sb.append(" <<").append(escapeUml(stereotype)).append(">>");
        }
        if (bgColor != null) {
            sb.append(" ").append(bgColor);
        }

        sb.append(" {\n");

        // 渲染字段
        renderFields(sb, ci);

        sb.append("    --\n");

        // 渲染方法
        renderMethods(sb, ci);

        sb.append("  }\n\n");
    }

    /**
     * 渲染字段：可见性符号 + 名称 : 类型
     */
    private void renderFields(StringBuilder sb, ClassInfo ci) {
        if (ci.getFieldDetails() != null && !ci.getFieldDetails().isEmpty()) {
            try {
                List<Map<String, Object>> fields = gson.fromJson(ci.getFieldDetails(), FIELD_LIST_TYPE);
                if (fields != null) {
                    int count = 0;
                    for (Map<String, Object> field : fields) {
                        if (count >= 8) {
                            sb.append("    .. ").append(fields.size() - 8).append(" more ..\n");
                            break;
                        }
                        String visibility = (String) field.getOrDefault("visibility", "package");
                        String name = (String) field.get("name");
                        String type = (String) field.getOrDefault("type", "Object");
                        boolean isStatic = Boolean.TRUE.equals(field.get("isStatic"));

                        sb.append("    ").append(visibilitySymbol(visibility));
                        if (isStatic) sb.append("{static} ");
                        sb.append(escapeUml(name)).append(" : ").append(escapeUml(type)).append("\n");
                        count++;
                    }
                    return;
                }
            } catch (Exception e) {
                log.debug("解析fieldDetails JSON失败，回退到fieldNames: {}", e.getMessage());
            }
        }

        // 回退：使用旧的fieldNames字段
        if (ci.getFieldNames() != null && !ci.getFieldNames().isEmpty()) {
            String[] fields = ci.getFieldNames().split(",");
            int limit = Math.min(8, fields.length);
            for (int i = 0; i < limit; i++) {
                sb.append("    ").append(escapeUml(fields[i].trim())).append("\n");
            }
            if (fields.length > 8) {
                sb.append("    .. ").append(fields.length - 8).append(" more ..\n");
            }
        }
    }

    /**
     * 渲染方法：可见性符号 + 名称(参数列表) : 返回类型
     */
    @SuppressWarnings("unchecked")
    private void renderMethods(StringBuilder sb, ClassInfo ci) {
        if (ci.getMethodDetails() != null && !ci.getMethodDetails().isEmpty()) {
            try {
                List<Map<String, Object>> methods = gson.fromJson(ci.getMethodDetails(), METHOD_LIST_TYPE);
                if (methods != null) {
                    int count = 0;
                    for (Map<String, Object> method : methods) {
                        if (count >= 8) {
                            sb.append("    .. ").append(methods.size() - 8).append(" more ..\n");
                            break;
                        }
                        String visibility = (String) method.getOrDefault("visibility", "package");
                        String name = (String) method.get("name");
                        String returnType = (String) method.getOrDefault("returnType", "void");
                        boolean isStatic = Boolean.TRUE.equals(method.get("isStatic"));
                        boolean isAbstract = Boolean.TRUE.equals(method.get("isAbstract"));

                        sb.append("    ").append(visibilitySymbol(visibility));
                        if (isStatic) sb.append("{static} ");
                        if (isAbstract) sb.append("{abstract} ");
                        sb.append(escapeUml(name)).append("(");

                        // 参数列表
                        List<Map<String, String>> params = (List<Map<String, String>>) method.get("params");
                        if (params != null && !params.isEmpty()) {
                            sb.append(params.stream()
                                    .map(p -> escapeUml(p.get("name")) + ": " +
                                              escapeUml(p.getOrDefault("type", "Object")))
                                    .collect(Collectors.joining(", ")));
                        }
                        sb.append(")");

                        if (returnType != null && !"void".equals(returnType)) {
                            sb.append(" : ").append(escapeUml(returnType));
                        }
                        sb.append("\n");
                        count++;
                    }
                    return;
                }
            } catch (Exception e) {
                log.debug("解析methodDetails JSON失败，回退到methodNames: {}", e.getMessage());
            }
        }

        // 回退：使用旧的methodNames字段
        if (ci.getMethodNames() != null && !ci.getMethodNames().isEmpty()) {
            String[] methods = ci.getMethodNames().split(",");
            int limit = Math.min(8, methods.length);
            for (int i = 0; i < limit; i++) {
                sb.append("    ").append(escapeUml(methods[i].trim())).append("()\n");
            }
            if (methods.length > 8) {
                sb.append("    .. ").append(methods.length - 8).append(" more ..\n");
            }
        }
    }

    /**
     * 渲染关系（颜色内嵌箭头语法，按类型优先级排序）
     */
    private void renderRelations(StringBuilder sb, List<ClassRelation> relations,
                                  Set<String> fqnSet, Map<String, String> aliasMap) {
        // 按关系类型优先级排序：继承/实现 > 组合/聚合 > 关联 > 依赖
        List<ClassRelation> sorted = relations.stream()
                .filter(r -> fqnSet.contains(r.getSourceClassName()) && fqnSet.contains(r.getTargetClassName()))
                .sorted(Comparator.comparingInt(r -> getRelationPriority(r.getRelationType())))
                .collect(Collectors.toList());

        Set<String> drawnRelations = new HashSet<>();
        for (ClassRelation rel : sorted) {
            String key = rel.getSourceClassName() + "->" + rel.getTargetClassName() + ":" + rel.getRelationType();
            if (drawnRelations.contains(key)) continue;
            drawnRelations.add(key);

            String srcAlias = aliasMap.get(rel.getSourceClassName());
            String tgtAlias = aliasMap.get(rel.getTargetClassName());
            if (srcAlias == null || tgtAlias == null) continue;

            String arrow = getArrowForRelationType(rel.getRelationType());

            // 多重性标注
            String srcMul = rel.getSourceMultiplicity();
            String tgtMul = rel.getTargetMultiplicity();

            sb.append(srcAlias);
            if (srcMul != null && !srcMul.isEmpty()) {
                sb.append(" \"").append(srcMul).append("\"");
            }
            sb.append(" ").append(arrow).append(" ");
            if (tgtMul != null && !tgtMul.isEmpty()) {
                sb.append("\"").append(tgtMul).append("\" ");
            }
            sb.append(tgtAlias);

            // 关联名称标注
            if (rel.getAssociationName() != null && !rel.getAssociationName().isEmpty()) {
                sb.append(" : ").append(escapeUml(rel.getAssociationName()));
            }
            sb.append("\n");
        }
    }

    /**
     * 关系类型优先级（越小越先渲染，继承/实现在最上层）
     */
    private int getRelationPriority(String relationType) {
        if (relationType == null) return 99;
        switch (relationType) {
            case "GENERALIZATION":
            case "INHERITANCE":
                return 0;
            case "REALIZATION":
            case "IMPLEMENTATION":
                return 1;
            case "COMPOSITION":
                return 2;
            case "AGGREGATION":
                return 3;
            case "ASSOCIATION":
                return 4;
            case "DEPENDENCY":
                return 5;
            default:
                return 99;
        }
    }

    /**
     * 箭头语法（颜色内嵌）
     */
    private String getArrowForRelationType(String relationType) {
        if (relationType == null) return "-[#7F8C8D]->";
        switch (relationType) {
            case "GENERALIZATION":
            case "INHERITANCE":
                return "-[#2E86C1]-|>";
            case "REALIZATION":
            case "IMPLEMENTATION":
                return ".[#27AE60].|>";
            case "COMPOSITION":
                return "-[#E74C3C]-*";
            case "AGGREGATION":
                return "-[#F39C12]-o";
            case "ASSOCIATION":
                return "-[#7F8C8D]->";
            case "DEPENDENCY":
                return ".[#BDC3C7].>";
            default:
                return "-[#7F8C8D]->";
        }
    }

    /**
     * 可见性符号映射
     */
    private String visibilitySymbol(String visibility) {
        if (visibility == null) return "~";
        switch (visibility) {
            case "public": return "+";
            case "private": return "-";
            case "protected": return "#";
            default: return "~";
        }
    }

    private String detectLayer(ClassInfo ci) {
        String annotations = ci.getAnnotations();
        String simpleName = ci.getSimpleName();
        String pkg = ci.getPackageName();

        if (annotations != null) {
            if (annotations.contains("Controller") || annotations.contains("RestController")) return "Controller";
            if (annotations.contains("Service")) return "Service";
            if (annotations.contains("Repository")) return "Repository";
            if (annotations.contains("Entity") || annotations.contains("Table")) return "Entity";
        }
        if (simpleName != null) {
            if (simpleName.endsWith("Controller") || simpleName.endsWith("Resource")) return "Controller";
            if (simpleName.endsWith("Service") || simpleName.endsWith("ServiceImpl")) return "Service";
            if (simpleName.endsWith("Repository") || simpleName.endsWith("Dao") || simpleName.endsWith("Mapper")) return "Repository";
            if (simpleName.endsWith("Entity") || simpleName.endsWith("DTO") || simpleName.endsWith("VO")) return "Entity";
        }
        if (pkg != null) {
            if (pkg.contains("controller") || pkg.contains("web")) return "Controller";
            if (pkg.contains("service")) return "Service";
            if (pkg.contains("repository") || pkg.contains("dao")) return "Repository";
            if (pkg.contains("entity") || pkg.contains("model") || pkg.contains("domain")) return "Entity";
        }
        return "Other";
    }

    private String getStereotype(ClassInfo ci) {
        if (ci.getAnnotations() == null) return null;
        if (ci.getAnnotations().contains("Service")) return "Service";
        if (ci.getAnnotations().contains("Controller") || ci.getAnnotations().contains("RestController"))
            return "Controller";
        if (ci.getAnnotations().contains("Repository")) return "Repository";
        if (ci.getAnnotations().contains("Entity")) return "Entity";
        if (ci.getAnnotations().contains("Configuration")) return "Config";
        if (ci.getAnnotations().contains("Component")) return "Component";
        return null;
    }

    /**
     * 构建类到组件的映射
     */
    private Map<String, String> buildClassToComponentMap(Long projectId) {
        Map<String, String> map = new HashMap<>();
        try {
            List<RecoveredComponent> components = recoveredComponentRepository.findByProjectId(projectId);
            for (RecoveredComponent comp : components) {
                if (comp.getClassNames() != null && !comp.getClassNames().isEmpty()) {
                    for (String className : comp.getClassNames().split(",")) {
                        map.put(className.trim(), comp.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取组件映射失败: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 根据组件名查找关联的类名集合
     */
    private Set<String> findClassNamesByComponent(Long projectId, String componentName) {
        try {
            List<RecoveredComponent> components = recoveredComponentRepository.findByProjectId(projectId);
            for (RecoveredComponent comp : components) {
                if (componentName.equals(comp.getName()) && comp.getClassNames() != null) {
                    return Arrays.stream(comp.getClassNames().split(","))
                            .map(String::trim)
                            .collect(Collectors.toSet());
                }
            }
        } catch (Exception e) {
            log.debug("按组件查找类失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 转义PlantUML特殊字符
     */
    private String escapeUml(String text) {
        if (text == null) return "";
        return text.replace("\"", "'").replace("\n", " ").replace("\r", "");
    }

    private void appendSkinParams(StringBuilder sb) {
        sb.append("skinparam dpi 150\n");
        sb.append("skinparam classAttributeIconSize 0\n");
        sb.append("skinparam shadowing false\n");
        sb.append("skinparam linetype ortho\n");
        sb.append("skinparam defaultFontName \"Microsoft YaHei\"\n");
        sb.append("skinparam defaultFontSize 12\n");
        sb.append("skinparam wrapWidth 200\n");
        sb.append("skinparam Padding 5\n\n");

        sb.append("skinparam class {\n");
        sb.append("  BackgroundColor #FEFEFE\n");
        sb.append("  BorderColor #5B8DEF\n");
        sb.append("  HeaderBackgroundColor #E8F0FE\n");
        sb.append("  FontColor #2C3E50\n");
        sb.append("  FontSize 12\n");
        sb.append("  AttributeFontSize 11\n");
        sb.append("  AttributeFontColor #555555\n");
        sb.append("  StereotypeFontSize 10\n");
        sb.append("  StereotypeFontColor #7F8C8D\n");
        sb.append("}\n\n");

        sb.append("skinparam interface {\n");
        sb.append("  BackgroundColor #F0FFF0\n");
        sb.append("  BorderColor #27AE60\n");
        sb.append("  HeaderBackgroundColor #D5F5E3\n");
        sb.append("  FontColor #1E8449\n");
        sb.append("  FontSize 12\n");
        sb.append("}\n\n");

        sb.append("skinparam enum {\n");
        sb.append("  BackgroundColor #FFF8E1\n");
        sb.append("  BorderColor #F39C12\n");
        sb.append("  HeaderBackgroundColor #FDEBD0\n");
        sb.append("  FontColor #7D6608\n");
        sb.append("  FontSize 12\n");
        sb.append("}\n\n");

        sb.append("skinparam package {\n");
        sb.append("  BackgroundColor #F8F9FA\n");
        sb.append("  BorderColor #ADB5BD\n");
        sb.append("  FontColor #495057\n");
        sb.append("  FontSize 13\n");
        sb.append("  FontStyle bold\n");
        sb.append("}\n\n");

        sb.append("skinparam arrow {\n");
        sb.append("  Color #6C757D\n");
        sb.append("  FontSize 10\n");
        sb.append("  Thickness 1.5\n");
        sb.append("}\n\n");
    }
}
