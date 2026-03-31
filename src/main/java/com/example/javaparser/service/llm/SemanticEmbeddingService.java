package com.example.javaparser.service.llm;

import com.example.javaparser.config.RecoveryProperties;
import com.example.javaparser.entity.ClassInfo;
import com.example.javaparser.llm.LLMService;
import com.example.javaparser.repository.ClassInfoRepository;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class SemanticEmbeddingService {

    @Autowired
    private LLMService llmService;

    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Autowired
    private RecoveryProperties recoveryProperties;

    private final Gson gson = new Gson();

    /** 每批embedding的文本数量 */
    private static final int EMBEDDING_BATCH_SIZE = 20;

    /** 并行embedding线程池（懒初始化） */
    private volatile ExecutorService embeddingExecutor;

    private ExecutorService getEmbeddingExecutor() {
        if (embeddingExecutor == null) {
            synchronized (this) {
                if (embeddingExecutor == null) {
                    int threads = recoveryProperties.getEmbeddingConcurrentBatches();
                    embeddingExecutor = Executors.newFixedThreadPool(threads,
                            r -> {
                                Thread t = new Thread(r, "embedding-worker");
                                t.setDaemon(true);
                                return t;
                            });
                    log.info("初始化embedding线程池: {} 线程", threads);
                }
            }
        }
        return embeddingExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (embeddingExecutor != null) {
            embeddingExecutor.shutdownNow();
        }
    }

    public void generateAllEmbeddings(Long projectId) {
        List<ClassInfo> classes = classInfoRepository.findByProjectIdAndSemanticEmbeddingIsNull(projectId);
        log.info("为 {} 个类生成embedding", classes.size());

        if (classes.isEmpty()) {
            return;
        }

        // 构建所有embedding文本
        List<String> allTexts = new ArrayList<>(classes.size());
        for (ClassInfo ci : classes) {
            allTexts.add(buildEmbeddingText(ci));
        }

        int totalBatches = (allTexts.size() + EMBEDDING_BATCH_SIZE - 1) / EMBEDDING_BATCH_SIZE;
        log.info("分 {} 批处理embedding", totalBatches);

        boolean useParallel = Boolean.TRUE.equals(recoveryProperties.getEnableParallelEmbedding());

        if (useParallel && totalBatches > 1) {
            generateEmbeddingsParallel(classes, allTexts, totalBatches);
        } else {
            generateEmbeddingsSequential(classes, allTexts, totalBatches);
        }

        log.info("Embedding生成完成");
    }

    /**
     * 并行批次处理embedding（核心优化：多批次并发请求API）
     * 使用Semaphore控制并发数，避免触发API限流
     */
    private void generateEmbeddingsParallel(List<ClassInfo> classes, List<String> allTexts, int totalBatches) {
        int concurrency = recoveryProperties.getEmbeddingConcurrentBatches();
        int delayMs = recoveryProperties.getEmbeddingDelayMs();
        Semaphore semaphore = new Semaphore(concurrency);
        AtomicInteger completedBatches = new AtomicInteger(0);

        // 线程安全的结果收集列表
        List<ClassInfo> toSave = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>(totalBatches);

        ExecutorService executor = getEmbeddingExecutor();

        for (int batchIdx = 0; batchIdx < totalBatches; batchIdx++) {
            final int start = batchIdx * EMBEDDING_BATCH_SIZE;
            final int end = Math.min(start + EMBEDDING_BATCH_SIZE, allTexts.size());
            final int bIdx = batchIdx;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        processBatch(classes, allTexts, start, end, toSave, delayMs);
                    } finally {
                        semaphore.release();
                    }
                    int done = completedBatches.incrementAndGet();
                    if (done % 5 == 0 || done == totalBatches) {
                        log.info("Embedding进度: {}/{} 批完成 ({}/{}类)",
                                done, totalBatches,
                                Math.min(done * EMBEDDING_BATCH_SIZE, classes.size()),
                                classes.size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Embedding批次 {} 被中断", bIdx);
                }
            }, executor);

            futures.add(future);
        }

        // 等待所有批次完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("并行embedding处理超时或失败", e);
        }

        // 统一批量保存（减少DB往返）
        bulkSave(toSave);
    }

    /**
     * 顺序批次处理embedding（回退模式）
     */
    private void generateEmbeddingsSequential(List<ClassInfo> classes, List<String> allTexts, int totalBatches) {
        int delayMs = recoveryProperties.getEmbeddingDelayMs();
        List<ClassInfo> toSave = new ArrayList<>();

        for (int batchIdx = 0; batchIdx < totalBatches; batchIdx++) {
            int start = batchIdx * EMBEDDING_BATCH_SIZE;
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, allTexts.size());

            processBatch(classes, allTexts, start, end, toSave, delayMs);

            if (batchIdx < totalBatches - 1) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log.debug("Embedding进度: {}/{}", Math.min(end, classes.size()), classes.size());
        }

        bulkSave(toSave);
    }

    /**
     * 处理单个批次的embedding生成
     */
    private void processBatch(List<ClassInfo> classes, List<String> allTexts,
                               int start, int end, List<ClassInfo> toSave, int delayMs) {
        List<String> batchTexts = allTexts.subList(start, end);

        try {
            List<double[]> batchEmbeddings = llmService.getEmbeddingBatch(batchTexts);

            for (int i = 0; i < batchEmbeddings.size() && i < (end - start); i++) {
                double[] embedding = batchEmbeddings.get(i);
                if (embedding.length > 0) {
                    ClassInfo ci = classes.get(start + i);
                    ci.setSemanticEmbedding(gson.toJson(embedding));
                    toSave.add(ci);
                }
            }
        } catch (Exception e) {
            log.warn("批量embedding失败(batch {}-{}), 回退到逐条处理", start, end, e);
            // 回退：逐条处理该批次
            for (int i = start; i < end; i++) {
                try {
                    double[] embedding = llmService.getEmbedding(allTexts.get(i));
                    if (embedding.length > 0) {
                        ClassInfo ci = classes.get(i);
                        ci.setSemanticEmbedding(gson.toJson(embedding));
                        toSave.add(ci);
                    }
                    Thread.sleep(delayMs);
                } catch (Exception ex) {
                    log.error("生成embedding失败: {}", classes.get(i).getFullyQualifiedName(), ex);
                }
            }
        }
    }

    /**
     * 批量保存embedding结果到数据库
     */
    private void bulkSave(List<ClassInfo> toSave) {
        if (!toSave.isEmpty()) {
            classInfoRepository.saveAll(toSave);
            log.info("批量保存 {} 个embedding", toSave.size());
        }
    }

    /**
     * 构建embedding文本（优化：补充包名、父类、接口、注解、字段类型等上下文）
     */
    private String buildEmbeddingText(ClassInfo ci) {
        StringBuilder sb = new StringBuilder();
        // 包名（反映模块归属）
        if (ci.getPackageName() != null) {
            sb.append("package: ").append(ci.getPackageName()).append(" ");
        }
        sb.append(ci.getSimpleName());

        // 父类和接口（反映类型层次）
        if (ci.getSuperClass() != null && !ci.getSuperClass().isEmpty()) {
            sb.append(" extends ").append(ci.getSuperClass());
        }
        if (ci.getInterfaces() != null && !ci.getInterfaces().isEmpty()) {
            sb.append(" implements ").append(ci.getInterfaces());
        }

        // 注解信息（反映架构角色）
        if (ci.getAnnotations() != null && !ci.getAnnotations().isEmpty()) {
            sb.append(" annotations: ").append(ci.getAnnotations());
        }

        // 功能摘要
        if (ci.getFunctionalSummary() != null) {
            sb.append(" ").append(ci.getFunctionalSummary());
        }
        // Javadoc
        if (ci.getJavadocComment() != null) {
            String doc = ci.getJavadocComment();
            sb.append(" ").append(doc.length() > 300 ? doc.substring(0, 300) : doc);
        }
        // 方法名
        if (ci.getMethodNames() != null) {
            sb.append(" methods: ").append(ci.getMethodNames());
        }
        // 字段名
        if (ci.getFieldNames() != null) {
            sb.append(" fields: ").append(ci.getFieldNames());
        }
        return sb.toString();
    }

    public double[] getEmbeddingVector(ClassInfo ci) {
        if (ci.getSemanticEmbedding() == null || ci.getSemanticEmbedding().isEmpty()) {
            return new double[0];
        }
        try {
            return gson.fromJson(ci.getSemanticEmbedding(), double[].class);
        } catch (Exception e) {
            log.error("解析embedding失败: {}", ci.getFullyQualifiedName());
            return new double[0];
        }
    }

    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
