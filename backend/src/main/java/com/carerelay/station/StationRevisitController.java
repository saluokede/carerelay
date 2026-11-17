package com.carerelay.station;

import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 复诊安排：医生排期、登记结论、自动派生「复诊后随访」工单。
 */
@RestController
@RequestMapping("/api/revisits")
public class StationRevisitController {

    private static final String JOIN = "SELECT r.id, r.elder_id, r.plan_at, r.reason, r.status, r.doctor, r.conclusion, "
            + "e.name AS elder_name, DATE_FORMAT(r.created_at,'%Y-%m-%d %H:%i:%s') AS created_at "
            + "FROM revisits r JOIN elders e ON e.id = r.elder_id ";

    private final JdbcTemplate jdbc;

    public StationRevisitController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        StationUser user = StationContext.require();
        List<Map<String, Object>> rows;
        if (user.isFamilyLike()) {
            rows = user.elderId() == null ? List.of()
                    : jdbc.queryForList(JOIN + "WHERE r.elder_id = ? ORDER BY r.plan_at, r.id", user.elderId());
        } else {
            rows = jdbc.queryForList(JOIN + "ORDER BY r.plan_at, r.id");
        }
        return ApiResult.ok(rows);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationSupport.requireRole("family_doctor", "hospital");
        Long elderId = StationSupport.longOrNull(body == null ? null : body.get("elder_id"));
        if (elderId == null) {
            throw new BizException(400, "缺少老人档案");
        }
        StationSupport.elder(jdbc, elderId);
        String planAt = StationSupport.str(body, "plan_at", "");
        String reason = StationSupport.str(body, "reason", "");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO revisits (elder_id,plan_at,reason,status,doctor) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, elderId);
            ps.setString(2, planAt);
            ps.setString(3, reason);
            ps.setString(4, "待复诊");
            ps.setString(5, user.name());
            return ps;
        }, keyHolder);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue());
        return ApiResult.ok(data);
    }

    @PostMapping("/{id}/complete")
    public ApiResult<Void> complete(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationSupport.requireRole("family_doctor", "hospital", "nurse");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + StationSupport.REVISIT_COLS + " FROM revisits WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(404, "复诊记录不存在");
        }
        Map<String, Object> revisit = rows.get(0);
        String conclusion = StationSupport.str(body, "conclusion", "");
        jdbc.update("UPDATE revisits SET status = '已完成', conclusion = ?, doctor = ? WHERE id = ?",
                conclusion, user.name(), id);

        Object elderId = revisit.get("elder_id");
        List<Map<String, Object>> elders = jdbc.queryForList(
                "SELECT id, name, address FROM elders WHERE id = ?", elderId);
        String elderName = elders.isEmpty() ? "" : String.valueOf(elders.get(0).get("name"));
        String address = elders.isEmpty() || elders.get(0).get("address") == null ? "" : String.valueOf(elders.get(0).get("address"));
        String detail = conclusion.isBlank() ? "复诊已完成，按医嘱继续康复随访" : conclusion;

        jdbc.update("INSERT INTO records (elder_id,category,title,detail,source) VALUES (?,?,?,?,?)",
                elderId, "复诊", "复诊完成 · " + elderName, detail, user.name());

        String scheduledAt = LocalDate.now().plusDays(3).toString();
        String note = "复诊结论：" + (conclusion.isBlank() ? "按医嘱继续康复" : conclusion)
                + "；请在 3 日内完成随访并复核体征。";
        jdbc.update("INSERT INTO followups (elder_id,`type`,scheduled_at,address,note,status,review_status) "
                + "VALUES (?,?,?,?,?,'待执行','待审核')", elderId, "复诊后随访", scheduledAt, address, note);
        return ApiResult.ok();
    }
}
