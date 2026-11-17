package com.carerelay.station;

import com.carerelay.common.AiGateway;
import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import com.carerelay.system.SettingsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 管理端：账号启停、公告发布、辖区报表导出。
 */
@RestController
@RequestMapping("/api/admin")
public class StationAdminController {

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final AiGateway gateway;
    public StationAdminController(JdbcTemplate jdbc, SettingsService settings, AiGateway gateway) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.gateway = gateway;
    }

    @GetMapping("/users")
    public ApiResult<List<Map<String, Object>>> users() {
        StationSupport.requireRole("admin");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, name, role, elder_id, phone, status, "
                        + "DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS created_at "
                        + "FROM `users` ORDER BY role, id");
        return ApiResult.ok(rows);
    }

    @PostMapping("/users/{id}/toggle")
    public ApiResult<Map<String, Object>> toggle(@PathVariable long id) {
        StationUser admin = StationSupport.requireRole("admin");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM `users` WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(404, "账号不存在");
        }
        if (admin.id() == id) {
            throw new BizException(400, "不可停用当前登录账号");
        }
        String current = String.valueOf(rows.get(0).get("status"));
        String next = "启用".equals(current) ? "停用" : "启用";
        jdbc.update("UPDATE `users` SET status = ? WHERE id = ?", next, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", next);
        return ApiResult.ok(data);
    }

    @GetMapping("/announcements")
    public ApiResult<List<Map<String, Object>>> announcements() {
        StationContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + StationSupport.ANNOUNCEMENT_COLS + " FROM announcements ORDER BY created_at DESC, id DESC");
        return ApiResult.ok(rows);
    }

    @PostMapping("/announcements")
    public ApiResult<Map<String, Object>> publish(@RequestBody(required = false) Map<String, Object> body) {
        StationUser admin = StationSupport.requireRole("admin");
        String title = StationSupport.str(body, "title");
        String content = StationSupport.str(body, "content");
        if (title == null || content == null) {
            throw new BizException(400, "公告标题和内容不能为空");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO announcements (title,content,publisher) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title.trim());
            ps.setString(2, content.trim());
            ps.setString(3, admin.name());
            return ps;
        }, keyHolder);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue());
        return ApiResult.ok(data);
    }

    /** 辖区报表：UTF-8 BOM + CRLF，Excel 双击即正常显示中文。 */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportCsv() {
        StationSupport.requireRole("admin");
        List<Map<String, Object>> elders = jdbc.queryForList(
                "SELECT " + StationSupport.ELDER_COLS + " FROM elders "
                        + "ORDER BY CASE risk WHEN '高' THEN 0 WHEN '中' THEN 1 ELSE 2 END, `day` DESC");
        int fuTotal = count("SELECT COUNT(*) FROM followups");
        int fuDone = count("SELECT COUNT(*) FROM followups WHERE status = '已完成'");
        int alertsOpen = count("SELECT COUNT(*) FROM alerts WHERE status = '未闭环'");
        int alertsClosed = count("SELECT COUNT(*) FROM alerts WHERE status = '已闭环'");

        List<String> lines = new ArrayList<>();
        lines.add(row(List.of("老人", "年龄", "性别", "出院诊断", "风险等级", "AI参考分", "任务完成率%", "未闭环预警", "出院天数")));
        for (Map<String, Object> e : elders) {
            lines.add(row(List.of(e.get("name"), e.get("age"), e.get("gender"), e.get("diag"), e.get("risk"),
                    e.get("risk_score"), e.get("rate"), e.get("alerts"), e.get("day"))));
        }
        lines.add("");
        lines.add(row(List.of("汇总指标", "数值")));
        lines.add(row(List.of("在管老人数", elders.size())));
        lines.add(row(List.of("随访完成率%", fuTotal == 0 ? 0 : Math.round(fuDone * 100f / fuTotal))));
        lines.add(row(List.of("预警闭环率%", alertsOpen + alertsClosed == 0
                ? 0 : Math.round(alertsClosed * 100f / (alertsOpen + alertsClosed)))));

        byte[] body = ("\uFEFF" + String.join("\r\n", lines)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"yiyang-report.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /** AI 网关配置：地址 / 模型 / 超时（Key 只回显末四位）。 */
    @GetMapping("/ai-settings")
    public ApiResult<Map<String, Object>> aiSettings() {
        StationSupport.requireRole("admin");
        return ApiResult.ok(settings.viewSettings());
    }

    public record AiSettingsRequest(String baseUrl, String apiKey, String model, Integer timeoutSeconds, Boolean clearKey) {
    }

    /** 保存配置。apiKey 传空表示「不改 Key」，clearKey=true 才清空。 */
    @PutMapping("/ai-settings")
    public ApiResult<Map<String, Object>> saveAiSettings(@RequestBody AiSettingsRequest req) {
        StationSupport.requireRole("admin");
        StationUser me = StationContext.require();
        return ApiResult.ok(settings.save(me.id(), req.baseUrl(), req.apiKey(), req.model(),
                req.timeoutSeconds(), req.clearKey() != null && req.clearKey()));
    }

    /** 测试连接：不落库，传了 Key 就用新的，没传就用已保存的。 */
    @PostMapping("/ai-settings/test")
    public ApiResult<Map<String, Object>> testAiSettings(@RequestBody(required = false) AiSettingsRequest req) {
        StationSupport.requireRole("admin");
        SettingsService.AiConfig saved = settings.resolve();
        String baseUrl = req != null && notBlank(req.baseUrl()) ? req.baseUrl().trim() : saved.baseUrl();
        String key = req != null && notBlank(req.apiKey()) ? req.apiKey().trim() : saved.apiKey();
        String model = req != null && notBlank(req.model()) ? req.model().trim() : saved.model();
        int timeout = req != null && req.timeoutSeconds() != null ? req.timeoutSeconds() : saved.timeoutSeconds();
        if (!notBlank(key)) {
            throw new BizException(400, "还没有可用的 API Key：请先填写再测试");
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        long started = System.currentTimeMillis();
        SettingsService.AiConfig probe = new SettingsService.AiConfig(baseUrl, key, model, Math.max(5, timeout), "PROBE");
        try {
            String reply = gateway.chatWith(probe, "你是一个连通性检测助手，只回一个字。", "回复：好");
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("model", model);
            ok.put("latencyMs", System.currentTimeMillis() - started);
            ok.put("reply", reply.length() > 40 ? reply.substring(0, 40) + "…" : reply);
            return ApiResult.ok(ok);
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("ok", false);
            fail.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            fail.put("latencyMs", System.currentTimeMillis() - started);
            return ApiResult.ok(fail);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private String row(List<Object> cells) {
        List<String> escaped = new ArrayList<>();
        for (Object cell : cells) {
            escaped.add("\"" + (cell == null ? "" : String.valueOf(cell)).replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }
}
