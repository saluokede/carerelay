package com.carerelay.station;

import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 老人档案与体征 / 随访记录。
 */
@RestController
@RequestMapping("/api/elders")
public class StationElderController {

    private static final String ORDER = " ORDER BY CASE risk WHEN '高' THEN 0 WHEN '中' THEN 1 ELSE 2 END, `day` DESC";

    private final JdbcTemplate jdbc;
    private final StationAiService aiService;

    public StationElderController(JdbcTemplate jdbc, StationAiService aiService) {
        this.jdbc = jdbc;
        this.aiService = aiService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        StationUser user = StationContext.require();
        List<Map<String, Object>> rows;
        if (user.isFamilyLike()) {
            if (user.elderId() == null) {
                return ApiResult.ok(new ArrayList<>());
            }
            rows = jdbc.queryForList("SELECT " + StationSupport.ELDER_COLS + " FROM elders WHERE id = ?", user.elderId());
        } else {
            rows = jdbc.queryForList("SELECT " + StationSupport.ELDER_COLS + " FROM elders" + ORDER);
        }
        return ApiResult.ok(rows);
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        StationUser user = StationContext.require();
        Map<String, Object> elder = StationSupport.elder(jdbc, id);
        StationSupport.assertBound(user, elder);
        return ApiResult.ok(elder);
    }

    @GetMapping("/{id}/records")
    public ApiResult<List<Map<String, Object>>> records(@PathVariable long id) {
        StationUser user = StationContext.require();
        StationSupport.assertBound(user, StationSupport.elder(jdbc, id));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + StationSupport.RECORD_COLS + " FROM records WHERE elder_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT 20", id);
        return ApiResult.ok(rows);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        StationSupport.requireRole("admin");
        String name = StationSupport.str(body, "name");
        String diag = StationSupport.str(body, "diag");
        if (name == null || diag == null) {
            throw new BizException(400, "姓名和出院诊断不能为空");
        }
        String risk = StationSupport.str(body, "risk", "低");
        final String riskValue = List.of("高", "中", "低").contains(risk) ? risk : "低";
        Integer age = StationSupport.integer(body, "age");
        Integer day = StationSupport.integer(body, "day");
        Integer rate = StationSupport.integer(body, "rate");
        Integer score = StationSupport.integer(body, "risk_score");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO elders (name,age,gender,diag,risk,risk_score,rate,alerts,`day`,contact,address,living,"
                            + "emergency_contact,emergency_phone,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            if (age == null) {
                ps.setObject(2, null);
            } else {
                ps.setInt(2, age);
            }
            ps.setString(3, StationSupport.str(body, "gender", ""));
            ps.setString(4, diag);
            ps.setString(5, riskValue);
            if (score == null) {
                ps.setObject(6, null);
            } else {
                ps.setInt(6, score);
            }
            ps.setInt(7, rate == null ? 0 : rate);
            ps.setInt(8, 0);
            ps.setInt(9, day == null ? 0 : day);
            ps.setString(10, StationSupport.str(body, "contact", ""));
            ps.setString(11, StationSupport.str(body, "address", ""));
            ps.setString(12, StationSupport.str(body, "living", ""));
            ps.setString(13, StationSupport.str(body, "emergency_contact", ""));
            ps.setString(14, StationSupport.str(body, "emergency_phone", ""));
            ps.setString(15, StationSupport.str(body, "status", "在管"));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
        return ApiResult.ok(StationSupport.elder(jdbc, id));
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable long id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationSupport.requireRole("family_doctor", "hospital", "admin");
        StationSupport.elder(jdbc, id);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("name", "name");
        columns.put("gender", "gender");
        columns.put("diag", "diag");
        columns.put("risk", "risk");
        columns.put("contact", "contact");
        columns.put("address", "address");
        columns.put("living", "living");
        columns.put("emergency_contact", "emergency_contact");
        columns.put("emergency_phone", "emergency_phone");
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String value = StationSupport.str(body, entry.getKey());
            if (value == null) {
                continue;
            }
            if ("risk".equals(entry.getKey()) && !List.of("高", "中", "低").contains(value)) {
                continue;
            }
            sets.add(entry.getValue() + " = ?");
            args.add(value);
        }
        Integer age = StationSupport.integer(body, "age");
        if (age != null) {
            sets.add("age = ?");
            args.add(age);
        }
        if (!sets.isEmpty()) {
            args.add(id);
            jdbc.update("UPDATE elders SET " + String.join(", ", sets) + " WHERE id = ?", args.toArray());
        }
        return ApiResult.ok(StationSupport.elder(jdbc, id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        StationSupport.requireRole("admin");
        StationSupport.elder(jdbc, id);
        for (String table : List.of("plans", "followups", "messages", "revisits", "alerts", "records", "ai_logs")) {
            jdbc.update("DELETE FROM " + table + " WHERE elder_id = ?", id);
        }
        jdbc.update("UPDATE `users` SET elder_id = NULL WHERE elder_id = ?", id);
        jdbc.update("DELETE FROM elders WHERE id = ?", id);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/records")
    public ApiResult<Map<String, Object>> addRecord(@PathVariable long id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationContext.require();
        Map<String, Object> elder = StationSupport.elder(jdbc, id);
        StationSupport.assertBound(user, elder);
        String title = StationSupport.str(body, "title");
        if (title == null) {
            throw new BizException(400, "记录标题不能为空");
        }
        String category = StationSupport.str(body, "category", "常规");
        String detail = StationSupport.str(body, "detail", "");
        String value = StationSupport.str(body, "value", "");
        jdbc.update("INSERT INTO records (elder_id,category,title,detail,`value`,source) VALUES (?,?,?,?,?,?)",
                id, category, title, detail, value, user.name());

        String reason = aiService.vitalAlertReason(category, value);
        if (reason != null) {
            jdbc.update("INSERT INTO alerts (elder_id,level,reason,status) VALUES (?,?,?,?)",
                    id, "高", reason, "未闭环");
            jdbc.update("UPDATE elders SET alerts = alerts + 1 WHERE id = ?", id);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alert", reason != null);
        result.put("reason", reason);
        return ApiResult.ok(result);
    }
}
