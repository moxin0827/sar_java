package com.example.javaparser.service.llm;

import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.entity.FunctionKnowledge;
import com.example.javaparser.llm.LLMService;
import com.example.javaparser.repository.ClassInfoRepository;
import com.example.javaparser.repository.FunctionKnowledgeRepository;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.javaparser.config.RecoveryProperties;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FskGenerationService {

    @Autowired
    private LLMService llmService;

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private FunctionKnowledgeRepository functionKnowledgeRepository;

    @Autowired
    private RecoveryProperties recoveryProperties;

    private final Gson gson = new Gson();

    public List<FunctionKnowledge> generateFsk(Long projectId, String projectName) {
        log.info("开始生成FSK: projectId={}", projectId);

        List<ClassInfo> classes = classInfoRepository.findByProjectId(projectId);
        if (classes.isEmpty()) {
            log.warn("项目无类信息，跳过FSK生成");
            return new ArrayList<>();
        }

        // 按包分组构建摘要
        Map<String, List<ClassInfo>> byPackage = classes.stream()
                .collect(Collectors.groupingBy(c -> c.getPackageName() != null ? c.getPackageName() : "default"));

        // 优化：大型项目按包均匀采样，确保每个包都有代表性类
        int maxSummaries = recoveryProperties.getFskMaxSamples() != null ?
                recoveryProperties.getFskMaxSamples() : 80;
        List<String> classSummaries = new ArrayList<>();

        if (classes.size() <= maxSummaries) {
            // 小项目：全部包含
            for (Map.Entry<String, List<ClassInfo>> entry : byPackage.entrySet()) {
                for (ClassInfo ci : entry.getValue()) {
                    classSummaries.add(buildClassSummary(ci));
                }
            }
        } else {
            // 大型项目：分层采样策略（优先Service层，其次Controller/Entity/Repository）
            classSummaries = sampleByArchitectureLayer(classes, byPackage, maxSummaries);
            log.info("大型项目分层采样: {} 个类 -> {} 个摘要（上限={}）",
                    classes.size(), classSummaries.size(), maxSummaries);
        }

        log.info("准备调用LLM生成FSK, 类数量: {}, 摘要数量: {}", classes.size(), classSummaries.size());
        String response = llmService.generateFskForClasses(
                projectName != null ? projectName : "Unknown", classSummaries, classes.size());

        if (response == null || response.trim().isEmpty()) {
            log.error("LLM返回空响应，无法生成FSK。请检查LLM配置（API Key、模型、max-tokens等）");
            return new ArrayList<>();
        }
        log.debug("LLM原始响应(前500字符): {}", response.length() > 500 ? response.substring(0, 500) : response);

        List<FunctionKnowledge> results = parseFskResponse(projectId, response);

        // 保存子节点（父节点已在parseFskResponse中保存）
        List<FunctionKnowledge> unsaved = results.stream()
                .filter(fk -> fk.getId() == null)
                .collect(Collectors.toList());
        functionKnowledgeRepository.saveAll(unsaved);
        log.info("FSK生成完成: {} 个功能节点", results.size());

        return results;
    }

    private List<FunctionKnowledge> parseFskResponse(Long projectId, String response) {
        List<FunctionKnowledge> results = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return results;
        }

        try {
            String jsonStr = extractJson(response);
            log.debug("提取的JSON(前500字符): {}", jsonStr.length() > 500 ? jsonStr.substring(0, 500) : jsonStr);
            JsonObject root = gson.fromJson(jsonStr, JsonObject.class);

            if (!root.has("functions")) {
                log.warn("LLM响应JSON中缺少'functions'字段, 实际字段: {}", root.keySet());
                return results;
            }

            JsonArray functions = root.getAsJsonArray("functions");
            log.info("解析到 {} 个功能节点", functions.size());

            for (int i = 0; i < functions.size(); i++) {
                JsonObject func = functions.get(i).getAsJsonObject();
                FunctionKnowledge parent = createFkFromJson(projectId, func, null);
                // 先保存parent以获取自增ID，供children引用
                functionKnowledgeRepository.save(parent);
                results.add(parent);

                // 处理子功能
                if (func.has("children")) {
                    JsonArray children = func.getAsJsonArray("children");
                    for (int j = 0; j < children.size(); j++) {
                        JsonObject child = children.get(j).getAsJsonObject();
                        FunctionKnowledge childFk = createFkFromJson(projectId, child, parent.getId());
                        results.add(childFk);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析FSK响应失败", e);
        }

        return results;
    }

    private FunctionKnowledge createFkFromJson(Long projectId, JsonObject obj, Long parentId) {
        FunctionKnowledge fk = new FunctionKnowledge();
        fk.setProjectId(projectId);
        fk.setParentFunctionId(parentId);
        fk.setFunctionName(obj.has("name") ? obj.get("name").getAsString() : "Unknown");
        fk.setDescription(obj.has("description") ? obj.get("description").getAsString() : "");
        fk.setIsLeaf(obj.has("isLeaf") ? obj.get("isLeaf").getAsBoolean() : (parentId != null));
        fk.setSource("LLM");

        if (obj.has("relatedTerms")) {
            JsonArray terms = obj.getAsJsonArray("relatedTerms");
            List<String> termList = new ArrayList<>();
            terms.forEach(t -> termList.add(t.getAsString()));
            fk.setRelatedTerms(String.join(",", termList));
        }

        if (obj.has("relatedClasses")) {
            JsonArray classes = obj.getAsJsonArray("relatedClasses");
            List<String> classList = new ArrayList<>();
            classes.forEach(c -> classList.add(c.getAsString()));
            fk.setRelatedClassNames(String.join(",", classList));
        }

        return fk;
    }

    private String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private String buildClassSummary(ClassInfo ci) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(ci.getPackageName()).append("] ");
        sb.append(ci.getSimpleName());
        if (ci.getFunctionalSummary() != null) {
            sb.append(" - ").append(ci.getFunctionalSummary());
        } else if (ci.getJavadocComment() != null) {
            String doc = ci.getJavadocComment();
            sb.append(" - ").append(doc.length() > 100 ? doc.substring(0, 100) : doc);
        }
        return sb.toString();
    }

    /**
     * 分层采样策略：按架构层次分配采样配额
     * Service层（核心业务）> Controller层 > Entity层 > Repository层 > 其他
     */
    private List<String> sampleByArchitectureLayer(List<ClassInfo> allClasses,
                                                     Map<String, List<ClassInfo>> byPackage,
                                                     int maxSamples) {
        // 按架构层次分类
        Map<String, List<ClassInfo>> byLayer = new HashMap<>();
        byLayer.put("service", new ArrayList<>());
        byLayer.put("controller", new ArrayList<>());
        byLayer.put("entity", new ArrayList<>());
        byLayer.put("repository", new ArrayList<>());
        byLayer.put("other", new ArrayList<>());

        for (ClassInfo ci : allClasses) {
            String pkg = ci.getPackageName() != null ? ci.getPackageName().toLowerCase() : "";
            String name = ci.getSimpleName().toLowerCase();

            if (pkg.contains("service") || name.contains("service")) {
                byLayer.get("service").add(ci);
            } else if (pkg.contains("controller") || pkg.contains("web") || name.contains("controller")) {
                byLayer.get("controller").add(ci);
            } else if (pkg.contains("entity") || pkg.contains("model") || pkg.contains("domain")) {
                byLayer.get("entity").add(ci);
            } else if (pkg.contains("repository") || pkg.contains("dao") || name.contains("repository")) {
                byLayer.get("repository").add(ci);
            } else {
                byLayer.get("other").add(ci);
            }
        }

        // 分配采样配额（Service层占比最高）
        Map<String, Integer> layerQuota = new HashMap<>();
        layerQuota.put("service", (int) (maxSamples * 0.35));      // 35%
        layerQuota.put("controller", (int) (maxSamples * 0.20));   // 20%
        layerQuota.put("entity", (int) (maxSamples * 0.20));       // 20%
        layerQuota.put("repository", (int) (maxSamples * 0.15));   // 15%
        layerQuota.put("other", (int) (maxSamples * 0.10));        // 10%

        List<String> result = new ArrayList<>();

        // 按优先级采样
        for (String layer : Arrays.asList("service", "controller", "entity", "repository", "other")) {
            List<ClassInfo> layerClasses = byLayer.get(layer);
            int quota = layerQuota.get(layer);

            if (layerClasses.isEmpty()) continue;

            // 优先选择有功能摘要或javadoc的类
            layerClasses.sort((a, b) -> {
                int scoreA = (a.getFunctionalSummary() != null ? 2 : 0)
                           + (a.getJavadocComment() != null ? 1 : 0);
                int scoreB = (b.getFunctionalSummary() != null ? 2 : 0)
                           + (b.getJavadocComment() != null ? 1 : 0);
                return Integer.compare(scoreB, scoreA);
            });

            int limit = Math.min(quota, layerClasses.size());
            for (int i = 0; i < limit; i++) {
                result.add(buildClassSummary(layerClasses.get(i)));
            }

            log.debug("分层采样 - {}: {} 个类, 采样 {} 个", layer, layerClasses.size(), limit);
        }

        return result;
    }
}
