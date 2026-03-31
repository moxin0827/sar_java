package com.example.javaparser.llm;

import com.example.javaparser.config.LLMConfig;
import com.example.javaparser.model.CodeEntity;
import com.example.javaparser.model.SemanticInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM服务 - 调用大语言模型进行语义分析
 */
@Slf4j
@Service
public class LLMService {

    @Autowired
    private LLMConfig llmConfig;

    private final Gson gson = new Gson();

    // LLM响应缓存目录
    private static final String CACHE_DIR = ".llm-cache";

    /**
     * 获取缓存的LLM响应，如果缓存不存在则返回null
     */
    private String getCachedResponse(String cacheKey) {
        try {
            Path cacheFile = Paths.get(CACHE_DIR, cacheKey + ".json");
            if (Files.exists(cacheFile)) {
                String cached = new String(Files.readAllBytes(cacheFile), StandardCharsets.UTF_8);
                log.debug("缓存命中: {}", cacheKey);
                return cached;
            }
        } catch (Exception e) {
            log.debug("读取缓存失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将LLM响应写入缓存
     */
    private void putCachedResponse(String cacheKey, String response) {
        try {
            Path cacheDir = Paths.get(CACHE_DIR);
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }
            Path cacheFile = cacheDir.resolve(cacheKey + ".json");
            Files.write(cacheFile, response.getBytes(StandardCharsets.UTF_8));
            log.debug("缓存写入: {}", cacheKey);
        } catch (Exception e) {
            log.debug("写入缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 生成实体的缓存key（基于类名+包名+方法列表hash）
     */
    private String generateCacheKey(CodeEntity entity) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(entity.getQualifiedName()).append("|");
            entity.getMethods().stream()
                .map(CodeEntity.MethodInfo::getName)
                .sorted()
                .forEach(m -> sb.append(m).append(","));
            sb.append("|");
            entity.getFields().stream()
                .map(CodeEntity.FieldInfo::getName)
                .sorted()
                .forEach(f -> sb.append(f).append(","));

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return entity.getQualifiedName().replace('.', '_');
        }
    }

    /**
     * 提取代码实体的语义信息（带缓存）
     */
    public SemanticInfo extractSemanticInfo(CodeEntity entity) {
        log.debug("提取实体语义信息: {}", entity.getQualifiedName());

        // 检查缓存
        String cacheKey = generateCacheKey(entity);
        String cached = getCachedResponse(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            SemanticInfo info = parseSemanticInfo(cached);
            if (info.getFunctionalDescription() != null && !info.getFunctionalDescription().isEmpty()) {
                return info;
            }
        }

        String prompt = buildSemanticExtractionPrompt(entity);
        String response = callLLM(prompt);

        // 写入缓存
        if (response != null && !response.isEmpty()) {
            putCachedResponse(cacheKey, response);
        }

        return parseSemanticInfo(response);
    }

    /**
     * 批量提取语义信息（改进版：批量Prompt + 缓存 + 并行处理）
     * 将5-10个实体合并到一个Prompt中，减少LLM调用次数5-10倍
     */
    public Map<String, SemanticInfo> extractSemanticInfoBatch(List<CodeEntity> entities) {
        Map<String, SemanticInfo> results = new java.util.concurrent.ConcurrentHashMap<>();

        if (entities.isEmpty()) {
            return results;
        }

        log.info("开始批量提取语义信息，共 {} 个实体", entities.size());

        // 过滤：只对重要实体提取语义信息
        List<CodeEntity> importantEntities = entities.stream()
            .filter(this::isImportantEntity)
            .collect(Collectors.toList());

        log.info("过滤后需要提取语义的实体: {} 个", importantEntities.size());

        // 先从缓存中加载已有结果
        List<CodeEntity> uncachedEntities = new ArrayList<>();
        for (CodeEntity entity : importantEntities) {
            String cacheKey = generateCacheKey(entity);
            String cached = getCachedResponse(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                SemanticInfo info = parseSemanticInfo(cached);
                if (info.getFunctionalDescription() != null && !info.getFunctionalDescription().isEmpty()) {
                    results.put(entity.getQualifiedName(), info);
                    continue;
                }
            }
            uncachedEntities.add(entity);
        }

        log.info("缓存命中 {} 个，需要调用LLM: {} 个", results.size(), uncachedEntities.size());

        if (!uncachedEntities.isEmpty()) {
            // 按批次处理（每批5个实体）
            int batchSize = 5;
            List<List<CodeEntity>> batches = new ArrayList<>();
            for (int i = 0; i < uncachedEntities.size(); i += batchSize) {
                batches.add(uncachedEntities.subList(i, Math.min(i + batchSize, uncachedEntities.size())));
            }

            log.info("分为 {} 个批次处理", batches.size());

            // 使用线程池并行处理批次
            int threadPoolSize = Math.min(6, batches.size());
            java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(threadPoolSize);

            java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(batches.size());

            for (List<CodeEntity> batch : batches) {
                executor.submit(() -> {
                    try {
                        Map<String, SemanticInfo> batchResults = extractSemanticInfoForBatch(batch);
                        results.putAll(batchResults);
                        Thread.sleep(500); // 批次间延迟
                    } catch (Exception e) {
                        log.error("批量提取语义信息失败", e);
                        for (CodeEntity entity : batch) {
                            results.putIfAbsent(entity.getQualifiedName(), createDefaultSemanticInfo(entity));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await(15, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                log.error("等待语义提取完成时被中断", e);
                Thread.currentThread().interrupt();
            } finally {
                executor.shutdown();
            }
        }

        // 为其他实体使用默认语义信息
        for (CodeEntity entity : entities) {
            if (!results.containsKey(entity.getQualifiedName())) {
                results.put(entity.getQualifiedName(), createDefaultSemanticInfo(entity));
            }
        }

        log.info("语义信息提取完成，成功提取 {} 个", results.size());
        return results;
    }

    /**
     * 为一批实体提取语义信息（合并到一个Prompt中）
     */
    private Map<String, SemanticInfo> extractSemanticInfoForBatch(List<CodeEntity> batch) {
        Map<String, SemanticInfo> results = new HashMap<>();

        if (batch.size() == 1) {
            // 单个实体直接调用
            CodeEntity entity = batch.get(0);
            SemanticInfo info = extractSemanticInfo(entity);
            results.put(entity.getQualifiedName(), info);
            return results;
        }

        // 构建批量Prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位资深Java架构师。请分析以下 ").append(batch.size())
              .append(" 个Java类的功能语义，以JSON数组格式返回每个类的分析结果。\n\n");

        for (int i = 0; i < batch.size(); i++) {
            CodeEntity entity = batch.get(i);
            prompt.append("--- 类 ").append(i + 1).append(" ---\n");
            prompt.append("类名: ").append(entity.getQualifiedName()).append("\n");
            if (entity.getComment() != null && !entity.getComment().isEmpty()) {
                String comment = entity.getComment();
                if (comment.length() > 150) comment = comment.substring(0, 150) + "...";
                prompt.append("注释: ").append(comment).append("\n");
            }
            if (!entity.getAnnotations().isEmpty()) {
                prompt.append("注解: ").append(String.join(", ", entity.getAnnotations())).append("\n");
            }
            if (!entity.getMethods().isEmpty()) {
                String methods = entity.getMethods().stream()
                    .filter(m -> m.isPublic())
                    .limit(3)
                    .map(CodeEntity.MethodInfo::getName)
                    .collect(Collectors.joining(", "));
                if (!methods.isEmpty()) {
                    prompt.append("方法: ").append(methods).append("\n");
                }
            }
            prompt.append("\n");
        }

        prompt.append("必须返回以下JSON格式（数组中每个元素对应一个类，按顺序）：\n");
        prompt.append("{\n  \"results\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"className\": \"完全限定类名\",\n");
        prompt.append("      \"functionalDescription\": \"简短描述\",\n");
        prompt.append("      \"businessDomain\": \"领域\",\n");
        prompt.append("      \"keyTerms\": [\"术语1\"],\n");
        prompt.append("      \"functionalTags\": [\"标签1\"],\n");
        prompt.append("      \"architectureLayer\": \"PRESENTATION|SERVICE|DOMAIN|INFRASTRUCTURE|UTILITY|UNKNOWN\",\n");
        prompt.append("      \"designPatterns\": [\"模式\"],\n");
        prompt.append("      \"responsibility\": \"职责\"\n");
        prompt.append("    }\n  ]\n}\n");

        String response = callLLM(prompt.toString());

        // 解析批量响应
        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            JsonArray resultsArray = obj.has("results") ? obj.getAsJsonArray("results") : null;

            if (resultsArray != null) {
                for (int i = 0; i < resultsArray.size() && i < batch.size(); i++) {
                    JsonObject item = resultsArray.get(i).getAsJsonObject();
                    SemanticInfo info = parseSemanticInfoFromJson(item);

                    // 确定对应的实体
                    CodeEntity entity;
                    if (item.has("className")) {
                        String className = item.get("className").getAsString();
                        entity = batch.stream()
                            .filter(e -> e.getQualifiedName().equals(className))
                            .findFirst()
                            .orElse(batch.get(i));
                    } else {
                        entity = batch.get(i);
                    }

                    results.put(entity.getQualifiedName(), info);

                    // 写入缓存
                    String cacheKey = generateCacheKey(entity);
                    putCachedResponse(cacheKey, item.toString());
                }
            }
        } catch (Exception e) {
            log.warn("批量解析失败，回退到逐个处理: {}", e.getMessage());
            // 回退：逐个处理
            for (CodeEntity entity : batch) {
                try {
                    SemanticInfo info = extractSemanticInfo(entity);
                    results.put(entity.getQualifiedName(), info);
                } catch (Exception ex) {
                    results.put(entity.getQualifiedName(), createDefaultSemanticInfo(entity));
                }
            }
        }

        // 确保所有实体都有结果
        for (CodeEntity entity : batch) {
            results.putIfAbsent(entity.getQualifiedName(), createDefaultSemanticInfo(entity));
        }

        return results;
    }

    /**
     * 从JsonObject解析SemanticInfo
     */
    private SemanticInfo parseSemanticInfoFromJson(JsonObject json) {
        SemanticInfo info = new SemanticInfo();
        try {
            if (json.has("functionalDescription")) {
                info.setFunctionalDescription(json.get("functionalDescription").getAsString());
            }
            if (json.has("businessDomain")) {
                info.setBusinessDomain(json.get("businessDomain").getAsString());
            }
            if (json.has("keyTerms")) {
                JsonArray terms = json.getAsJsonArray("keyTerms");
                List<String> keyTerms = new ArrayList<>();
                terms.forEach(t -> keyTerms.add(t.getAsString()));
                info.setKeyTerms(keyTerms);
            }
            if (json.has("functionalTags")) {
                JsonArray tags = json.getAsJsonArray("functionalTags");
                List<String> functionalTags = new ArrayList<>();
                tags.forEach(t -> functionalTags.add(t.getAsString()));
                info.setFunctionalTags(functionalTags);
            }
            if (json.has("architectureLayer")) {
                String layer = json.get("architectureLayer").getAsString();
                try {
                    info.setArchitectureLayer(SemanticInfo.ArchitectureLayer.valueOf(layer));
                } catch (Exception e) {
                    info.setArchitectureLayer(SemanticInfo.ArchitectureLayer.UNKNOWN);
                }
            }
            if (json.has("designPatterns")) {
                JsonArray patterns = json.getAsJsonArray("designPatterns");
                List<String> designPatterns = new ArrayList<>();
                patterns.forEach(p -> designPatterns.add(p.getAsString()));
                info.setDesignPatterns(designPatterns);
            }
            if (json.has("responsibility")) {
                info.setResponsibility(json.get("responsibility").getAsString());
            }
        } catch (Exception e) {
            log.debug("解析SemanticInfo字段失败: {}", e.getMessage());
        }
        return info;
    }

    /**
     * 判断实体是否重要（改进版：避免误过滤核心业务类）
     */
    private boolean isImportantEntity(CodeEntity entity) {
        // 跳过测试类
        if (entity.getName().endsWith("Test") ||
            entity.getName().endsWith("Tests") ||
            entity.getPackageName().contains("test")) {
            return false;
        }

        // 关键词白名单：包含这些关键词的类始终保留
        String name = entity.getName();
        Set<String> importantKeywords = new HashSet<>(Arrays.asList(
            "Service", "Manager", "Handler", "Processor", "Controller",
            "Repository", "Dao", "Factory", "Builder", "Strategy",
            "Validator", "Converter", "Adapter", "Listener", "Observer"
        ));
        for (String keyword : importantKeywords) {
            if (name.contains(keyword)) {
                return true;
            }
        }

        // 有业务注解的类始终保留（包括Config类）
        Set<String> businessAnnotations = new HashSet<>(Arrays.asList(
            "Service", "Controller", "RestController", "Repository",
            "Component", "Configuration", "Bean"
        ));
        for (String annotation : entity.getAnnotations()) {
            if (businessAnnotations.contains(annotation)) {
                return true;
            }
        }

        // 跳过配置类（仅当没有业务注解时）
        if (name.endsWith("Config") || name.endsWith("Configuration")) {
            return false;
        }

        // 改进的POJO判定：字段>5 且方法<3 且无业务注解 且无继承关系
        if (entity.getFields().size() > 5 && entity.getMethods().size() < 3) {
            // 有继承关系的不跳过（可能是领域实体）
            if (entity.getSuperClass() != null || !entity.getInterfaces().isEmpty()) {
                return true;
            }
            return false;
        }

        // 有注释的类
        if (entity.getComment() != null && !entity.getComment().isEmpty()) {
            return true;
        }

        // 有注解的类
        if (!entity.getAnnotations().isEmpty()) {
            return true;
        }

        // 有公共方法的类
        long publicMethodCount = entity.getMethods().stream()
            .filter(CodeEntity.MethodInfo::isPublic)
            .count();

        return publicMethodCount > 0;
    }

    /**
     * 优化模块命名
     */
    public String optimizeModuleName(String originalName, Set<String> entities, String description) {
        log.debug("优化模块命名: {}", originalName);

        String prompt = buildNamingOptimizationPrompt(originalName, entities, description);
        String response = callLLM(prompt);

        return parseModuleName(response);
    }

    /**
     * 识别功能模块
     */
    public List<String> identifyFunctionalModules(List<CodeEntity> entities) {
        log.debug("识别功能模块，实体数量: {}", entities.size());

        String prompt = buildModuleIdentificationPrompt(entities);
        String response = callLLM(prompt);

        return parseModuleList(response);
    }

    /**
     * 计算语义相似度
     */
    public double calculateSemanticSimilarity(CodeEntity entity1, CodeEntity entity2) {
        String prompt = buildSimilarityPrompt(entity1, entity2);
        String response = callLLM(prompt);

        return parseSimilarityScore(response);
    }

    /**
     * 构建语义提取提示词（优化版：确保返回 JSON）
     */
    private String buildSemanticExtractionPrompt(CodeEntity entity) {
        StringBuilder prompt = new StringBuilder();

        // 系统角色设定
        prompt.append("你是一位资深Java架构师，擅长分析代码结构和识别架构模式。");
        prompt.append("请分析以下Java类的功能语义，并以JSON格式返回分析结果。\n\n");

        // Few-shot示例
        prompt.append("示例：\n");
        prompt.append("输入: 类=UserService, 包=com.example.service, 注解=@Service, 方法=createUser,findById,deleteUser\n");
        prompt.append("输出: {\"functionalDescription\":\"用户管理服务，提供用户的增删查改操作\",");
        prompt.append("\"businessDomain\":\"用户管理\",\"keyTerms\":[\"用户\",\"CRUD\"],");
        prompt.append("\"functionalTags\":[\"用户管理\",\"业务服务\"],\"architectureLayer\":\"SERVICE\",");
        prompt.append("\"designPatterns\":[\"Service Layer\"],\"responsibility\":\"管理用户生命周期\"}\n\n");

        // 实际输入
        prompt.append("请分析：\n");
        prompt.append("类: ").append(entity.getName()).append("\n");
        prompt.append("包: ").append(entity.getPackageName()).append("\n");

        if (entity.getComment() != null && !entity.getComment().isEmpty()) {
            String comment = entity.getComment();
            if (comment.length() > 200) {
                comment = comment.substring(0, 200) + "...";
            }
            prompt.append("注释: ").append(comment).append("\n");
        }

        if (!entity.getAnnotations().isEmpty()) {
            prompt.append("注解: ").append(String.join(", ", entity.getAnnotations())).append("\n");
        }

        if (!entity.getMethods().isEmpty()) {
            prompt.append("方法: ");
            String methods = entity.getMethods().stream()
                    .filter(m -> m.isPublic())
                    .limit(5)
                    .map(CodeEntity.MethodInfo::getName)
                    .collect(Collectors.joining(", "));
            prompt.append(methods).append("\n");
        }

        if (!entity.getFields().isEmpty()) {
            String fields = entity.getFields().stream()
                    .limit(5)
                    .map(f -> f.getType() + " " + f.getName())
                    .collect(Collectors.joining(", "));
            prompt.append("字段: ").append(fields).append("\n");
        }

        // 继承信息
        if (entity.getSuperClass() != null) {
            prompt.append("父类: ").append(entity.getSuperClass()).append("\n");
        }
        if (!entity.getInterfaces().isEmpty()) {
            prompt.append("实现接口: ").append(String.join(", ", entity.getInterfaces())).append("\n");
        }

        prompt.append("\n请先分析该类的依赖关系和职责，然后返回以下JSON格式：\n");
        prompt.append("{\n");
        prompt.append("  \"functionalDescription\": \"简短功能描述(1-2句话)\",\n");
        prompt.append("  \"businessDomain\": \"所属业务领域\",\n");
        prompt.append("  \"keyTerms\": [\"关键术语1\", \"关键术语2\"],\n");
        prompt.append("  \"functionalTags\": [\"功能标签1\", \"功能标签2\"],\n");
        prompt.append("  \"architectureLayer\": \"PRESENTATION|SERVICE|DOMAIN|INFRASTRUCTURE|UTILITY|UNKNOWN\",\n");
        prompt.append("  \"designPatterns\": [\"使用的设计模式\"],\n");
        prompt.append("  \"responsibility\": \"单一职责描述\"\n");
        prompt.append("}");

        return prompt.toString();
    }

    /**
     * 构建命名优化提示词
     */
    private String buildNamingOptimizationPrompt(String originalName, Set<String> entities, String description) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位资深Java架构师。请为以下功能模块提供一个更好的命名，并以JSON格式返回。\n\n");
        prompt.append("命名规范：使用英文驼峰命名法，名称应简洁且能反映模块的核心功能。\n");
        prompt.append("示例：UserManagement, OrderProcessing, AuthenticationService\n\n");
        prompt.append("当前名称: ").append(originalName).append("\n");
        prompt.append("模块描述: ").append(description != null ? description : "无").append("\n");
        prompt.append("\n包含的类:\n");
        entities.stream().limit(10).forEach(e -> prompt.append("- ").append(e).append("\n"));

        prompt.append("\n请以JSON格式返回：{\"moduleName\": \"优化后的模块名称(英文驼峰命名法)\"}");

        return prompt.toString();
    }

    /**
     * 构建模块识别提示词（改进版：添加角色设定和Chain-of-Thought）
     */
    private String buildModuleIdentificationPrompt(List<CodeEntity> entities) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位资深Java架构师，擅长识别代码中的功能模块和架构模式。\n");
        prompt.append("请分析以下Java类，先列出它们之间的依赖关系，再推理哪些类共享职责，最后给出功能模块分组。\n\n");

        prompt.append("示例分组：Spring MVC项目通常有Controller层、Service层、Repository层。\n\n");

        entities.stream().limit(20).forEach(entity -> {
            prompt.append("- ").append(entity.getQualifiedName());
            if (entity.getComment() != null) {
                prompt.append(" // ").append(entity.getComment().substring(0, Math.min(50, entity.getComment().length())));
            }
            prompt.append("\n");
        });

        prompt.append("\n请返回可能的功能模块名称列表（JSON数组格式）：\n");
        prompt.append("[\"模块1\", \"模块2\", \"模块3\"]");

        return prompt.toString();
    }

    /**
     * 构建相似度计算提示词（改进版：添加更多上下文信息）
     */
    private String buildSimilarityPrompt(CodeEntity entity1, CodeEntity entity2) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位资深Java架构师。请评估以下两个Java类的语义相似度。\n");
        prompt.append("考虑因素：是否属于同一业务领域、是否有相似的职责、是否经常一起使用。\n\n");

        prompt.append("类1: ").append(entity1.getQualifiedName()).append("\n");
        if (entity1.getComment() != null) {
            prompt.append("描述: ").append(entity1.getComment()).append("\n");
        }
        if (!entity1.getAnnotations().isEmpty()) {
            prompt.append("注解: ").append(String.join(", ", entity1.getAnnotations())).append("\n");
        }

        prompt.append("\n类2: ").append(entity2.getQualifiedName()).append("\n");
        if (entity2.getComment() != null) {
            prompt.append("描述: ").append(entity2.getComment()).append("\n");
        }
        if (!entity2.getAnnotations().isEmpty()) {
            prompt.append("注解: ").append(String.join(", ", entity2.getAnnotations())).append("\n");
        }

        prompt.append("\n请以JSON格式返回：{\"similarity\": 0.8}（0-1之间的数字，0表示完全无关，1表示功能完全相同）");

        return prompt.toString();
    }

    /**
     * 公开的LLM调用方法（供批量命名等外部服务使用）
     */
    public String callLLMPublic(String prompt) {
        return callLLM(prompt);
    }

    /**
     * 调用LLM API - 支持多种提供商
     */
    private String callLLM(String prompt) {
        if (!llmConfig.getEnabled()) {
            log.warn("LLM功能未启用");
            return "";
        }

        log.debug("callLLM: provider={}, model={}, apiUrl={}, maxTokens={}, timeout={}",
                llmConfig.getProvider(), llmConfig.getActiveModel(),
                llmConfig.getActiveApiUrl(), llmConfig.getActiveMaxTokens(),
                llmConfig.getTimeout());

        String apiKey = llmConfig.getActiveApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.error("LLM API Key为空，请检查配置。provider={}", llmConfig.getProvider());
            return "";
        }

        String result;
        switch (llmConfig.getProvider()) {
            case OPENAI:
                result = callOpenAI(prompt);
                break;
            case ANTHROPIC:
                result = callAnthropic(prompt);
                break;
            case QWEN:
                result = callQwen(prompt);
                break;
            case CUSTOM:
                result = callCustomAPI(prompt);
                break;
            default:
                log.error("不支持的LLM提供商: {}", llmConfig.getProvider());
                return "";
        }

        if (result == null || result.trim().isEmpty()) {
            log.warn("callLLM返回空结果, provider={}, prompt长度={}", llmConfig.getProvider(), prompt.length());
        }
        return result;
    }

    /**
     * 调用OpenAI API (GPT-4o) - 带重试机制
     */
    private String callOpenAI(String prompt) {
        int maxRetries = 3;
        int retryDelay = 3000; // 初始延迟 3 秒

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                URL url = new URL(llmConfig.getActiveApiUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + llmConfig.getActiveApiKey());

                // OpenAI组织ID（可选）
                if (llmConfig.getOpenai().getOrganization() != null) {
                    conn.setRequestProperty("OpenAI-Organization", llmConfig.getOpenai().getOrganization());
                }

                conn.setDoOutput(true);
                conn.setConnectTimeout(llmConfig.getTimeout().intValue());
                conn.setReadTimeout(llmConfig.getTimeout().intValue());

                // 构建OpenAI请求体
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", llmConfig.getActiveModel());
                requestBody.addProperty("temperature", llmConfig.getActiveTemperature());
                requestBody.addProperty("max_tokens", llmConfig.getActiveMaxTokens());

                // 添加 response_format 参数，强制返回有效的 JSON
                JsonObject responseFormat = new JsonObject();
                responseFormat.addProperty("type", "json_object");
                requestBody.add("response_format", responseFormat);

                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);
                requestBody.add("messages", messages);

                log.debug("callOpenAI: url={}, model={}, max_tokens={}, prompt长度={}",
                        url, llmConfig.getActiveModel(), llmConfig.getActiveMaxTokens(), prompt.length());

                // 发送请求
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                // 读取响应
                int responseCode = conn.getResponseCode();
                log.debug("callOpenAI: 响应状态码={}", responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String response = br.lines().collect(Collectors.joining("\n"));
                        log.debug("callOpenAI: 原始响应长度={}", response.length());
                        return extractOpenAIContent(response);
                    }
                } else if (responseCode == 503 || responseCode == 429 || responseCode == 500) {
                    // 503: 服务不可用, 429: 限流, 500: 服务器错误 - 可以重试
                    String errorResponse = readErrorStream(conn);

                    log.warn("OpenAI API调用失败 (尝试 {}/{}), 状态码: {}, 错误: {}",
                            attempt, maxRetries, responseCode, errorResponse);

                    if (attempt < maxRetries) {
                        int currentDelay = retryDelay * attempt; // 指数退避
                        log.info("等待 {} 毫秒后重试...", currentDelay);
                        Thread.sleep(currentDelay);
                        continue; // 重试
                    } else {
                        log.error("OpenAI API调用失败，已达到最大重试次数");
                        return "";
                    }
                } else {
                    // 其他错误不重试
                    String errorResponse = readErrorStream(conn);
                    log.error("OpenAI API调用失败，状态码: {}, 错误详情: {}", responseCode, errorResponse);
                    return "";
                }

            } catch (InterruptedException e) {
                log.error("重试等待被中断", e);
                Thread.currentThread().interrupt();
                return "";
            } catch (java.net.SocketTimeoutException e) {
                // 超时错误单独处理：使用更长的重试延迟
                log.warn("调用OpenAI超时 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        int currentDelay = retryDelay * attempt * 2; // 超时用双倍退避
                        log.info("超时重试，等待 {} 毫秒...", currentDelay);
                        Thread.sleep(currentDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "";
                    }
                } else {
                    log.error("调用OpenAI超时，已达到最大重试次数。建议增大 llm.timeout 配置（当前={}ms）",
                            llmConfig.getTimeout());
                    return "";
                }
            } catch (Exception e) {
                log.error("调用OpenAI失败 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage(), e);
                if (attempt < maxRetries) {
                    try {
                        int currentDelay = retryDelay * attempt;
                        log.info("等待 {} 毫秒后重试...", currentDelay);
                        Thread.sleep(currentDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "";
                    }
                } else {
                    return "";
                }
            }
        }

        return "";
    }

    /**
     * 安全读取HTTP错误流
     */
    private String readErrorStream(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    return br.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            log.debug("读取错误流失败: {}", e.getMessage());
        }
        return "(无法读取错误详情)";
    }

