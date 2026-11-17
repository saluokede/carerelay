package com.carerelay.api;

import com.carerelay.admission.AdmissionService;
import com.carerelay.admission.dto.AdmissionRequest;
import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 住院记录：每次住院一条，床位的历史都在这里。 */
@Tag(name = "档案 · 住院记录")
@RestController
@RequestMapping("/api/v1")
public class AdmissionController {

    private final AdmissionService service;

    public AdmissionController(AdmissionService service) {
        this.service = service;
    }

    @Operation(summary = "某位老人的住院记录（新→旧）")
    @GetMapping("/elders/{elderId}/admissions")
    public ApiResult<List<Map<String, Object>>> list(@PathVariable Long elderId) {
        UserContext.require();
        return ApiResult.ok(service.list(elderId));
    }

    @Operation(summary = "登记一次住院（不带出院日期＝现在在院；带＝补登历史）")
    @PostMapping("/elders/{elderId}/admissions")
    public ApiResult<Map<String, Object>> admit(@PathVariable Long elderId, @RequestBody AdmissionRequest request) {
        UserContext.require();
        return ApiResult.ok(service.admit(elderId, request));
    }

    @Operation(summary = "办理出院")
    @PostMapping("/admissions/{id}/discharge")
    public ApiResult<Map<String, Object>> discharge(@PathVariable Long id, @RequestBody(required = false) DischargeRequest request) {
        UserContext.require();
        return ApiResult.ok(service.discharge(id, request == null ? null : request.dischargeDate()));
    }

    @Operation(summary = "修改住院记录（录错了改这里；给在院记录补出院日期＝办理出院）")
    @PatchMapping("/admissions/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable Long id, @RequestBody AdmissionRequest request) {
        UserContext.require();
        return ApiResult.ok(service.update(id, request));
    }

    @Operation(summary = "作废住院记录（不物理删除，留原因）")
    @PostMapping("/admissions/{id}/void")
    public ApiResult<Map<String, Object>> voidAdmission(@PathVariable Long id,
                                                        @RequestBody(required = false) VoidRequest request) {
        UserContext.require();
        return ApiResult.ok(service.voidAdmission(id, request == null ? null : request.reason()));
    }

    @Operation(summary = "每位老人的住院次数与累计天数（护士台用）")
    @GetMapping("/admissions/stats")
    public ApiResult<List<Map<String, Object>>> stats() {
        UserContext.require();
        return ApiResult.ok(service.stats());
    }

    public record DischargeRequest(String dischargeDate) {
    }

    public record VoidRequest(String reason) {
    }
}
