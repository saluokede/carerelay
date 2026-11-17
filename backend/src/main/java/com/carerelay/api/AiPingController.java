package com.carerelay.api;

import com.carerelay.common.AiGateway;
import com.carerelay.common.AiProperties;
import com.carerelay.common.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D0 技术验证接口：确认「后端能真的调通大模型」。
 *
 * <p>curl 验证：
 * <pre>
 *   set CR_AI_API_KEY=sk-xxx
 *   curl http://localhost:8080/api/v1/ai/ping
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiPingController {

    private final AiGateway gateway;
    private final AiProperties props;

    public AiPingController(AiGateway gateway, AiProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    /** 体检：不看 Key 是否配置成功，先告诉你「配了没有」。 */
    @GetMapping("/ping")
    public ApiResult<Map<String, Object>> ping() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", gateway.chatConfigured());
        out.put("model", props.model());
        out.put("baseUrl", props.baseUrl());
        if (!gateway.chatConfigured()) {
            out.put("hint", "设置 CR_AI_API_KEY（及相关 base-url/model）后重启服务，再调 POST /api/v1/ai/ping");
        }
        return ApiResult.ok(out);
    }

    /** 真实调用一次大模型，返回延迟与回答，用于验证 Key/网络/额度。 */
    @PostMapping("/ping")
    public ApiResult<Map<String, Object>> pingReal(@RequestBody(required = false) Map<String, String> body) {
        String q = (body == null || body.get("q") == null || body.get("q").isBlank())
                ? "用一句话说明出院老人回家后的前 30 天为什么最容易出问题。"
                : body.get("q");

        // 没有 Key 不是错误，是"这条通道现在关着"。返回 200 + configured=false，
        // 让调用方（验收脚本、前端体检）能区分"没配"和"配了但调不通"。
        if (!gateway.chatConfigured()) {
            Map<String, Object> off = new LinkedHashMap<>();
            off.put("configured", false);
            off.put("model", props.model());
            off.put("hint", "未配置 CR_AI_API_KEY，模型通道按设计关闭；设置后重启服务再调本接口。");
            return ApiResult.ok(off);
        }

        long start = System.currentTimeMillis();
        String answer = gateway.chat("你是社区护士的助手，回答简洁、具体、不超过 80 字，不做诊断。", q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", true);
        out.put("model", props.model());
        out.put("latencyMs", System.currentTimeMillis() - start);
        out.put("answer", answer);
        return ApiResult.ok(out);
    }
}
