package com.example.javaparser.service.evaluation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 加载 Ground Truth JSON 文件
 */
@Slf4j
@Service
public class GroundTruthLoader {

    private static final String DATASET_DIR = "dataset/ground-truth";
    private final Gson gson = new Gson();

    /**
     * 加载 ground truth 文件，返回组件划分
     *
     * @param filename ground truth 文件名（如 "jpetstore.json"）
     * @return 组件名 -> 类名列表
     */
    public Map<String, List<String>> loadGroundTruth(String filename) {
        Path filePath = Paths.get(System.getProperty("user.dir"), DATASET_DIR, filename);

        if (!Files.exists(filePath)) {
            throw new RuntimeException("Ground truth 文件不存在: " + filePath);
        }

        try (Reader reader = new InputStreamReader(
                Files.newInputStream(filePath), StandardCharsets.UTF_8)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            JsonArray components = root.getAsJsonArray("components");

            Map<String, List<String>> result = new LinkedHashMap<>();
            for (JsonElement elem : components) {
                JsonObject comp = elem.getAsJsonObject();
                String name = comp.get("name").getAsString();
                JsonArray classes = comp.getAsJsonArray("classes");

                List<String> classNames = new ArrayList<>();
                if (classes != null) {
                    for (JsonElement cls : classes) {
                        classNames.add(cls.getAsString());
                    }
                }
                result.put(name, classNames);
            }

            log.info("加载 Ground Truth: {}, {} 个组件, {} 个类",
                    filename, result.size(),
                    result.values().stream().mapToInt(List::size).sum());
            return result;
        } catch (IOException e) {
            throw new RuntimeException("读取 Ground Truth 文件失败: " + filePath, e);
        }
    }

    /**
     * 将恢复结果转换为组件划分格式
     *
     * @param components 组件名 -> 逗号分隔的类名字符串
     * @return 组件名 -> 类名列表
     */
    public static Map<String, List<String>> toPartition(Map<String, String> components) {
        Map<String, List<String>> partition = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : components.entrySet()) {
            String[] classes = entry.getValue().split(",");
            List<String> classList = new ArrayList<>();
            for (String cls : classes) {
                String trimmed = cls.trim();
                if (!trimmed.isEmpty()) {
                    classList.add(trimmed);
                }
            }
            if (!classList.isEmpty()) {
                partition.put(entry.getKey(), classList);
            }
        }
        return partition;
    }
}
