package com.carerelay.api;

import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import com.carerelay.elder.ElderService;
import com.carerelay.elder.dto.CreateElderRequest;
import com.carerelay.elder.dto.UpdateElderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "交接环 · 老人档案")
@RestController
@RequestMapping("/api/v1/elders")
public class ElderController {

    private final ElderService elderService;

    public ElderController(ElderService elderService) {
        this.elderService = elderService;
    }

    @Operation(summary = "建档")
    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody CreateElderRequest request) {
        UserContext.require();
        return ApiResult.ok(elderService.create(request));
    }

    @Operation(summary = "档案列表（可按姓名检索）")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        UserContext.require();
        return ApiResult.ok(elderService.list(keyword));
    }

    @Operation(summary = "档案详情")
    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        UserContext.require();
        return ApiResult.ok(elderService.detail(id));
    }

    @Operation(summary = "修改档案（床号、住院状态、联系方式等，按字段打补丁）")
    @PatchMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable Long id, @RequestBody UpdateElderRequest request) {
        UserContext.require();
        return ApiResult.ok(elderService.update(id, request));
    }
}
