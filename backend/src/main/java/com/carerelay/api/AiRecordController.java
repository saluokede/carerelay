package com.carerelay.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.ai.AiRequestRecord;
import com.carerelay.ai.mapper.AiRequestMapper;
import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 调用记录：把"每一次模型调用都可追溯"从数据库搬到界面。
 *
 * <p>评审最想确认的是"AI 到底有没有在工作、有没有被采纳"，这一页就是自证材料：
 * 场景、模式（MODEL/LOCAL）、模型名、耗时、是否采纳、失败原因。</p>
 */
@Tag(name = "AI · 调用记录")
@RestController
@RequestMapping("/api/v1/ai/records")
public class AiRecordController {

    private final AiRequestMapper mapper;

    public AiRecordController(AiRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Operation(summary = "最近的 AI 调用记录（仅管理员）")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> recent(@RequestParam(required = false) String scene,
                                                       @RequestParam(defaultValue = "50") int limit) {
        UserContext.require();
        int size = Math.max(1, Math.min(limit, 200));
        var query = Wrappers.<AiRequestRecord>lambdaQuery().orderByDesc(AiRequestRecord::getId);
        if (scene != null && !scene.isBlank()) {
            query = query.eq(AiRequestRecord::getScene, scene.trim().toUpperCase());
        }
        List<Map<String, Object>> rows = mapper.selectList(query.last("limit " + size))
                .stream()
                .map(AiRecordController::toView)
                .toList();
        return ApiResult.ok(rows);
    }

    private static Map<String, Object> toView(AiRequestRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("scene", r.getScene());
        m.put("mode", r.getMode());
        m.put("model", r.getModel());
        m.put("status", r.getStatus());
        m.put("latencyMs", r.getLatencyMs());
        m.put("adopted", r.getAdopted());
        m.put("error", r.getError());
        m.put("inputChars", r.getInputJson() == null ? 0 : r.getInputJson().length());
        m.put("outputChars", r.getOutputJson() == null ? 0 : r.getOutputJson().length());
        m.put("userId", r.getUserId());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