    /**
     * 调用Anthropic API (Claude Sonnet 4.5)
     */
    private String callAnthropic(String prompt) {
        try {
            URL url = new URL(llmConfig.getActiveApiUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", llmConfig.getActiveApiKey());
            conn.setRequestProperty("anthropic-version", llmConfig.getAnthropic().getVersion());
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmConfig.getTimeout().intValue());
            conn.setReadTimeout(llmConfig.getTimeout().intValue());

            // 构建Anthropic请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", llmConfig.getActiveModel());
            requestBody.addProperty("max_tokens", llmConfig.getActiveMaxTokens());
            requestBody.addProperty("temperature", llmConfig.getActiveTemperature());

            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", prompt);
            messages.add(message);
            requestBody.add("messages", messages);

            // 发送请求
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // 读取响应
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = br.lines().collect(Collectors.joining("\n"));
                    return extractAnthropicContent(response);
                }
            } else {
                log.error("Anthropic API调用失败，状态码: {}", responseCode);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String errorResponse = br.lines().collect(Collectors.joining("\n"));
                    log.error("错误详情: {}", errorResponse);
                }
                return "";
            }

        } catch (Exception e) {
            log.error("调用Anthropic失败", e);
            return "";
        }
    }

    /**
     * 调用Qwen API (Qwen3-Max-Thinking)
     */
    private String callQwen(String prompt) {
        try {
            URL url = new URL(llmConfig.getActiveApiUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + llmConfig.getActiveApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmConfig.getTimeout().intValue());
            conn.setReadTimeout(llmConfig.getTimeout().intValue());

            // 构建Qwen请求体（兼容OpenAI格式）
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", llmConfig.getActiveModel());
            requestBody.addProperty("temperature", llmConfig.getActiveTemperature());
            requestBody.addProperty("max_tokens", llmConfig.getActiveMaxTokens());

            // Qwen特有参数
            if (llmConfig.getQwen().getEnableSearch()) {
                JsonObject parameters = new JsonObject();
                parameters.addProperty("enable_search", true);
                requestBody.add("parameters", parameters);
            }

            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", prompt);
            messages.add(message);
            requestBody.add("messages", messages);

            // 发送请求
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // 读取响应
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = br.lines().collect(Collectors.joining("\n"));
                    return extractQwenContent(response);
                }
            } else {
                log.error("Qwen API调用失败，状态码: {}", responseCode);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String errorResponse = br.lines().collect(Collectors.joining("\n"));
                    log.error("错误详情: {}", errorResponse);
                }
                return "";
            }

        } catch (Exception e) {
            log.error("调用Qwen失败", e);
            return "";
        }
    }

    /**
     * 调用自定义API
     */
    private String callCustomAPI(String prompt) {
        // 使用OpenAI兼容格式
        return callOpenAI(prompt);
    }

    /**
     * 从OpenAI响应中提取内容
     */
    private String extractOpenAIContent(String response) {
        try {
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);

            // 检查是否有error字段（某些代理API会在200中返回error）
            if (jsonResponse.has("error")) {
                log.error("OpenAI响应包含error字段: {}", jsonResponse.get("error"));
                return "";
            }

            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                log.error("OpenAI响应中choices为空, 响应内容: {}",
                        response.length() > 500 ? response.substring(0, 500) + "..." : response);
                return "";
            }
            JsonObject firstChoice = choices.get(0).getAsJsonObject();

            // 检查finish_reason
            if (firstChoice.has("finish_reason")) {
                String finishReason = firstChoice.get("finish_reason").getAsString();
                if ("length".equals(finishReason)) {
                    log.warn("OpenAI响应因max_tokens截断(finish_reason=length), 当前max_tokens={}",
                            llmConfig.getActiveMaxTokens());
                }
            }

            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                log.error("OpenAI响应中message或content为空, firstChoice: {}", firstChoice);
                return "";
            }
            String content = message.get("content").getAsString();
            log.debug("extractOpenAIContent成功, 内容长度: {}", content.length());
            return content;
        } catch (Exception e) {
            log.error("解析OpenAI响应失败, 响应内容: {}",
                    response != null ? (response.length() > 500 ? response.substring(0, 500) + "..." : response) : "null", e);
        }
        return "";
    }

    /**
     * 从Anthropic响应中提取内容
     */
    private String extractAnthropicContent(String response) {
        try {
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            JsonArray content = jsonResponse.getAsJsonArray("content");
            if (content != null && content.size() > 0) {
                JsonObject firstContent = content.get(0).getAsJsonObject();
                return firstContent.get("text").getAsString();
            }
        } catch (Exception e) {
            log.error("解析Anthropic响应失败", e);
        }
        return "";
    }

    /**
     * 从Qwen响应中提取内容
     */
    private String extractQwenContent(String response) {
        try {
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject firstChoice = choices.get(0).getAsJsonObject();
                JsonObject message = firstChoice.getAsJsonObject("message");
                return message.get("content").getAsString();
            }
        } catch (Exception e) {
            log.error("解析Qwen响应失败", e);
        }
        return "";
    }

    /**
     * 解析语义信息
     */
    private SemanticInfo parseSemanticInfo(String response) {
        SemanticInfo info = new SemanticInfo();

        try {
            // 提取JSON部分
            String jsonStr = extractJsonFromResponse(response);
            JsonObject json = gson.fromJson(jsonStr, JsonObject.class);

            if (json.has("functionalDescription")) {
                info.setFunctionalDescription(json.get("functionalDescription").getAsString());
            }
            if (json.has("businessDomain")) {
                info.setBusinessDomain(json.get("businessDomain").getAsString());
            }
            if (json.has("keyTerms")) {
                JsonArray terms = json.getAsJsonArray("keyTerms");
                List<String> keyTerms = new ArrayList<>();
                terms.forEach(t -> keyTerms.add(t.getAsString()));
                info.setKeyTerms(keyTerms);
            }
            if (json.has("functionalTags")) {
                JsonArray tags = json.getAsJsonArray("functionalTags");
                List<String> functionalTags = new ArrayList<>();
                tags.forEach(t -> functionalTags.add(t.getAsString()));
                info.setFunctionalTags(functionalTags);
            }
            if (json.has("architectureLayer")) {
                String layer = json.get("architectureLayer").getAsString();
                try {
                    info.setArchitectureLayer(SemanticInfo.ArchitectureLayer.valueOf(layer));
                } catch (Exception e) {
                    info.setArchitectureLayer(SemanticInfo.ArchitectureLayer.UNKNOWN);
                }
            }
            if (json.has("designPatterns")) {
                JsonArray patterns = json.getAsJsonArray("designPatterns");
                List<String> designPatterns = new ArrayList<>();
                patterns.forEach(p -> designPatterns.add(p.getAsString()));
                info.setDesignPatterns(designPatterns);
            }
            if (json.has("responsibility")) {
                info.setResponsibility(json.get("responsibility").getAsString());
            }

        } catch (Exception e) {
            log.error("解析语义信息失败", e);
        }

        return info;
    }

    /**
     * 解析模块名称
     */
    private String parseModuleName(String response) {
        if (response == null || response.trim().isEmpty()) {
            log.warn("LLM返回空响应");
            return "UnknownModule";
        }

        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            if (obj.has("moduleName")) {
                String moduleName = obj.get("moduleName").getAsString().trim();
                // 验证模块名称是否合法（只包含字母、数字、下划线）
                if (moduleName.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
                    return moduleName;
                } else {
                    log.warn("LLM返回的模块名称不合法: {}", moduleName);
                }
            }
        } catch (Exception e) {
            log.warn("无法解析JSON响应: {}", e.getMessage());
        }

        // 如果JSON解析失败，尝试使用正则表达式提取moduleName字段的值
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"moduleName\"\\s*:\\s*\"([a-zA-Z][a-zA-Z0-9_]*)\"");
            java.util.regex.Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String moduleName = matcher.group(1);
                log.debug("通过正则表达式提取到模块名称: {}", moduleName);
                return moduleName;
            }
        } catch (Exception e) {
            log.warn("正则表达式提取失败: {}", e.getMessage());
        }

        // 最后的fallback：返回默认名称，而不是返回整个响应文本
        log.error("无法从LLM响应中提取有效的模块名称，响应内容: ",
                  response.length() > 200 ? response.substring(0, 200) + "..." : response);
        return "UnknownModule";
    }

    /**
     * 解析模块列表
     */
    private List<String> parseModuleList(String response) {
        List<String> modules = new ArrayList<>();
        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonArray array = gson.fromJson(jsonStr, JsonArray.class);
            array.forEach(e -> modules.add(e.getAsString()));
        } catch (Exception e) {
            log.error("解析模块列表失败", e);
        }
        return modules;
    }

    /**
     * 解析相似度分数
     */
    private double parseSimilarityScore(String response) {
        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            if (obj.has("similarity")) {
                return obj.get("similarity").getAsDouble();
            }
        } catch (Exception e) {
            log.debug("无法解析JSON响应，尝试直接提取数字");
        }

        try {
            String cleaned = response.trim().replaceAll("[^0-9.]", "");
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            log.error("解析相似度分数失败", e);
            return 0.0;
        }
    }

    /**
     * 从响应中提取JSON
     */
    private String extractJsonFromResponse(String response) {
        // 尝试提取JSON部分
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }

        start = response.indexOf('[');
        end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }

        return response;
    }

    /**
     * 创建默认语义信息
     */
    private SemanticInfo createDefaultSemanticInfo(CodeEntity entity) {
        SemanticInfo info = new SemanticInfo();
        info.setFunctionalDescription("Unknown");
        info.setBusinessDomain("Unknown");
        info.setArchitectureLayer(inferLayerFromPackage(entity.getPackageName()));
        return info;
    }

    /**
     * 从包名推断架构层次
     */
    private SemanticInfo.ArchitectureLayer inferLayerFromPackage(String packageName) {
        if (packageName.contains("controller") || packageName.contains("web") || packageName.contains("api")) {
            return SemanticInfo.ArchitectureLayer.PRESENTATION;
        } else if (packageName.contains("service")) {
            return SemanticInfo.ArchitectureLayer.SERVICE;
        } else if (packageName.contains("domain") || packageName.contains("model") || packageName.contains("entity")) {
            return SemanticInfo.ArchitectureLayer.DOMAIN;
        } else if (packageName.contains("repository") || packageName.contains("dao") || packageName.contains("infrastructure")) {
            return SemanticInfo.ArchitectureLayer.INFRASTRUCTURE;
        } else if (packageName.contains("util") || packageName.contains("helper") || packageName.contains("common")) {
            return SemanticInfo.ArchitectureLayer.UTILITY;
        }
        return SemanticInfo.ArchitectureLayer.UNKNOWN;
    }

    // ==================== 功能结构知识库相关方法 ====================

    /**
     * 获取文本的embedding向量
     */
    public double[] getEmbedding(String text) {
        if (!llmConfig.getEnabled()) {
            log.warn("LLM功能未启用");
            return new double[0];
        }

        try {
            String embeddingUrl = llmConfig.getOpenai().getEmbeddingUrl();
            URL url = new URL(embeddingUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + llmConfig.getActiveApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmConfig.getTimeout().intValue());
            conn.setReadTimeout(llmConfig.getTimeout().intValue());

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", llmConfig.getOpenai().getEmbeddingModel());
            requestBody.addProperty("input", text);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = br.lines().collect(Collectors.joining("\n"));
                    return parseEmbeddingResponse(response);
                }
            } else {
                log.error("Embedding API调用失败，状态码: {}", responseCode);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    log.error("错误详情: {}", br.lines().collect(Collectors.joining("\n")));
                }
                return new double[0];
            }
        } catch (Exception e) {
            log.error("获取embedding失败", e);
            return new double[0];
        }
    }

    /**
     * 批量获取embedding向量（一次API调用传入多个文本）
     */
    public List<double[]> getEmbeddingBatch(List<String> texts) {
        List<double[]> results = new ArrayList<>();
        if (!llmConfig.getEnabled() || texts.isEmpty()) {
            for (int i = 0; i < texts.size(); i++) results.add(new double[0]);
            return results;
        }

        try {
            String embeddingUrl = llmConfig.getOpenai().getEmbeddingUrl();
            URL url = new URL(embeddingUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + llmConfig.getActiveApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmConfig.getTimeout().intValue());
            conn.setReadTimeout(llmConfig.getTimeout().intValue() * 2); // 批量请求给更多时间

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", llmConfig.getOpenai().getEmbeddingModel());
            // 批量输入：传入JSON数组
            JsonArray inputArray = new JsonArray();
            for (String text : texts) {
                inputArray.add(text);
            }
            requestBody.add("input", inputArray);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = br.lines().collect(Collectors.joining("\n"));
                    return parseBatchEmbeddingResponse(response, texts.size());
                }
            } else {
                String errorResponse = readErrorStream(conn);
                log.error("批量Embedding API调用失败，状态码: {}, 错误: {}", responseCode, errorResponse);
                // 回退到逐条调用
                for (String text : texts) {
                    results.add(getEmbedding(text));
                }
                return results;
            }
        } catch (Exception e) {
            log.error("批量获取embedding失败，回退到逐条调用", e);
            for (String text : texts) {
                try {
                    results.add(getEmbedding(text));
                } catch (Exception ex) {
                    results.add(new double[0]);
                }
            }
            return results;
        }
    }

    /**
     * 解析批量embedding API响应
     */
    private List<double[]> parseBatchEmbeddingResponse(String response, int expectedSize) {
        List<double[]> results = new ArrayList<>();
        try {
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            JsonArray data = jsonResponse.getAsJsonArray("data");
            if (data != null) {
                // 按index排序确保顺序正确
                double[][] ordered = new double[expectedSize][];
                for (int i = 0; i < data.size(); i++) {
                    JsonObject item = data.get(i).getAsJsonObject();
                    int index = item.has("index") ? item.get("index").getAsInt() : i;
                    JsonArray embedding = item.getAsJsonArray("embedding");
                    double[] vec = new double[embedding.size()];
                    for (int j = 0; j < embedding.size(); j++) {
                        vec[j] = embedding.get(j).getAsDouble();
                    }
                    if (index < expectedSize) {
                        ordered[index] = vec;
                    }
                }
                for (int i = 0; i < expectedSize; i++) {
                    results.add(ordered[i] != null ? ordered[i] : new double[0]);
                }
            }
        } catch (Exception e) {
            log.error("解析批量embedding响应失败", e);
            for (int i = results.size(); i < expectedSize; i++) {
                results.add(new double[0]);
            }
        }
        return results;
    }

    /**
     * 解析embedding API响应（单条）
     */
    private double[] parseEmbeddingResponse(String response) {
        try {
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            JsonArray data = jsonResponse.getAsJsonArray("data");
            if (data != null && data.size() > 0) {
                JsonArray embedding = data.get(0).getAsJsonObject().getAsJsonArray("embedding");
                double[] result = new double[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    result[i] = embedding.get(i).getAsDouble();
                }
                return result;
            }
        } catch (Exception e) {
            log.error("解析embedding响应失败", e);
        }
        return new double[0];
    }

    /**
     * 为一组类生成FSK功能层次结构
     *
     * @param projectName    项目名称
     * @param classSummaries 类摘要列表
     * @param totalClassCount 项目总类数量（用于估算目标组件数）
     */
    public String generateFskForClasses(String projectName, List<String> classSummaries, int totalClassCount) {
        // 目标组件数：使用 log2(N) 更贴近实际架构（小项目3-5，中项目5-8，大项目8-12）
        int targetComponents = Math.max(3, Math.min(12, (int)(Math.log(totalClassCount) / Math.log(2))));

        // 优化4: 小型项目(<50类)提升目标组件数下限，鼓励更细粒度分组
        int lowerBound = totalClassCount < 50 ? targetComponents : Math.max(2, targetComponents - 2);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a software architecture recovery expert. Analyze the following Java project and group its classes into BUSINESS FUNCTIONAL COMPONENTS. Return the result in JSON format.\n\n");
        prompt.append("Project: ").append(projectName).append("\n");
        prompt.append("Total classes: ").append(totalClassCount).append("\n");
        prompt.append("Target number of components: approximately ").append(targetComponents).append(" (between ").append(lowerBound).append(" and ").append(targetComponents + 3).append(")\n\n");

        prompt.append("CRITICAL RULES:\n");
        prompt.append("1. Group by BUSINESS FUNCTION (e.g., 'Order Management', 'User Authentication', 'Payment Processing'), NOT by technical layer.\n");
        prompt.append("2. Each component MUST be a VERTICAL SLICE: include ALL related classes across ALL layers (Entity + Service + ServiceImpl + Controller/REST + DTO + Repository) that serve the SAME business function.\n");
        prompt.append("3. Classes sharing the same domain noun MUST be in the same component. For example: Customer, CustomerService, CustomerServiceImpl, CustomerREST, CustomerInfo, CustomerAddress → all in one 'Customer Management' component.\n");
        prompt.append("4. WRONG grouping examples (DO NOT do this):\n");
        prompt.append("   - 'All Entities' component containing Customer, Flight, Booking entities → WRONG\n");
        prompt.append("   - 'All REST APIs' component containing CustomerREST, FlightREST, BookingREST → WRONG\n");
        prompt.append("   - 'All Services' component containing CustomerService, FlightService → WRONG\n");
        prompt.append("   - Separating CustomerEntity from CustomerService into different components → WRONG\n");
        prompt.append("5. CORRECT grouping example:\n");
        prompt.append("   - 'Customer Management': Customer, CustomerAddress, CustomerService, CustomerServiceImpl, CustomerREST, CustomerInfo\n");
        prompt.append("   - 'Flight Management': Flight, FlightSegment, FlightService, FlightServiceImpl, FlightsREST\n");
        prompt.append("   - 'Booking Management': Booking, BookingService, BookingServiceImpl, BookingsREST\n");
        prompt.append("6. If a class has multiple implementations (e.g., CustomerServiceImpl in different packages like morphia/wxs), group ALL implementations with the same interface/entity.\n");
        prompt.append("7. Every class listed below MUST appear in exactly one component's relatedClasses. Use the FULL QUALIFIED class name exactly as provided.\n");
        prompt.append("8. Do NOT create single-class components unless the class is truly independent.\n");
        prompt.append("9. Classes within the same component should primarily come from the same or closely related package prefixes. Avoid mixing classes from completely different package hierarchies into one component.\n");
        if (totalClassCount < 50) {
            prompt.append("10. This is a SMALL project. Prefer finer-grained components aligned with sub-package boundaries. It is better to have MORE smaller components than FEWER large ones that mix different sub-packages.\n");
        }
        prompt.append("\n");

        prompt.append("Class list:\n");
        classSummaries.forEach(s -> prompt.append("- ").append(s).append("\n"));

        prompt.append("\nReturn JSON with this structure:\n");
        prompt.append("{\n");
        prompt.append("  \"functions\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"name\": \"BusinessFunctionName\",\n");
        prompt.append("      \"description\": \"What this business function does\",\n");
        prompt.append("      \"relatedTerms\": [\"domain terms\"],\n");
        prompt.append("      \"relatedClasses\": [\"fully.qualified.ClassName\"],\n");
        prompt.append("      \"children\": [\n");
        prompt.append("        {\"name\": \"SubFunction\", \"description\": \"desc\", \"relatedClasses\": [\"fully.qualified.ClassName\"], \"isLeaf\": true}\n");
        prompt.append("      ]\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");

        return callLLM(prompt.toString());
    }

    /**
     * 生成单个类的功能摘要
     */
    public String generateClassFunctionalSummary(String className, String javadoc, String methods, String fields) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下Java类生成一句话的功能摘要，并以JSON格式返回。\n\n");
        prompt.append("类名: ").append(className).append("\n");
        if (javadoc != null && !javadoc.isEmpty()) {
            prompt.append("文档: ").append(javadoc.length() > 200 ? javadoc.substring(0, 200) : javadoc).append("\n");
        }
        if (methods != null && !methods.isEmpty()) {
            prompt.append("方法: ").append(methods).append("\n");
        }
        if (fields != null && !fields.isEmpty()) {
            prompt.append("字段: ").append(fields).append("\n");
        }
        prompt.append("\n请以JSON格式返回: {\"summary\": \"一句话功能摘要\"}\n");

        String response = callLLM(prompt.toString());
        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            if (obj.has("summary")) {
                return obj.get("summary").getAsString();
            }
        } catch (Exception e) {
            log.debug("解析功能摘要失败");
        }
        return className;
    }

    /**
     * 生成系统级描述
     */
    public String generateSystemDescription(String projectName, Collection<CodeEntity> entities) {
        log.debug("生成系统描述: {}", projectName);

        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下Java项目生成一个简洁的系统级功能描述，并以JSON格式返回：\n\n");
        prompt.append("项目名称: ").append(projectName).append("\n");
        prompt.append("包含 ").append(entities.size()).append(" 个代码实体\n\n");

        // 统计各类型实体
        Map<CodeEntity.EntityType, Long> typeCounts = entities.stream()
            .collect(Collectors.groupingBy(CodeEntity::getType, Collectors.counting()));

        prompt.append("实体类型分布:\n");
        typeCounts.forEach((type, count) ->
            prompt.append("- ").append(type).append(": ").append(count).append("\n")
        );

        // 列举主要包
        Set<String> packages = entities.stream()
            .map(CodeEntity::getPackageName)
            .collect(Collectors.toSet());
        prompt.append("\n主要包结构:\n");
        packages.stream().limit(10).forEach(pkg ->
            prompt.append("- ").append(pkg).append("\n")
        );

        prompt.append("\n请以JSON格式返回：{\"description\": \"系统功能描述(1-2句话)\"}");

        String response = callLLM(prompt.toString());

        // 尝试从JSON中提取description字段
        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
            if (obj.has("description")) {
                return obj.get("description").getAsString().trim();
            }
        } catch (Exception e) {
            log.debug("无法解析JSON响应，直接返回原始响应");
        }

        return response.trim();
    }

    /**
     * 生成子系统信息
     */
    public String generateSubsystemInfo(String topPackage, List<CodeEntity> entities) {
        log.debug("生成子系统信息: {}", topPackage);

        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下子系统生成名称和功能描述，并以JSON格式返回：\n\n");
        prompt.append("包名: ").append(topPackage).append("\n");
        prompt.append("包含 ").append(entities.size()).append(" 个类\n\n");

        // 列举主要类
        prompt.append("主要类:\n");
        entities.stream().limit(10).forEach(entity ->
            prompt.append("- ").append(entity.getName())
                .append(entity.getComment() != null ? " // " + entity.getComment().substring(0, Math.min(50, entity.getComment().length())) : "")
                .append("\n")
        );

        prompt.append("\n请以JSON格式返回：\n");
        prompt.append("{\"name\": \"子系统名称\", \"description\": \"功能描述\"}\n");

        String response = callLLM(prompt.toString());
        return response;
    }

    /**
     * 生成模块信息
     */
    public String generateModuleInfo(String packageName, List<CodeEntity> entities) {
        log.debug("生成模块信息: {}", packageName);

        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下模块生成名称和功能描述，并以JSON格式返回：\n\n");
        prompt.append("包名: ").append(packageName).append("\n");
        prompt.append("包含 ").append(entities.size()).append(" 个类\n\n");

        // 列举所有类
        prompt.append("类列表:\n");
        entities.forEach(entity -> {
            prompt.append("- ").append(entity.getName());
            if (entity.getComment() != null) {
                prompt.append(" // ").append(entity.getComment().substring(0, Math.min(50, entity.getComment().length())));
            }
            prompt.append("\n");
        });

        prompt.append("\n请以JSON格式返回：\n");
        prompt.append("{\"name\": \"模块名称(驼峰命名法)\", \"description\": \"功能描述(1-2句话)\"}\n");

        String response = callLLM(prompt.toString());
        return response;
    }

    /**
     * 批量定义术语（改进版：使用 response_format 强制 JSON）
     */
    public Map<String, com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition> defineTermsBatch(
            List<String> terms, com.example.javaparser.model.KnowledgeBase knowledgeBase) {

        Map<String, com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition> definitions = new HashMap<>();

        if (terms.isEmpty()) {
            return definitions;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("你必须返回一个有效的JSON数组。为以下术语提供定义：\n\n");
        prompt.append("术语：");
        prompt.append(String.join(", ", terms));
        prompt.append("\n\n");
        prompt.append("返回格式（必须是有效的JSON数组）：\n");
        prompt.append("{\n");
        prompt.append("  \"terms\": [\n");
        prompt.append("    {\"term\":\"术语名\",\"definition\":\"简短定义(不超过50字)\",\"category\":\"分类\",\"synonyms\":[\"同义词1\",\"同义词2\"]}\n");
        prompt.append("  ]\n");
        prompt.append("}\n");

        String response = callLLM(prompt.toString());

        if (response == null || response.trim().isEmpty()) {
            log.warn("LLM返回空响应");
            return createDefaultTermDefinitions(terms);
        }

        try {
            String jsonStr = extractJsonFromResponse(response);

            // 检查JSON是否完整
            if (!isCompleteJson(jsonStr)) {
                log.warn("JSON响应不完整，尝试修复");
                jsonStr = repairJson(jsonStr);
            }

            log.debug("提取的JSON长度: {} 字符", jsonStr.length());

            // 尝试解析为包含 terms 数组的对象
            try {
                JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
                if (obj.has("terms")) {
                    JsonArray array = obj.getAsJsonArray("terms");
                    parseTermDefinitionsFromArray(array, definitions);
                    log.debug("成功解析 {} 个术语定义", definitions.size());
                } else {
                    // 尝试直接解析为数组
                    JsonArray array = gson.fromJson(jsonStr, JsonArray.class);
                    parseTermDefinitionsFromArray(array, definitions);
                    log.debug("成功解析 {} 个术语定义", definitions.size());
                }
            } catch (Exception e) {
                // 如果不是预期格式，尝试解析为对象
                log.debug("尝试其他解析方式");
                try {
                    JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
                    parseTermDefinitionsFromObject(obj, definitions);
                    log.debug("从对象格式解析了 {} 个术语定义", definitions.size());
                } catch (Exception e2) {
                    log.error("无法解析JSON响应，使用默认定义");
                    return createDefaultTermDefinitions(terms);
                }
            }
        } catch (Exception e) {
            log.error("解析术语定义失败，使用默认定义", e);
            return createDefaultTermDefinitions(terms);
        }

        // 为未定义的术语添加默认定义
        for (String term : terms) {
            if (!definitions.containsKey(term)) {
                definitions.put(term, createDefaultTermDefinition(term));
            }
        }

        return definitions;
    }

    /**
     * 检查JSON是否完整
     */
    private boolean isCompleteJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }

        json = json.trim();

        // 检查数组格式
        if (json.startsWith("[")) {
            return json.endsWith("]");
        }

        // 检查对象格式
        if (json.startsWith("{")) {
            return json.endsWith("}");
        }

        return false;
    }

    /**
     * 尝试修复不完整的JSON
     */
    private String repairJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "[]";
        }

        json = json.trim();

        // 如果是数组但没有闭合
        if (json.startsWith("[") && !json.endsWith("]")) {
            // 找到最后一个完整的对象
            int lastCompleteObject = json.lastIndexOf("}");
            if (lastCompleteObject > 0) {
                json = json.substring(0, lastCompleteObject + 1) + "]";
            } else {
                json = "[]";
            }
        }

        // 如果是对象但没有闭合
        if (json.startsWith("{") && !json.endsWith("}")) {
            // 尝试找到最后一个完整的字段
            int lastQuote = json.lastIndexOf("\"");
            if (lastQuote > 0) {
                json = json.substring(0, lastQuote + 1) + "}";
            } else {
                json = "{}";
            }
        }

        return json;
    }

    /**
     * 创建默认术语定义列表
     */
    private Map<String, com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition> createDefaultTermDefinitions(
            List<String> terms) {
        Map<String, com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition> definitions = new HashMap<>();
        for (String term : terms) {
            definitions.put(term, createDefaultTermDefinition(term));
        }
        return definitions;
    }

    /**
     * 创建单个默认术语定义
     */
    private com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition createDefaultTermDefinition(String term) {
        com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition def =
            new com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition();
        def.setTerm(term);
        def.setDefinition("Domain term: " + term);
        def.setCategory("General");
        def.setSynonyms(new ArrayList<>());
        def.setSource("Default");
        return def;
    }

    /**
     * 从JSON数组解析术语定义
     */
    private void parseTermDefinitionsFromArray(JsonArray array,
            Map<String, com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition> definitions) {

        for (int i = 0; i < array.size(); i++) {
            JsonObject obj = array.get(i).getAsJsonObject();
            com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition def =
                parseTermDefinition(obj);
            if (def != null && def.getTerm() != null) {
                definitions.put(def.getTerm(), def);
            }
        }
    }

    /**
     * 从JSON对象解析术语定义（处理LLM返回对象而非数组的情况）
     */
    private void parseTermDefinitionsFromObject(JsonObject obj,
            Map<String, com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition> definitions) {

        // 如果对象的每个键是术语名，值是定义对象
        for (String key : obj.keySet()) {
            try {
                JsonObject termObj = obj.get(key).getAsJsonObject();
                com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition def =
                    parseTermDefinition(termObj);
                if (def != null) {
                    if (def.getTerm() == null) {
                        def.setTerm(key);
                    }
                    definitions.put(def.getTerm(), def);
                }
            } catch (Exception e) {
                log.debug("跳过无效的术语定义: {}", key);
            }
        }
    }

    /**
     * 解析单个术语定义
     */
    private com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition parseTermDefinition(JsonObject obj) {
        com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition def =
            new com.example.javaparser.model.FunctionalKnowledgeBase.TermDefinition();

        if (obj.has("term")) {
            def.setTerm(obj.get("term").getAsString());
        }
        if (obj.has("definition")) {
            def.setDefinition(obj.get("definition").getAsString());
        }
        if (obj.has("category")) {
            def.setCategory(obj.get("category").getAsString());
        }
        if (obj.has("synonyms")) {
            try {
                JsonArray synonyms = obj.getAsJsonArray("synonyms");
                List<String> synonymList = new ArrayList<>();
                synonyms.forEach(s -> synonymList.add(s.getAsString()));
                def.setSynonyms(synonymList);
            } catch (Exception e) {
                def.setSynonyms(new ArrayList<>());
            }
        }

        def.setSource("LLM");
        return def;
    }

    /**
     * 识别功能块
     */
    public com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalBlock identifyFunctionalBlock(
            String moduleName, List<CodeEntity> entities) {

        log.debug("识别功能块: {}", moduleName);

        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下模块，识别其功能块信息：\n\n");
        prompt.append("模块名称: ").append(moduleName).append("\n");
        prompt.append("包含的类:\n");

        entities.forEach(entity -> {
            prompt.append("- ").append(entity.getName());
            if (entity.getComment() != null) {
                prompt.append(" // ").append(entity.getComment().substring(0, Math.min(50, entity.getComment().length())));
            }
            prompt.append("\n");

            // 列举主要方法
            if (!entity.getMethods().isEmpty()) {
                prompt.append("  方法: ");
                entity.getMethods().stream()
                    .filter(m -> m.isPublic())
                    .limit(3)
                    .forEach(m -> prompt.append(m.getName()).append("(), "));
                prompt.append("\n");
            }
        });

        prompt.append("\n请以JSON格式返回功能块信息：\n");
        prompt.append("{\n");
        prompt.append("  \"name\": \"功能块名称\",\n");
        prompt.append("  \"functionalDescription\": \"功能描述\",\n");
        prompt.append("  \"businessPurpose\": \"业务目的\",\n");
        prompt.append("  \"capabilities\": [\"能力1\", \"能力2\"]\n");
        prompt.append("}\n");

        String response = callLLM(prompt.toString());

        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);

            com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalBlock block =
                new com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalBlock();

            if (obj.has("name")) {
                block.setName(obj.get("name").getAsString());
            }
            if (obj.has("functionalDescription")) {
                block.setFunctionalDescription(obj.get("functionalDescription").getAsString());
            }
            if (obj.has("businessPurpose")) {
                block.setBusinessPurpose(obj.get("businessPurpose").getAsString());
            }
            if (obj.has("capabilities")) {
                JsonArray caps = obj.getAsJsonArray("capabilities");
                Set<String> capabilities = new HashSet<>();
                caps.forEach(c -> capabilities.add(c.getAsString()));
                block.setCapabilities(capabilities);
            }

            return block;

        } catch (Exception e) {
            log.error("解析功能块失败", e);
            return null;
        }
    }

    /**
     * 分析约束关系
     */
    public com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalConstraint analyzeConstraint(
            com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalBlock block1,
            com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalBlock block2,
            com.example.javaparser.model.KnowledgeBase knowledgeBase) {

        log.debug("分析约束: {} -> {}", block1.getName(), block2.getName());

        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下两个功能块之间的约束关系：\n\n");
        prompt.append("功能块1: ").append(block1.getName()).append("\n");
        prompt.append("描述: ").append(block1.getFunctionalDescription()).append("\n\n");
        prompt.append("功能块2: ").append(block2.getName()).append("\n");
        prompt.append("描述: ").append(block2.getFunctionalDescription()).append("\n\n");

        prompt.append("请判断它们之间的约束类型，并以JSON格式返回：\n");
        prompt.append("{\n");
        prompt.append("  \"type\": \"REQUIRES|EXCLUDES|OPTIONAL|MANDATORY|CONDITIONAL|SEQUENCE\",\n");
        prompt.append("  \"description\": \"约束描述\",\n");
        prompt.append("  \"strength\": 0.8\n");
        prompt.append("}\n");
        prompt.append("\n如果没有明显约束关系，返回 null");

        String response = callLLM(prompt.toString());

        if (response.trim().equalsIgnoreCase("null") || response.trim().isEmpty()) {
            return null;
        }

        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);

            com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalConstraint constraint =
                new com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalConstraint();

            if (obj.has("type")) {
                String typeStr = obj.get("type").getAsString();
                constraint.setType(
                    com.example.javaparser.model.FunctionalKnowledgeBase.FunctionalConstraint.ConstraintType.valueOf(typeStr)
                );
            }
            if (obj.has("description")) {
                constraint.setDescription(obj.get("description").getAsString());
            }
            if (obj.has("strength")) {
                constraint.setStrength(obj.get("strength").getAsDouble());
            }

            return constraint;

        } catch (Exception e) {
            log.error("解析约束失败", e);
            return null;
        }
    }

    /**
     * 提取业务规则
     */
    public com.example.javaparser.model.FunctionalKnowledgeBase.BusinessRule extractBusinessRule(
            CodeEntity entity, CodeEntity.MethodInfo method) {

        log.debug("提取业务规则: {}.{}", entity.getName(), method.getName());

        StringBuilder prompt = new StringBuilder();
        prompt.append("请从以下方法中提取业务规则：\n\n");
        prompt.append("类: ").append(entity.getName()).append("\n");
        prompt.append("方法: ").append(method.getName()).append("\n");
        prompt.append("注释: ").append(method.getComment() != null ? method.getComment() : "无").append("\n");
        prompt.append("参数: ").append(String.join(", ", method.getParameters())).append("\n");
        prompt.append("返回类型: ").append(method.getReturnType()).append("\n\n");

        prompt.append("请以JSON格式返回业务规则：\n");
        prompt.append("{\n");
        prompt.append("  \"name\": \"规则名称\",\n");
        prompt.append("  \"description\": \"规则描述\",\n");
        prompt.append("  \"type\": \"VALIDATION|CALCULATION|INFERENCE|CONSTRAINT|WORKFLOW\",\n");
        prompt.append("  \"condition\": \"触发条件\",\n");
        prompt.append("  \"action\": \"执行动作\"\n");
        prompt.append("}\n");
        prompt.append("\n如果没有明显的业务规则，返回 null");

        String response = callLLM(prompt.toString());

        if (response.trim().equalsIgnoreCase("null") || response.trim().isEmpty()) {
            return null;
        }

        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);

            com.example.javaparser.model.FunctionalKnowledgeBase.BusinessRule rule =
                new com.example.javaparser.model.FunctionalKnowledgeBase.BusinessRule();

            if (obj.has("name")) {
                rule.setName(obj.get("name").getAsString());
            }
            if (obj.has("description")) {
                rule.setDescription(obj.get("description").getAsString());
            }
            if (obj.has("type")) {
                String typeStr = obj.get("type").getAsString();
                rule.setType(
                    com.example.javaparser.model.FunctionalKnowledgeBase.BusinessRule.RuleType.valueOf(typeStr)
                );
            }
            if (obj.has("condition")) {
                rule.setCondition(obj.get("condition").getAsString());
            }
            if (obj.has("action")) {
                rule.setAction(obj.get("action").getAsString());
            }

            return rule;

        } catch (Exception e) {
            log.error("解析业务规则失败", e);
            return null;
        }
    }
}
