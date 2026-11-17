package com.carerelay.common;

import com.carerelay.system.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 AI 网关：对话 / Embedding / 文生图都走 OpenAI 兼容协议。
 *
 * <p>配置来源有两处：界面「系统设置」写进数据库的值优先，其次才是 {@code CR_AI_*} 环境变量。
 * 每次调用都重新解析，所以管理员在页面上换 Key 或改模型，下一个请求就生效，不用重启。</p>
 */
@Component
public class AiGateway {

    private final AiProperties props;
    private final SettingsService settings;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public AiGateway(AiProperties props, SettingsService settings) {
        this.props = props;
        this.settings = settings;
    }

    public boolean chatConfigured() {
        return settings.resolve().configured();
    }

    public String model() {
        return settings.resolve().model();
    }

    /** 当前生效配置的脱敏视图，给「系统设置」页面用。 */
    public Map<String, Object> currentConfigView() {
        return settings.viewSettings();
    }

    /**
     * 一次性对话（非流式）。
     *
     * @throws IllegalStateException 未配置 Key，或上游返回异常结构
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chatWith(settings.resolve(), systemPrompt, userPrompt);
    }

    /** 用指定配置发起一次对话——「测试连接」按钮会用它验证尚未保存的 Key。 */
    public String chatWith(SettingsService.AiConfig cfg, String systemPrompt, String userPrompt) {
        if (!cfg.configured()) {
            throw new IllegalStateException("AI 未配置：请在「系统设置」里填写 API Key（或设置环境变量 CR_AI_API_KEY）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", 0.7);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        JsonNode resp = clientFor(cfg.baseUrl(), cfg.timeoutSeconds()).post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) {
            throw new IllegalStateException("AI 返回为空");
        }
        JsonNode content = resp.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("AI 返回结构异常：" + resp);
        }
        return content.asText();
    }

    /**
     * 带工具（Function Calling）的一轮对话，供 Agent 循环使用。
     *
     * <p>返回上游的 {@code choices[0].message} 原节点：可能带 {@code tool_calls}（要调工具），
     * 也可能是最终回答的 {@code content}。调用方自己决定下一步，这里只负责协议。</p>
     */
    public JsonNode chatMessage(SettingsService.AiConfig cfg, List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools, int maxTokens) {
        if (!cfg.configured()) {
            throw new IllegalStateException("AI 未配置：请在「系统设置」里填写 API Key（或设置环境变量 CR_AI_API_KEY）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", 0.2);
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        JsonNode resp = clientFor(cfg.baseUrl(), cfg.timeoutSeconds()).post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) {
            throw new IllegalStateException("AI 返回为空");
        }
        JsonNode message = resp.path("choices").path(0).path("message");
        if (message.isMissingNode() || message.isNull()) {
            throw new IllegalStateException("AI 返回结构异常：" + resp);
        }
        return message;
    }

    /** 按 baseUrl + 超时缓一个客户端，换地址时重新建，避免每次请求都 new。 */
    private RestClient clientFor(String baseUrl, int timeoutSeconds) {
        String key = baseUrl + "#" + timeoutSeconds;
        return clients.computeIfAbsent(key, k -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(10));
            factory.setReadTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)));
            return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        });
    }

    /** 保留给未来的 embedding / 图像入口。 */
    public AiProperties props() {
        return props;
    }
}
