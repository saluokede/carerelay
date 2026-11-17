package com.carerelay.station;

import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新版工作台 · AI 接力台接口。
 *
 * <p>{@code /run} 只读 + 可回写风险分，任何落库动作都走 {@code /apply}，
 * 由医生点「确认执行」后逐条写入，前端把动作列成清单，避免模型直接改病历。</p>
 */
@RestController
@RequestMapping("/api/ai/agent")
public class StationAgentController {

    private static final Set<String> TYPES = Set.of("plan", "followup", "record");

    private final JdbcTemplate jdbc;
    private final StationAgentService agent;

    public StationAgentController(JdbcTemplate jdbc, StationAgentService agent) {
        this.jdbc = jdbc;
        this.agent = agent;
    }

    @PostMapping("/run")
    public ApiResult<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body) {
        StationSupport.requireRole("hospital", "family_doctor", "nurse");
        Long elderId = StationSupport.longOrNull(body == null ? null : body.get("elder_id"));
        if (elderId == null) {
            throw new BizException(400, "请选择要评估的老人");
        }
        return ApiResult.ok(agent.run(elderId, StationSupport.str(body, "goal")));
    }

    /** 确认执行 Agent 建议的动作：签发方案 / 排随访 / 归档记录。 */
    @PostMapping("/apply")
    public ApiResult<Map<String, Object>> apply(@RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationSupport.requireRole("hospital", "family_doctor");
        Long elderId = StationSupport.longOrNull(body == null ? null : body.get("elder_id"));
        if (elderId == null) {
            throw new BizException(400, "请选择老人");
        }
        StationSupport.elder(jdbc, elderId);
        Object raw = body == null ? null : body.get("actions");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BizException(400, "没有可执行的动作");
        }
        if (list.size() > 5) {
            throw new BizException(400, "一次最多执行 5 条动作");
        }

        List<Map<String, Object>> applied = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> action)) {
                continue;
            }
            String type = String.valueOf(action.get("type"));
            if (!TYPES.contains(type)) {
                throw new BizException(400, "不支持的动作类型：" + type);
            }
            Map<?, ?> payload = action.get("payload") instanceof Map<?, ?> p ? p : Map.of();
            String title = text(action.get("title"));
            String detail = text(action.get("detail"));
            long id = applyOne(elderId, type, title, detail, payload, user);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("id", id);
            row.put("title", title);
            applied.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", applied);
        out.put("count", applied.size());
        return ApiResult.ok(out);
    }

    private long applyOne(long elderId, String type, String title, String detail,
                          Map<?, ?> payload, StationUser user) {
        KeyHolder keys = new GeneratedKeyHolder();
        switch (type) {
            case "plan" -> {
                String content = firstNonBlank(text(payload.get("content")), detail, title);
                if (content.isBlank()) {
                    throw new BizException(400, "方案内容为空");
                }
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO plans (elder_id, `type`, content, ai_draft, status, issued_by) VALUES (?,?,?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, elderId);
                    ps.setString(2, "康复");
                    ps.setString(3, content);
                    ps.setInt(4, 1);
                    ps.setString(5, "待签发");
                    ps.setString(6, user.name());
                    return ps;
                }, keys);
            }
            case "followup" -> {
                String followType = firstNonBlank(text(payload.get("type")), "电话随访");
                String scheduled = firstNonBlank(text(payload.get("scheduled_at")), "3 天后");
                String note = firstNonBlank(text(payload.get("note")), detail, title);
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO followups (elder_id, `type`, scheduled_at, address, note, status, review_status) "
                                    + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, elderId);
                    ps.setString(2, followType);
                    ps.setString(3, scheduled);
                    ps.setString(4, "电话回访");
                    ps.setString(5, note);
                    ps.setString(6, "待执行");
                    ps.setString(7, "待审核");
                    return ps;
                }, keys);
            }
            default -> {
                String category = firstNonBlank(text(payload.get("category")), "护理");
                String recordTitle = firstNonBlank(title, "AI 接力评估结论");
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO records (elder_id, category, title, detail, `value`, source) VALUES (?,?,?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, elderId);
                    ps.setString(2, category);
                    ps.setString(3, recordTitle);
                    ps.setString(4, detail);
                    ps.setString(5, "");
                    ps.setString(6, user.name());
                    return ps;
                }, keys);
            }
        }
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
