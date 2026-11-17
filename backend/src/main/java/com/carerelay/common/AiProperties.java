package com.carerelay.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 接入配置（全部走 OpenAI 兼容协议，换厂商只改环境变量）。
 *
 * @param baseUrl           对话模型 base-url，如 https://api.deepseek.com/v1
 * @param apiKey            对话模型 Key
 * @param model             对话模型名，如 deepseek-chat
 * @param embeddingBaseUrl  Embedding 服务 base-url（留空则复用 baseUrl）
 * @param embeddingApiKey   Embedding Key（留空则复用 apiKey）
 * @param embeddingModel    Embedding 模型名，如 bge-m3 / text-embedding-v3
 * @param imageBaseUrl      文生图服务 base-url
 * @param imageApiKey       文生图 Key
 * @param imageModel        文生图模型名，如 wanx2.1-t2i-turbo / cogview-4
 * @param timeoutSeconds    HTTP 超时秒数
 */
@ConfigurationProperties(prefix = "cr.ai")
public record AiProperties(
        @DefaultValue("https://api.deepseek.com/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("deepseek-chat") String model,
        @DefaultValue("") String embeddingBaseUrl,
        @DefaultValue("") String embeddingApiKey,
        @DefaultValue("") String embeddingModel,
        @DefaultValue("") String imageBaseUrl,
        @DefaultValue("") String imageApiKey,
        @DefaultValue("") String imageModel,
        @DefaultValue("30") int timeoutSeconds
) {
    /** 对话模型是否已配置。 */
    public boolean chatConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String resolvedEmbeddingBaseUrl() {
        return isBlank(embeddingBaseUrl) ? baseUrl : embeddingBaseUrl;
    }

    public String resolvedEmbeddingApiKey() {
        return isBlank(embeddingApiKey) ? apiKey : embeddingApiKey;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
