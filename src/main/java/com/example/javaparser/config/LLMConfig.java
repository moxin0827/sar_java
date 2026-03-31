package com.example.javaparser.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM配置类 - 支持多种LLM提供商
 * 支持: GPT-4o, Claude Sonnet 4.5, Qwen3-Max-Thinking
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LLMConfig {

    /**
     * 是否启用LLM功能
     */
    private Boolean enabled = true;

    /**
     * LLM提供商类型
     */
    private ProviderType provider = ProviderType.OPENAI;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * API URL (如果不指定，将使用默认URL)
     */
    private String apiUrl;

    /**
     * 模型名称
     */
    private String model;

    /**
     * 温度参数 (0.0-1.0)
     */
    private Double temperature = 0.3;

    /**
     * 最大token数（全局覆盖，不设置则使用各provider自己的配置）
     */
    private Integer maxTokens;

    /**
     * 超时时间(毫秒)
     */
    private Long timeout = 60000L;

    /**
     * OpenAI配置
     */
    private OpenAIConfig openai = new OpenAIConfig();

    /**
     * Anthropic配置
     */
    private AnthropicConfig anthropic = new AnthropicConfig();

    /**
     * Qwen配置
     */
    private QwenConfig qwen = new QwenConfig();

    /**
     * LLM提供商类型枚举
     */
    public enum ProviderType {
        OPENAI("OpenAI"),
        ANTHROPIC("Anthropic"),
        QWEN("Qwen"),
        CUSTOM("Custom");

        private final String displayName;

        ProviderType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * OpenAI配置
     */
    @Data
    public static class OpenAIConfig {
        private String apiKey;
        private String apiUrl = "https://api.openai.com/v1/chat/completions";
        private String embeddingUrl = "https://api.openai.com/v1/embeddings";
        private String model = "gpt-4o";
        private String embeddingModel = "text-embedding-3-small";
        private String organization;
        private Double temperature = 0.3;
        private Integer maxTokens = 2000;
    }

    /**
     * Anthropic (Claude) 配置
     */
    @Data
    public static class AnthropicConfig {
        private String apiKey;
        private String apiUrl = "https://api.anthropic.com/v1/messages";
        private String model = "claude-sonnet-4.5";
        private String version = "2023-06-01";
        private Double temperature = 0.3;
        private Integer maxTokens = 2000;
    }

    /**
     * Qwen配置
     */
    @Data
    public static class QwenConfig {
        private String apiKey;
        private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        private String model = "qwen3-max-thinking";
        private Double temperature = 0.3;
        private Integer maxTokens = 2000;
        private Boolean enableSearch = false;
    }

    /**
     * 获取当前激活的API URL
     */
    public String getActiveApiUrl() {
        if (apiUrl != null && !apiUrl.isEmpty()) {
            return apiUrl;
        }

        switch (provider) {
            case OPENAI:
                return openai.getApiUrl();
            case ANTHROPIC:
                return anthropic.getApiUrl();
            case QWEN:
                return qwen.getApiUrl();
            default:
                return "https://api.openai.com/v1/chat/completions";
        }
    }

    /**
     * 获取当前激活的API密钥
     */
    public String getActiveApiKey() {
        if (apiKey != null && !apiKey.isEmpty()) {
            return apiKey;
        }

        switch (provider) {
            case OPENAI:
                return openai.getApiKey();
            case ANTHROPIC:
                return anthropic.getApiKey();
            case QWEN:
                return qwen.getApiKey();
            default:
                return null;
        }
    }

    /**
     * 获取当前激活的模型名称
     */
    public String getActiveModel() {
        if (model != null && !model.isEmpty()) {
            return model;
        }

        switch (provider) {
            case OPENAI:
                return openai.getModel();
            case ANTHROPIC:
                return anthropic.getModel();
            case QWEN:
                return qwen.getModel();
            default:
                return "gpt-4o";
        }
    }

    /**
     * 获取当前激活的温度参数
     */
    public Double getActiveTemperature() {
        if (temperature != null) {
            return temperature;
        }

        switch (provider) {
            case OPENAI:
                return openai.getTemperature();
            case ANTHROPIC:
                return anthropic.getTemperature();
            case QWEN:
                return qwen.getTemperature();
            default:
                return 0.3;
        }
    }

    /**
     * 获取当前激活的最大token数
     */
    public Integer getActiveMaxTokens() {
        if (maxTokens != null) {
            return maxTokens;
        }

        switch (provider) {
            case OPENAI:
                return openai.getMaxTokens();
            case ANTHROPIC:
                return anthropic.getMaxTokens();
            case QWEN:
                return qwen.getMaxTokens();
            default:
                return 2000;
        }
    }
}
