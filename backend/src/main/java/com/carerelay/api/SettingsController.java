package com.carerelay.api;

import com.carerelay.auth.CurrentUser;
import com.carerelay.auth.UserContext;
import com.carerelay.common.AiGateway;
import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import com.carerelay.system.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置：AI 网关的地址 / 模型 / Key。
 *
 * <p>只有管理员能读写（RoleRules 里按路径拦）。Key 保存后只回显末四位，
 * 页面上再提交空 Key 表示"不改"，所以不会因为误点保存把 Key 清掉。</p>
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settings;
    private final AiGateway gateway;

    public SettingsController(SettingsService settings, AiGateway gateway) {
        this.settings = settings;
        this.gateway = gateway;
    }

    public record SaveRequest(String baseUrl, String apiKey, String model, Integer timeoutSeconds, Boolean clearKey) {
    }

    public record TestRequest(String baseUrl, String apiKey, String model, Integer timeoutSeconds) {
    }

    @GetMapping("/ai")
    public ApiResult<Map<String, Object>> get() {
        return ApiResult.ok(settings.viewSettings());
    }

    @PutMapping("/ai")
    public ApiResult<Map<String, Object>> save(@RequestBody SaveRequest req) {
        CurrentUser me = UserContext.require();
        Map<String, Object> view = settings.save(
                me.id(),
                req.baseUrl(),
                req.apiKey(),
                req.model(),
                req.timeoutSeconds(),
                req.clearKey() != null && req.clearKey());
        return ApiResult.ok(view);
    }

    /** 测试连接：没传 Key 就用已保存的那把，传了就用新的（不落库）。 */
    @PostMapping("/ai/test")
    public ApiResult<Map<String, Object>> test(@RequestBody(required = false) TestRequest req) {
        SettingsService.AiConfig saved = settings.resolve();
        String baseUrl = req != null && notBlank(req.baseUrl()) ? strip(req.baseUrl()) : saved.baseUrl();
        String key = req != null && notBlank(req.apiKey()) ? req.apiKey().trim() : saved.apiKey();
        String model = req != null && notBlank(req.model()) ? req.model().trim() : saved.model();
        int timeout = req != null && req.timeoutSeconds() != null ? req.timeoutSeconds() : saved.timeoutSeconds();

        if (key == null || key.isBlank()) {
            throw new BizException(400, "还没有可用的 API Key：请先填写再测试");
        }

        SettingsService.AiConfig probe = new SettingsService.AiConfig(baseUrl, key, model, timeout, "PROBE");
        long started = System.currentTimeMillis();
        String reply;
        try {
            reply = gateway.chatWith(probe, "你是一个连通性检测助手，只回一个字。", "回复：好");
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("ok", false);
            fail.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            fail.put("latencyMs", System.currentTimeMillis() - started);
            return ApiResult.ok(fail);
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("model", model);
        ok.put("latencyMs", System.currentTimeMillis() - started);
        ok.put("reply", reply.length() > 40 ? reply.substring(0, 40) + "…" : reply);
        return ApiResult.ok(ok);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String strip(String s) {
        String u = s.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
