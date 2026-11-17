package com.carerelay.station;

import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 康复方案：AI 起草 → 医生签发 → 家属端可见。
 */
@RestController
@RequestMapping("/api/plans")
public class StationPlanController {

    private final JdbcTemplate jdbc;
    private final StationAiService aiService;

    public StationPlanController(JdbcTemplate jdbc, StationAiService aiService) {
        this.jdbc = jdbc;
        this.aiService = aiService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(name = "elder_id", required = false) Long elderId) {
        StationContext.require();
        List<Map<String, Object>> rows = elderId == null
                ? jdbc.queryForList("SELECT " + StationSupport.PLAN_COLS + " FROM plans ORDER BY created_at DESC, id DESC")
                : jdbc.queryForList("SELECT " + StationSupport.PLAN_COLS + " FROM plans WHERE elder_id = ? "
                        + "ORDER BY created_at DESC, id DESC", elderId);
        return ApiResult.ok(rows);
    }

    @PostMapping("/ai-draft")
    public ApiResult<Map<String, Object>> aiDraft(@RequestBody(required = false) Map<String, Object> body) {
        StationContext.require();
        StationSupport.requireRole("family_doctor", "hospital");
        Long elderId = StationSupport.longOrNull(body == null ? null : body.get("elder_id"));
        Map<String, Object> elder = StationSupport.elder(jdbc, elderId);
        Map<String, Object> draft = aiService.draftPlan(elder);
        String content = "运动康复：" + draft.get("motion") + "\n饮食建议：" + draft.get("diet")
                + "\n用药提醒：" + draft.get("med");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO plans (elder_id,`type`,content,ai_draft,status) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, StationSupport.longOf(elder.get("id")));
            ps.setString(2, "康复");
            ps.setString(3, content);
            ps.setInt(4, 1);
            ps.setString(5, "待签发");
            return ps;
        }, keyHolder);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue());
        data.put("elder_id", StationSupport.longOf(elder.get("id")));
        data.put("motion", draft.get("motion"));
        data.put("diet", draft.get("diet"));
        data.put("med", draft.get("med"));
        data.put("usedMock", draft.get("usedMock"));
        return ApiResult.ok(data);
    }

    @PostMapping("/{id}/issue")
    public ApiResult<Map<String, Object>> issue(@PathVariable long id) {
        StationUser user = StationSupport.requireRole("family_doctor", "hospital");
        plan(jdbc, id);
        jdbc.update("UPDATE plans SET status = '已签发', ai_draft = 0, issued_by = ? WHERE id = ?", user.name(), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return ApiResult.ok(data);
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable long id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        StationSupport.requireRole("family_doctor", "hospital");
        Map<String, Object> plan = plan(jdbc, id);
        String content = StationSupport.str(body, "content");
        if (content == null) {
            throw new BizException(400, "方案内容不能为空");
        }
        String type = StationSupport.str(body, "type", String.valueOf(plan.get("type")));
        String status = "已签发".equals(String.valueOf(plan.get("status"))) ? "已调整" : String.valueOf(plan.get("status"));
        jdbc.update("UPDATE plans SET `type` = ?, content = ?, status = ? WHERE id = ?", type, content.trim(), status, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", status);
        return ApiResult.ok(data);
    }

    private Map<String, Object> plan(JdbcTemplate jdbc, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + StationSupport.PLAN_COLS + " FROM plans WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(404, "方案不存在");
        }
        return rows.get(0);
    }
}
