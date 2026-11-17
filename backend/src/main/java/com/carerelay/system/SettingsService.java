package com.carerelay.system;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.carerelay.audit.AuditService;
import com.carerelay.common.AiProperties;
import com.carerelay.common.BizException;
import com.carerelay.system.mapper.AppSettingMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 网关的运行时配置。
 *
 * <p>优先级：数据库里的设置（界面保存） &gt; 环境变量 {@code CR_AI_*}。
 * 这样演示环境可以带 Key 启动，护士长也能在界面上换一个 Key 立即生效，不必重启。</p>
 */
@Service
public class SettingsService {

    public static final String KEY_BASE_URL = "ai.baseUrl";
    public static final String KEY_API_KEY = "ai.apiKey";
    public static final String KEY_MODEL = "ai.model";
    public static final String KEY_TIMEOUT = "ai.timeoutSeconds";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private final AppSettingMapper mapper;
    private final AiProperties props;
    private final AuditService audit;

    public SettingsService(AppSettingMapper mapper, AiProperties props, AuditService audit) {
        this.mapper = mapper;
        this.props = props;
        this.audit = audit;
    }

    /** 解析后的生效配置。 */
    public record AiConfig(String baseUrl, String apiKey, String model, int timeoutSeconds, String keySource) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** 给前端看时把 Key 打码，只留末四位。 */
        public String maskedKey() {
            if (!configured()) {
                return "";
            }
            String k = apiKey.trim();
            if (k.length() <= 8) {
                return "****";
            }
            return "****" + k.substring(k.length() - 4);
        }
    }

    public AiConfig resolve() {
        String dbBase = value(KEY_BASE_URL);
        String dbKey = value(KEY_API_KEY);
        String dbModel = value(KEY_MODEL);
        String dbTimeout = value(KEY_TIMEOUT);

        String baseUrl = firstNonBlank(dbBase, props.baseUrl(), DEFAULT_BASE_URL);
        String model = firstNonBlank(dbModel, props.model(), DEFAULT_MODEL);
        String key = firstNonBlank(dbKey, props.apiKey(), null);
        int timeout = parseTimeout(firstNonBlank(dbTimeout, String.valueOf(props.timeoutSeconds()), "30"));
        String source = notBlank(dbKey) ? "DATABASE" : (notBlank(props.apiKey()) ? "ENV" : "NONE");
        return new AiConfig(stripTrailingSlash(baseUrl), key, model, timeout, source);
    }

    /** 界面上展示的完整设置（Key 打码）。 */
    public Map<String, Object> viewSettings() {
        AiConfig c = resolve();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseUrl", c.baseUrl());
        m.put("model", c.model());
        m.put("timeoutSeconds", c.timeoutSeconds());
        m.put("configured", c.configured());
        m.put("maskedKey", c.maskedKey());
        m.put("keySource", c.keySource());
        return m;
    }

    /**
     * 保存设置。{@code apiKey} 传空表示"不改 Key"（页面上不会回显明文，避免误清空）。
     */
    public Map<String, Object> save(Long userId, String baseUrl, String apiKey, String model, Integer timeoutSeconds, boolean clearKey) {
        if (notBlank(baseUrl) && !baseUrl.startsWith("http")) {
            throw new BizException(400, "接口地址要以 http(s):// 开头");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 5 || timeoutSeconds > 120)) {
            throw new BizException(400, "超时时间请填 5–120 秒");
        }
        if (notBlank(baseUrl)) {
            upsert(KEY_BASE_URL, stripTrailingSlash(baseUrl.trim()), userId);
        }
        if (notBlank(model)) {
            upsert(KEY_MODEL, model.trim(), userId);
        }
        if (timeoutSeconds != null) {
            upsert(KEY_TIMEOUT, String.valueOf(timeoutSeconds), userId);
        }
        if (clearKey) {
            upsert(KEY_API_KEY, "", userId);
        } else if (notBlank(apiKey)) {
            upsert(KEY_API_KEY, apiKey.trim(), userId);
        }
        audit.record("AI_SETTINGS_SAVE", "APP_SETTING", null, "baseUrl=" + baseUrl + ", model=" + model + (notBlank(apiKey) ? ", key=已更新" : ""));
        return viewSettings();
    }

    private void upsert(String key, String value, Long userId) {
        AppSetting existing = mapper.selectById(key);
        AppSetting row = new AppSetting();
        row.setSettingKey(key);
        row.setSettingValue(value);
        row.setUpdatedBy(userId);
        row.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    private String value(String key) {
        QueryWrapper<AppSetting> q = new QueryWrapper<>();
        q.eq("setting_key", key);
        AppSetting row = mapper.selectOne(q);
        return row == null ? null : row.getSettingValue();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static int parseTimeout(String raw) {
        try {
            return Math.max(5, Integer.parseInt(raw.trim()));
        } catch (Exception e) {
            return 30;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
