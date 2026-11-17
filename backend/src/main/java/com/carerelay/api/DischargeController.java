package com.carerelay.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.auth.UserContext;
import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import com.carerelay.discharge.DischargeExtractService;
import com.carerelay.discharge.DischargeRecord;
import com.carerelay.discharge.dto.ExtractRequest;
import com.carerelay.discharge.dto.ExtractResult;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "交接环 · 出院小结")
@RestController
@RequestMapping("/api/v1/discharge")
public class DischargeController {

    private final DischargeExtractService extractService;
    private final DischargeRecordMapper recordMapper;
    private final ObjectMapper om = new ObjectMapper();

    public DischargeController(DischargeExtractService extractService, DischargeRecordMapper recordMapper) {
        this.extractService = extractService;
        this.recordMapper = recordMapper;
    }

    @Operation(summary = "出院小结 → 结构化档案（AI 抽取，人工复核）")
    @PostMapping("/extract")
    public ApiResult<ExtractResult> extract(@RequestBody ExtractRequest request) {
        return ApiResult.ok(extractService.extract(UserContext.require().id(), request));
    }

    @Operation(summary = "按老人查历史出院记录")
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam Long elderId) {
        // 有出院日期的排前面（NULL 排在最后），同一天里的取最新一条
        List<DischargeRecord> records = recordMapper.selectList(Wrappers.<DischargeRecord>lambdaQuery()
                .eq(DischargeRecord::getElderId, elderId)
                .last("ORDER BY discharge_date IS NULL, discharge_date DESC, id DESC"));
        return ApiResult.ok(records.stream().map(this::toView).toList());
    }

    @Operation(summary = "查看单条出院记录")
    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        DischargeRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BizException(404, "出院记录不存在");
        }
        return ApiResult.ok(toView(record));
    }

    /**
     * 没有模型 Key 时，抽取会降级成"待人工填写"；所以必须给护士一条真正能填的路，
     * 否则提示语只是一句空话，风险评分和计划生成全部拿不到输入。
     */
    @Operation(summary = "人工填写/修订结构化档案（本地模式或无 Key 时的正道）")
    @PutMapping("/{id}/structured")
    public ApiResult<ExtractResult> saveStructured(@PathVariable Long id, @RequestBody JsonNode data) {
        return ApiResult.ok(extractService.saveManual(UserContext.require().id(), id, data));
    }

    /** 前端需要的形状：顶层是业务字段，结构化细节放在 data 里，避免前端写一堆 JSON.parse。 */
    private Map<String, Object> toView(DischargeRecord r) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", r.getId());
        view.put("elderId", r.getElderId());
        view.put("hospital", r.getHospital());
        view.put("department", r.getDepartment());
        view.put("admitDate", r.getAdmitDate());
        view.put("dischargeDate", r.getDischargeDate());
        view.put("diagnosisPrimary", r.getDiagnosisPrimary());
        view.put("extractStatus", r.getExtractStatus());
        view.put("sourceUrl", r.getSourceUrl());
        view.put("createdAt", r.getCreatedAt());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("diagnoses", read(r.getDiagnosesJson()));
        data.put("surgeries", read(r.getSurgeryJson()));
        data.put("medications", read(r.getMedicationsJson()));
        data.put("tubes", read(r.getTubesJson()));
        data.put("wound", read(r.getWoundJson()));
        data.put("diet", read(r.getDietJson()));
        data.put("activityLimit", read(r.getActivityJson()));
        data.put("followup", read(r.getFollowupJson()));
        data.put("redFlags", read(r.getRedFlagsJson()));
        data.put("confidence", read(r.getExtractConfidence()));
        view.put("data", data);
        return view;
    }

    private Object read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = om.readTree(json);
            return node;
        } catch (Exception e) {
            return json;
        }
    }
}
