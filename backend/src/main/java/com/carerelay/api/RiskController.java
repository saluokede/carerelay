package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import com.carerelay.risk.RiskAssessment;
import com.carerelay.risk.RiskScoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "交接环 · 风险评分卡")
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private final RiskScoringService riskService;

    public RiskController(RiskScoringService riskService) {
        this.riskService = riskService;
    }

    @Operation(summary = "按最新出院记录算风险分（代码算分，模型只解释）")
    @PostMapping("/assess")
    public ApiResult<Map<String, Object>> assess(@RequestBody AssessRequest request) {
        UserContext.require();
        RiskAssessment assessment = riskService.assess(request.elderId(), request.recordId());
        return ApiResult.ok(riskService.toView(assessment));
    }

    @Operation(summary = "查看最近一次风险结果")
    @GetMapping("/latest")
    public ApiResult<Map<String, Object>> latest(@RequestParam Long elderId) {
        UserContext.require();
        RiskAssessment assessment = riskService.latest(elderId);
        return ApiResult.ok(assessment == null ? null : riskService.toView(assessment));
    }

    public record AssessRequest(Long elderId, Long recordId) {
    }
}
