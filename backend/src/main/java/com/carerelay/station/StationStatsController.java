package com.carerelay.station;

import com.carerelay.common.ApiResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 首页统计与健康检查。
 */
@RestController
@RequestMapping("/api")
public class StationStatsController {

    private final JdbcTemplate jdbc;

    public StationStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ApiResult<Void> health() {
        return ApiResult.ok();
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats() {
        StationContext.require();
        int total = count("SELECT COUNT(*) FROM elders");
        List<Map<String, Object>> byRisk = jdbc.queryForList(
                "SELECT risk, COUNT(*) AS n FROM elders GROUP BY risk");
        int alertsOpen = count("SELECT COUNT(*) FROM alerts WHERE status = '未闭环'");
        int alertsClosed = count("SELECT COUNT(*) FROM alerts WHERE status = '已闭环'");
        int followupTotal = count("SELECT COUNT(*) FROM followups");
        int followupDone = count("SELECT COUNT(*) FROM followups WHERE status = '已完成'");
        int plansPending = count("SELECT COUNT(*) FROM plans WHERE status = '待签发'");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("byRisk", byRisk);
        data.put("alertsOpen", alertsOpen);
        data.put("alertsClosed", alertsClosed);
        data.put("alertCloseRate", rate(alertsClosed, alertsOpen + alertsClosed));
        data.put("followupTotal", followupTotal);
        data.put("followupDone", followupDone);
        data.put("followupRate", rate(followupDone, followupTotal));
        data.put("plansPending", plansPending);
        return ApiResult.ok(data);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private int rate(int a, int b) {
        return b == 0 ? 0 : Math.round(a * 100f / b);
    }
}
