package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import com.carerelay.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "复盘环 · 周报与结局")
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(summary = "生成（或重算）某位老人的周报：家属版 + 医生版")
    @PostMapping("/weekly")
    public ApiResult<Map<String, Object>> weekly(@RequestBody WeeklyRequest body) {
        UserContext.require();
        return ApiResult.ok(reportService.weekly(body.elderId(), body.weekEnd()));
    }

    @Operation(summary = "某位老人的报告列表")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam Long elderId) {
        UserContext.require();
        return ApiResult.ok(reportService.list(elderId));
    }

    @Operation(summary = "报告详情（含当期统计快照）")
    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(reportService.detail(id));
    }

    @Operation(summary = "出院后 30 天结局小结")
    @PostMapping("/outcome")
    public ApiResult<Map<String, Object>> outcome(@RequestBody OutcomeRequest body) {
        UserContext.require();
        return ApiResult.ok(reportService.outcome(body.elderId()));
    }

    @Operation(summary = "护士台总览：风险分布、今日随访、未处理预警与工单、平均完成率")
    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        UserContext.require();
        return ApiResult.ok(reportService.overview());
    }

    public record WeeklyRequest(Long elderId,
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd) {
    }

    public record OutcomeRequest(Long elderId) {
    }
}
