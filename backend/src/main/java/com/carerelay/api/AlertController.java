package com.carerelay.api;

import com.carerelay.alert.AlertService;
import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
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

@Tag(name = "执行环 · 预警与工单")
@RestController
@RequestMapping("/api/v1")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @Operation(summary = "预警列表（默认全部，可传 status=OPEN）")
    @GetMapping("/alerts")
    public ApiResult<List<Map<String, Object>>> alerts(@RequestParam(required = false) String status) {
        UserContext.require();
        return ApiResult.ok(alertService.list(status));
    }

    @Operation(summary = "关闭预警（护士/家庭医生处置）")
    @PostMapping("/alerts/{id}/close")
    public ApiResult<Map<String, Object>> close(@PathVariable Long id,
                                                @RequestBody(required = false) CloseRequest body) {
        UserContext.require();
        return ApiResult.ok(alertService.close(id, body == null ? null : body.note()));
    }

    @Operation(summary = "工单列表")
    @GetMapping("/tickets")
    public ApiResult<List<Map<String, Object>>> tickets(@RequestParam(required = false) String status) {
        UserContext.require();
        return ApiResult.ok(alertService.tickets(status));
    }

    @Operation(summary = "工单处置")
    @PostMapping("/tickets/{id}/handle")
    public ApiResult<Map<String, Object>> handle(@PathVariable Long id,
                                                 @RequestBody(required = false) CloseRequest body) {
        UserContext.require();
        return ApiResult.ok(alertService.handle(id, body == null ? null : body.note()));
    }

    public record CloseRequest(String note) {
    }
}
