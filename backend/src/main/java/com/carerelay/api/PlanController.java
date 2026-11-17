package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import com.carerelay.plan.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "交接环 · 照护计划")
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @Operation(summary = "生成 30 天计划草案（同时重算风险分层）")
    @PostMapping("/generate")
    public ApiResult<Map<String, Object>> generate(@RequestBody GenerateRequest request) {
        UserContext.require();
        return ApiResult.ok(planService.generate(request.elderId(), request.recordId()));
    }

    @Operation(summary = "护士签发（DRAFT → ISSUED）")
    @PostMapping("/{id}/issue")
    public ApiResult<Map<String, Object>> issue(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(planService.issue(id));
    }

    @Operation(summary = "计划详情")
    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(planService.detail(id));
    }

    @Operation(summary = "某位老人的全部计划")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam Long elderId) {
        UserContext.require();
        return ApiResult.ok(planService.listByElder(elderId));
    }

    @Operation(summary = "计划展开的每日任务")
    @GetMapping("/{id}/tasks")
    public ApiResult<List<Map<String, Object>>> tasks(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(planService.tasks(id));
    }

    @Operation(summary = "会诊留言（医生/护士都能留）")
    @GetMapping("/{id}/comments")
    public ApiResult<List<Map<String, Object>>> comments(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(planService.comments(id));
    }

    @Operation(summary = "留一条会诊意见")
    @PostMapping("/{id}/comments")
    public ApiResult<Map<String, Object>> addComment(@PathVariable Long id, @RequestBody CommentRequest request) {
        UserContext.require();
        return ApiResult.ok(planService.addComment(id, request.content()));
    }

    public record GenerateRequest(Long elderId, Long recordId) {
    }

    public record CommentRequest(String content) {
    }
}
