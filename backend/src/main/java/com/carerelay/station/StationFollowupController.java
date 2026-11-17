package com.carerelay.station;

import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 随访工单：护士执行回填 → 医生审核。
 */
@RestController
@RequestMapping("/api/followups")
public class StationFollowupController {

    private static final String JOIN = "SELECT f.id, f.elder_id, f.`type`, f.scheduled_at, f.address, f.note, f.status, "
            + "f.reporter, f.review_status, f.review_note, e.name AS elder_name, "
            + "DATE_FORMAT(f.created_at,'%Y-%m-%d %H:%i:%s') AS created_at "
            + "FROM followups f JOIN elders e ON e.id = f.elder_id ";

    private final JdbcTemplate jdbc;

    public StationFollowupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String review) {
        StationContext.require();
        List<Map<String, Object>> rows;
        if (review != null && !review.isBlank()) {
            rows = jdbc.queryForList(JOIN + "WHERE f.review_status = ? AND f.status = '已完成' "
                    + "ORDER BY f.created_at DESC, f.id DESC", review);
        } else if (status != null && !status.isBlank()) {
            rows = jdbc.queryForList(JOIN + "WHERE f.status = ? ORDER BY f.scheduled_at, f.id", status);
        } else {
            rows = jdbc.queryForList(JOIN + "ORDER BY f.scheduled_at, f.id");
        }
        return ApiResult.ok(rows);
    }

    @PostMapping("/{id}/complete")
    public ApiResult<Void> complete(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        StationUser user = StationSupport.requireRole("nurse");
        Map<String, Object> fu = followup(id);
        String note = StationSupport.str(body, "note", fu.get("note") == null ? "" : String.valueOf(fu.get("note")));
        jdbc.update("UPDATE followups SET status = '已完成', reporter = ?, note = ?, review_status = '待审核' WHERE id = ?",
                user.name(), note, id);
        jdbc.update("INSERT INTO records (elder_id,category,title,detail,source) VALUES (?,?,?,?,?)",
                fu.get("elder_id"), "随访", fu.get("type") + "已完成", note, user.name());
        return ApiResult.ok();
    }

    @PostMapping("/{id}/review")
    public ApiResult<Map<String, Object>> review(@PathVariable long id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        StationSupport.requireRole("family_doctor", "hospital");
        Map<String, Object> fu = followup(id);
        if (!"已完成".equals(String.valueOf(fu.get("status")))) {
            throw new BizException(400, "仅已完成的随访记录可审核");
        }
        Object pass = body == null ? null : body.get("pass");
        boolean approved = Boolean.TRUE.equals(pass) || "true".equalsIgnoreCase(String.valueOf(pass));
        String reviewStatus = approved ? "已通过" : "已驳回";
        String note = StationSupport.str(body, "note", "");
        jdbc.update("UPDATE followups SET review_status = ?, review_note = ? WHERE id = ?", reviewStatus, note, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("review_status", reviewStatus);
        return ApiResult.ok(data);
    }

    private Map<String, Object> followup(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + StationSupport.FOLLOWUP_COLS + " FROM followups WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(404, "工单不存在");
        }
        return rows.get(0);
    }
}
