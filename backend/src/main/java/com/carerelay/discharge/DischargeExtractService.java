package com.carerelay.discharge;

import com.carerelay.ai.AiCallService;
import com.carerelay.audit.AuditService;
import com.carerelay.common.BizException;
import com.carerelay.discharge.dto.ExtractRequest;
import com.carerelay.discharge.dto.ExtractResult;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.carerelay.elder.ElderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交接环第一步：出院小结 → 结构化档案。
 *
 * <p>这一段的工程重点不是"把提示词写好"，而是三件事：</p>
 * <ol>
 *   <li><b>失败要可见</b>：解析失败会重试一次；仍失败就降级成"人工填写"并明确告诉护士，绝不静默编造。</li>
 *   <li><b>缺失要成清单</b>：模型自报的 missing_fields 与代码复核出的缺失项合并，直接变成护士的待补列表。</li>
 *   <li><b>调用要留痕</b>：每次调用（含降级）都会写 ai_request，答辩时可按时间线回放。</li>
 * </ol>
 */
@Slf4j
@Service
public class DischargeExtractService {

    private static final String SCENE = "EXTRACT";
    private static final String PROMPT_PATH = "prompts/discharge_extract.v1.md";
    private static final int MIN_TEXT_LENGTH = 30;

    private final DischargeRecordMapper recordMapper;
    private final AiCallService ai;
    private final AuditService audit;
    private final ElderService elders;
    private final ObjectMapper om = new ObjectMapper();
    private final String systemPrompt;

    public DischargeExtractService(DischargeRecordMapper recordMapper, AiCallService ai, AuditService audit,
                                   ElderService elders) {
        this.recordMapper = recordMapper;
        this.ai = ai;
        this.audit = audit;
        this.elders = elders;
        this.systemPrompt = loadPrompt();
    }

    public ExtractResult extract(Long userId, ExtractRequest req) {
        if (req == null || req.text() == null || req.text().trim().length() < MIN_TEXT_LENGTH) {
            throw new BizException(400, "出院小结内容太短，至少 " + MIN_TEXT_LENGTH + " 字");
        }
        String text = req.text().trim();

        // 先确认老人档案存在：出院记录挂在不存在的人身上，后面评分、计划、随访全是孤儿数据
        elders.require(req.elderId());

        if (!ai.configured()) {
            return degrade(userId, req, text, "当前未配置模型 Key，已切到本地模式：请手工填写结构化档案");
        }

        String raw;
        try {
            raw = ai.chat(SCENE, userId, systemPrompt, text);
        } catch (RuntimeException e) {
            return degrade(userId, req, text, "模型调用失败，已切到本地模式：" + e.getMessage());
        }

        JsonNode data = parseStrictJson(raw);
        if (!valid(data)) {
            // 一次纠偏重试：把校验失败的原因原样回给模型，比让它"再想想"有效
            try {
                String retry = ai.chat(SCENE, userId, systemPrompt,
                        text + "\n\n[系统校验] 上一次输出不是合法 JSON 对象或缺少 medications/diagnoses 字段，"
                                + "请严格按输出结构只返回一个 JSON 对象，不要任何解释。");
                JsonNode second = parseStrictJson(retry);
                if (valid(second)) {
                    data = second;
                    raw = retry;
                }
            } catch (RuntimeException e) {
                log.warn("抽取重试失败: {}", e.getMessage());
            }
        }

        if (!valid(data)) {
            return degrade(userId, req, text, "模型输出无法解析为结构化结果，已切到本地模式：" + head(raw));
        }

        // 模型偶尔漏掉机构名与入出院日期（这几个字段原文里通常写得很规整），
        // 用规则从原文补一次：只填空缺，绝不覆盖模型已经给出的值。
        fillFromText(text, data);

        DischargeRecord record = toRecord(req, text, data);
        record.setCreatedBy(userId);
        record.setExtractStatus("PARSED");
        recordMapper.insert(record);

        return new ExtractResult(record.getId(), "PARSED", "MODEL", ai.model(),
                mergeMissing(data), data, "已生成结构化草稿，请逐项核对后签发");
    }

    /** 降级路径：留空档案 + 明确提示，护士可以手工补；调用同样落 ai_request。 */
    private ExtractResult degrade(Long userId, ExtractRequest req, String text, String notice) {
        ObjectNode empty = om.createObjectNode();
        ArrayNode missing = empty.putArray("missing_fields");
        for (String f : List.of("diagnosis_primary", "medications", "followup.date", "red_flags")) {
            missing.add(f);
        }
        DischargeRecord record = toRecord(req, text, empty);
        record.setCreatedBy(userId);
        record.setExtractStatus("MANUAL");
        recordMapper.insert(record);

        ai.logLocal(SCENE, userId, text, empty.toString(), notice);
        log.info("抽取降级为手工填写 recordId={}", record.getId());
        return new ExtractResult(record.getId(), "MANUAL", "LOCAL", "本地模板",
                new ArrayList<>(List.of("diagnosis_primary", "medications", "followup.date", "red_flags")),
                empty, notice);
    }

