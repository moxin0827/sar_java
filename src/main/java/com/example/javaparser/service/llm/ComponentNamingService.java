package com.example.javaparser.service.llm;

import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.RecoveredComponent;
import com.example.javaparser.llm.LLMService;
import com.example.javaparser.repository.ClassInfoRepository;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ComponentNamingService {

    @Autowired
    private LLMService llmService;

    @Autowired
    private ClassInfoRepository classInfoRepository;

    private final Gson gson = new Gson();

    /** 单次批量命名的最大组件数（避免prompt过长） */
    private static final int BATCH_CHUNK_SIZE = 30;

    /**
     * 批量命名所有组件（优化：智能跳过 + 分块批量LLM调用）
     *
     * @param components      待命名的组件列表
     * @param preloadedClasses 预加载的ClassInfo列表（避免重复查询）
     */
    public void batchGenerateNames(List<RecoveredComponent> components, List<ClassInfo> preloadedClasses) {
        if (components == null || components.isEmpty()) {
            return;
        }

        // 构建FQN -> ClassInfo的索引
        Map<String, ClassInfo> classIndex = new HashMap<>();
        if (preloadedClasses != null) {
            for (ClassInfo ci : preloadedClasses) {
                classIndex.put(ci.getFullyQualifiedName(), ci);
            }
        }

        // 智能跳过：过滤出需要优化命名的组件
        List<RecoveredComponent> needsNaming = new ArrayList<>();
        int skippedCount = 0;
        for (RecoveredComponent comp : components) {
            if (needsOptimization(comp)) {
                needsNaming.add(comp);
            } else {
                skippedCount++;
            }
        }

        if (skippedCount > 0) {
            log.info("组件命名智能跳过: {} 个已有良好命名, {} 个需要优化", skippedCount, needsNaming.size());
        }

        if (needsNaming.isEmpty()) {
            log.info("所有组件命名已足够好，跳过LLM优化");
            return;
        }

        // 如果组件数量少（<=3），逐个命名即可
        if (needsNaming.size() <= 3) {
            for (RecoveredComponent comp : needsNaming) {
                try {
                    String name = generateNameFromIndex(comp, classIndex);
                    comp.setName(name);
                } catch (Exception e) {
                    log.warn("组件命名优化失败: {}", comp.getName(), e);
                }
            }
            return;
        }

        // 分块批量命名：每块最多BATCH_CHUNK_SIZE个组件，避免prompt过长
        int totalChunks = (needsNaming.size() + BATCH_CHUNK_SIZE - 1) / BATCH_CHUNK_SIZE;
        if (totalChunks > 1) {
            log.info("组件命名分 {} 块批量处理（每块最多 {} 个）", totalChunks, BATCH_CHUNK_SIZE);
        }

        for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
            int start = chunkIdx * BATCH_CHUNK_SIZE;
            int end = Math.min(start + BATCH_CHUNK_SIZE, needsNaming.size());
            List<RecoveredComponent> chunk = needsNaming.subList(start, end);

            if (!batchNameChunk(chunk, classIndex)) {
                // 回退：逐个命名该块
                for (RecoveredComponent comp : chunk) {
                    try {
                        String name = generateNameFromIndex(comp, classIndex);
                        comp.setName(name);
                    } catch (Exception e) {
                        log.warn("组件命名优化失败: {}", comp.getName(), e);
                    }
                }
            }
        }
    }

    /**
     * 判断组件是否需要LLM优化命名
     */
    private boolean needsOptimization(RecoveredComponent comp) {
        String name = comp.getName();
        if (name == null || name.isEmpty()) return true;

        // 自动生成的名称需要优化
        if (name.startsWith("Cluster_") || name.startsWith("FSK_") || name.startsWith("Misc_")) {
            return true;
        }

        // 纯包名片段（如 com_example_service）需要优化
        if (name.contains("_") && name.contains(".")) {
            return true;
        }

        // 已经是有意义的驼峰命名，不需要优化
        if (name.matches("[A-Z][a-zA-Z0-9]+") && name.length() >= 4) {
            return false;
        }

        return true;
    }

    /**
     * 对一块组件执行批量LLM命名
     * @return true=成功, false=失败需回退
     */
    private boolean batchNameChunk(List<RecoveredComponent> chunk, Map<String, ClassInfo> classIndex) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一位资深Java架构师。请为以下 ").append(chunk.size())
                  .append(" 个软件组件优化命名，以JSON格式返回。\n\n");
            prompt.append("命名规范：使用英文驼峰命名法，名称应简洁（1-3个单词）且能反映组件的核心功能。\n");
            prompt.append("示例：UserManagement, OrderProcessing, DataPersistence\n\n");

            for (int i = 0; i < chunk.size(); i++) {
                RecoveredComponent comp = chunk.get(i);
                prompt.append("组件 ").append(i + 1).append(": 当前名称=").append(comp.getName());

                if (comp.getClassNames() != null) {
                    List<String> classNames = Arrays.asList(comp.getClassNames().split(","));
                    // 列出关键类名和摘要（最多5个）
                    List<String> summaries = new ArrayList<>();
                    for (String fqn : classNames) {
                        if (summaries.size() >= 5) break;
                        ClassInfo ci = classIndex.get(fqn.trim());
                        if (ci != null) {
                            String s = ci.getSimpleName();
                            if (ci.getFunctionalSummary() != null) {
                                s += "(" + ci.getFunctionalSummary() + ")";
                            }
                            summaries.add(s);
                        } else {
                            int dot = fqn.lastIndexOf('.');
                            summaries.add(dot > 0 ? fqn.substring(dot + 1) : fqn);
                        }
                    }
                    prompt.append(", 包含类: ").append(String.join(", ", summaries));
                    if (classNames.size() > 5) {
                        prompt.append(" 等共").append(classNames.size()).append("个类");
                    }
                }
                prompt.append("\n");
            }

            prompt.append("\n请以JSON格式返回所有组件的优化名称：\n");
            prompt.append("{\"names\": [\"组件1名称\", \"组件2名称\", ...]}\n");
            prompt.append("数组中的名称顺序必须与上面的组件顺序一一对应。");

            String response = llmService.callLLMPublic(prompt.toString());
            List<String> names = parseBatchNames(response, chunk.size());

            if (names != null && names.size() == chunk.size()) {
                for (int i = 0; i < chunk.size(); i++) {
                    String name = names.get(i);
                    if (isValidName(name)) {
                        chunk.get(i).setName(name);
                    }
                }
                log.info("批量命名成功: {} 个组件", chunk.size());
                return true;
            }
        } catch (Exception e) {
            log.warn("批量命名块失败，回退到逐个命名", e);
        }
        return false;
    }

    /**
     * 单个组件命名（使用预构建的classIndex）
     */
    private String generateNameFromIndex(RecoveredComponent component, Map<String, ClassInfo> classIndex) {
        if (component.getClassNames() == null || component.getClassNames().isEmpty()) {
            return "UnknownComponent";
        }

        List<String> classNames = Arrays.asList(component.getClassNames().split(","));
        Set<String> classSet = new HashSet<>(classNames);

        StringBuilder desc = new StringBuilder();
        classNames.stream()
                .limit(10)
                .forEach(fqn -> {
                    ClassInfo ci = classIndex.get(fqn.trim());
                    if (ci != null) {
                        desc.append(ci.getSimpleName());
                        if (ci.getFunctionalSummary() != null) {
                            desc.append("(").append(ci.getFunctionalSummary()).append(")");
                        }
                        desc.append(", ");
                    }
                });

        return llmService.optimizeModuleName(component.getName(), classSet, desc.toString());
    }

    /**
     * 单个组件命名（兼容旧接口）
     */
    public String generateName(RecoveredComponent component, Long projectId) {
        if (component.getClassNames() == null || component.getClassNames().isEmpty()) {
            return "UnknownComponent";
        }

        List<String> classNames = Arrays.asList(component.getClassNames().split(","));
        Set<String> classSet = new HashSet<>(classNames);

        StringBuilder desc = new StringBuilder();
        List<ClassInfo> classes = classInfoRepository.findByProjectId(projectId);
        classes.stream()
                .filter(c -> classSet.contains(c.getFullyQualifiedName()))
                .limit(10)
                .forEach(c -> {
                    desc.append(c.getSimpleName());
                    if (c.getFunctionalSummary() != null) {
                        desc.append("(").append(c.getFunctionalSummary()).append(")");
                    }
                    desc.append(", ");
                });

        return llmService.optimizeModuleName(
                component.getName(), classSet, desc.toString());
    }

    private List<String> parseBatchNames(String response, int expectedSize) {
        if (response == null || response.trim().isEmpty()) return null;
        try {
            String jsonStr = response.trim();
            int start = jsonStr.indexOf('{');
            int end = jsonStr.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end + 1);
            }
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            if (obj.has("names")) {
                JsonArray arr = obj.getAsJsonArray("names");
                if (arr.size() == expectedSize) {
                    List<String> names = new ArrayList<>();
                    arr.forEach(e -> names.add(e.getAsString().trim()));
                    return names;
                }
            }
        } catch (Exception e) {
            log.debug("解析批量命名响应失败: {}", e.getMessage());
        }
        return null;
    }

    private boolean isValidName(String name) {
        return name != null && !name.isEmpty() && name.matches("[a-zA-Z][a-zA-Z0-9_]*");
    }
}
