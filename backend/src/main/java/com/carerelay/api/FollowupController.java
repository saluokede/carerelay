package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import com.carerelay.followup.FollowupService;
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

@Tag(name = "随访环 · 随访会话")
@RestController
@RequestMapping("/api/v1/followups")
public class FollowupController {

    private final FollowupService followupService;

    public FollowupController(FollowupService followupService) {
        this.followupService = followupService;
    }

    @Operation(summary = "按计划随访间隔排期（默认取最新已签发计划的 interval_days）")
    @PostMapping("/schedule")
    public ApiResult<Map<String, Object>> schedule(@RequestBody ScheduleRequest body) {
        UserContext.require();
        return ApiResult.ok(followupService.schedule(body.elderId(), body.plannedDate()));
    }

    @Operation(summary = "某位老人的随访会话列表")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam Long elderId) {
        UserContext.require();
        return ApiResult.ok(followupService.list(elderId));
    }

    @Operation(summary = "今天该随访与已逾期的会话")
    @GetMapping("/due")
    public ApiResult<List<Map<String, Object>>> due() {
        UserContext.require();
        return ApiResult.ok(followupService.due());
    }

    @Operation(summary = "开始随访，返回开场白与第一个问题")
    @PostMapping("/{id}/start")
    public ApiResult<Map<String, Object>> start(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(followupService.start(id));
    }

    @Operation(summary = "一轮对话")
    @PostMapping("/{id}/messages")
    public ApiResult<Map<String, Object>> message(@PathVariable Long id, @RequestBody MessageRequest body) {
        UserContext.require();
        return ApiResult.ok(followupService.message(id, body.text()));
    }

    @Operation(summary = "结束随访，生成结构化记录（红旗自动升级）")
    @PostMapping("/{id}/finish")
    public ApiResult<Map<String, Object>> finish(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(followupService.finish(id));
    }

    public record ScheduleRequest(Long elderId,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedDate) {
    }

    public record MessageRequest(String text) {
    }
}