    // ------------------------------------------------------------------
    // 解析与校验
    // ------------------------------------------------------------------

    /** 去掉 ```json 围栏后按 JSON 对象解析；失败返回 null，由上层决定重试或降级。 */
    JsonNode parseStrictJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            s = s.substring(fence + 3);
            if (s.startsWith("json")) {
                s = s.substring(4);
            }
            int end = s.lastIndexOf("```");
            if (end >= 0) {
                s = s.substring(0, end);
            }
            s = s.trim();
        }
        int start = s.indexOf('{');
        int endBrace = s.lastIndexOf('}');
        if (start >= 0 && endBrace > start) {
            s = s.substring(start, endBrace + 1);
        }
        try {
            JsonNode node = om.readTree(s);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 最小校验：必须是对象，且至少带一个数组字段，避免模型返回一段散文也算成功。 */
    boolean valid(JsonNode data) {
        if (data == null || !data.isObject()) {
            return false;
        }
        return data.path("medications").isArray() || data.path("diagnoses").isArray();
    }

    /** 合并模型自报与代码复核的缺失项，去重保序。 */
    List<String> mergeMissing(JsonNode data) {
        Set<String> missing = new LinkedHashSet<>();
        JsonNode self = data.path("missing_fields");
        if (self.isArray()) {
            self.forEach(n -> missing.add(n.asText()));
        }
        for (String key : List.of("hospital", "department", "admit_date", "discharge_date")) {
            if (blank(data.path(key))) {
                missing.add(key);
            }
        }
        if (data.path("medications").isArray() && data.path("medications").isEmpty()) {
            missing.add("medications");
        }
        if (data.path("diagnosis_primary").isNull() || data.path("diagnosis_primary").asText("").isBlank()) {
            missing.add("diagnosis_primary");
        }
        JsonNode followup = data.path("followup");
        if (followup.isMissingNode() || followup.isNull() || followup.path("date").isNull()) {
            missing.add("followup.date");
        }
        if (data.path("red_flags").isArray() && data.path("red_flags").isEmpty()) {
            missing.add("red_flags");
        }
        return new ArrayList<>(missing);
    }

    // ------------------------------------------------------------------
    // 原文兜底：只在模型没给值时补，绝不覆盖模型结果
    // ------------------------------------------------------------------

    private static final Pattern HOSPITAL = Pattern.compile("([\\u4e00-\\u9fa5]{2,20}(?:医院|卫生院|医疗中心))");
    private static final Pattern DEPARTMENT = Pattern.compile("([\\u4e00-\\u9fa5]{2,10}(?:科|病区|中心))");
    private static final Pattern ADMIT = Pattern.compile("入院(?:日期|时间)?[:：\\s]*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?)");
    private static final Pattern DISCHARGE = Pattern.compile("出院(?:日期|时间)?[:：\\s]*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?)");
    private static final Pattern ANY_DATE = Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?");

    void fillFromText(String text, JsonNode data) {
        if (!(data instanceof ObjectNode obj) || text == null || text.isBlank()) {
            return;
        }
        if (blank(obj.path("hospital"))) {
            Matcher m = HOSPITAL.matcher(text);
            if (m.find()) {
                obj.put("hospital", m.group(1));
            }
        }
        if (blank(obj.path("department"))) {
            Matcher m = DEPARTMENT.matcher(text);
            if (m.find()) {
                obj.put("department", m.group(1));
            }
        }
        if (blank(obj.path("admit_date"))) {
            Matcher m = ADMIT.matcher(text);
            if (m.find()) {
                obj.put("admit_date", isoDate(m.group(1)));
            }
        }
        if (blank(obj.path("discharge_date"))) {
            Matcher m = DISCHARGE.matcher(text);
            if (m.find()) {
                obj.put("discharge_date", isoDate(m.group(1)));
            }
        }
    }

    private static boolean blank(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText("").isBlank();
    }

    /** "2026年10月24日" / "2026/10/24" / "2026-10-24" → 2026-10-24 */
    private static String isoDate(String raw) {
        Matcher m = ANY_DATE.matcher(raw);
        if (!m.find()) {
            return raw;
        }
        return String.format("%s-%02d-%02d", m.group(1),
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }


    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    private DischargeRecord toRecord(ExtractRequest req, String text, JsonNode data) {
        DischargeRecord r = new DischargeRecord();
        r.setElderId(req.elderId());
        r.setSourceText(text);
        r.setSourceUrl(req.sourceUrl() == null ? "" : req.sourceUrl());
        applyData(r, data);
        return r;
    }

    /**
     * 人工填写/修订结构化档案：本地模式下没有模型，护士必须能自己把关键字段补齐，
     * 否则「手工填写」就只是一句提示，风险评分和计划生成全都空转。
     */
    public ExtractResult saveManual(Long userId, Long recordId, JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new BizException(400, "结构化档案格式不正确，应为 JSON 对象");
        }
        DischargeRecord existing = recordMapper.selectById(recordId);
        if (existing == null) {
            throw new BizException(404, "出院记录不存在");
        }
        // 护士只改了几项时，没提交的字段必须保持原样：
        // 整份覆盖会把模型抽出来的药单、管路、伤口悄悄清空，比不提供人工填写更糟。
        ObjectNode merged = rebuildData(existing);
        data.fields().forEachRemaining(e -> {
            if (!"missing_fields".equals(e.getKey()) && !"confidence".equals(e.getKey())) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        // 人工改过的档案不再有"模型置信度"这回事，留着会让护士看到过期的低置信度提示
        merged.remove("confidence");

        DischargeRecord r = new DischargeRecord();
        r.setId(existing.getId());
        applyData(r, merged);
        // 置成空对象而不是 null：updateById 会跳过 null 字段，旧置信度就删不掉了
        r.setExtractConfidence("{}");
        r.setExtractStatus("REVIEWED");
        recordMapper.updateById(r);

        audit.record("DISCHARGE_MANUAL_EDIT", "DISCHARGE_RECORD", recordId,
                "护士手工填写/修订结构化档案");
        log.info("人工修订结构化档案 recordId={} userId={}", recordId, userId);
        return new ExtractResult(recordId, "REVIEWED", "MANUAL", "人工填写",
                mergeMissing(merged), merged, "已保存人工填写的结构化档案");
    }

    /** 把一条出院记录重新拼成结构化 JSON（与列表/详情接口吐出的形状保持一致）。 */
    private ObjectNode rebuildData(DischargeRecord r) {
        ObjectNode o = om.createObjectNode();
        if (r.getHospital() != null) o.put("hospital", r.getHospital());
        if (r.getDepartment() != null) o.put("department", r.getDepartment());
        if (r.getAdmitDate() != null) o.put("admit_date", r.getAdmitDate().toString());
        if (r.getDischargeDate() != null) o.put("discharge_date", r.getDischargeDate().toString());
        if (r.getDiagnosisPrimary() != null) o.put("diagnosis_primary", r.getDiagnosisPrimary());
        o.set("diagnoses", jnode(r.getDiagnosesJson()));
        o.set("surgeries", jnode(r.getSurgeryJson()));
        o.set("medications", jnode(r.getMedicationsJson()));
        o.set("tubes", jnode(r.getTubesJson()));
        o.set("wound", jnode(r.getWoundJson()));
        o.set("diet", jnode(r.getDietJson()));
        o.set("activity_limit", jnode(r.getActivityJson()));
        o.set("followup", jnode(r.getFollowupJson()));
        o.set("red_flags", jnode(r.getRedFlagsJson()));
        o.set("confidence", jnode(r.getExtractConfidence()));
        return o;
    }

    private JsonNode jnode(String json) {
        if (json == null || json.isBlank()) {
            return om.createArrayNode();
        }
        try {
            JsonNode node = om.readTree(json);
            return node == null ? om.createArrayNode() : node;
        } catch (IOException e) {
            return om.createArrayNode();
        }
    }

    /** 把结构化数据写回实体；抽取与人工填写共用这一份映射，避免两处字段名漂移。 */
    private void applyData(DischargeRecord r, JsonNode data) {
        r.setHospital(textOf(data, "hospital"));
        r.setDepartment(textOf(data, "department"));
        r.setAdmitDate(dateOf(data, "admit_date"));
        r.setDischargeDate(dateOf(data, "discharge_date"));
        r.setDiagnosisPrimary(textOf(data, "diagnosis_primary"));
        r.setDiagnosesJson(jsonOf(data, "diagnoses"));
        r.setSurgeryJson(jsonOf(data, "surgeries"));
        r.setMedicationsJson(jsonOf(data, "medications"));
        r.setTubesJson(jsonOf(data, "tubes"));
        r.setWoundJson(jsonOf(data, "wound"));
        r.setDietJson(jsonOf(data, "diet"));
        r.setActivityJson(jsonOf(data, "activity_limit"));
        r.setFollowupJson(jsonOf(data, "followup"));
        r.setRedFlagsJson(jsonOf(data, "red_flags"));
        r.setExtractConfidence(jsonOf(data, "confidence"));
    }

    private String textOf(JsonNode data, String field) {
        JsonNode n = data.path(field);
        return n.isMissingNode() || n.isNull() ? "" : n.asText();
    }

    private String jsonOf(JsonNode data, String field) {
        JsonNode n = data.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        return n.toString();
    }

    private LocalDate dateOf(JsonNode data, String field) {
        JsonNode n = data.path(field);
        if (!n.isTextual() || n.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(n.asText().trim());
        } catch (RuntimeException e) {
            log.debug("日期字段无法解析 {}={}", field, n.asText());
            return null;
        }
    }

    private String head(String raw) {
        if (raw == null) {
            return "空返回";
        }
        return raw.length() <= 80 ? raw : raw.substring(0, 80) + "…";
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_PATH);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词文件缺失：" + PROMPT_PATH, e);
        }
    }
}
