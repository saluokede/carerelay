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

import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 预警：体征越界自动生成，医护处置后闭环。
 */
@RestController
@RequestMapping("/api/alerts")
public class StationAlertController {

    private static final String JOIN = "SELECT a.id, a.elder_id, a.level, a.reason, a.status, a.conclusion, "
            + "e.name AS elder_name, DATE_FORMAT(a.created_at,'%Y-%m-%d %H:%i:%s') AS created_at "
            + "FROM alerts a JOIN elders e ON e.id = a.elder_id ";

    private final JdbcTemplate jdbc;

    public StationAlertController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        StationContext.require();
        List<Map<String, Object>> rows = (status == null || status.isBlank())
                ? jdbc.queryForList(JOIN + "ORDER BY CASE a.status WHEN '未闭环' THEN 0 ELSE 1 END, a.created_at DESC, a.id DESC")
                : jdbc.queryForList(JOIN + "WHERE a.status = ? ORDER BY a.created_at DESC, a.id DESC", status);
        return ApiResult.ok(rows);
    }

    @PostMapping("/{id}/close")
    public ApiResult<Void> close(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        StationSupport.requireRole("nurse", "family_doctor", "hospital");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + StationSupport.ALERT_COLS + " FROM alerts WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(404, "预警不存在");
        }
        String conclusion = StationSupport.str(body, "conclusion");
        if (conclusion == null) {
            throw new BizException(400, "关闭预警须填写处置结论");
        }
        Map<String, Object> alert = rows.get(0);
        jdbc.update("UPDATE alerts SET status = '已闭环', conclusion = ? WHERE id = ?", conclusion.trim(), id);
        jdbc.update("UPDATE elders SET alerts = alerts - 1 WHERE id = ? AND alerts > 0", alert.get("elder_id"));
        return ApiResult.ok();
    }
}
