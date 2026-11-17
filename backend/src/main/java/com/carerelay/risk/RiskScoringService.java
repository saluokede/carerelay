package com.carerelay.risk;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.ai.AiCallService;
import com.carerelay.common.BizException;
import com.carerelay.common.JsonText;
import com.carerelay.discharge.DischargeRecord;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.mapper.ElderProfileMapper;
import com.carerelay.risk.mapper.RiskAssessmentMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险评分卡：**纯代码算分**。
 *
 * <p>这是本作品"数值归代码、语言归模型"的第一处落点：评分项、权重、分层阈值都是常量，
 * 可复算、可单测；模型只负责把已经算好的结论解释成人话（explanation），
 * 解释得再好也无法改变 score。</p>
 */
@Slf4j
@Service
public class RiskScoringService {

    private static final int HIGH_THRESHOLD = 6;
    private static final int MEDIUM_THRESHOLD = 3;
    private static final String SCENE = "RISK_EXPLAIN";
    private static final String PROMPT_PATH = "prompts/risk_explain.v1.md";

    private final RiskAssessmentMapper mapper;
    private final DischargeRecordMapper recordMapper;
    private final ElderProfileMapper elderMapper;
    private final AiCallService ai;
    private final ObjectMapper om = new ObjectMapper();
    private final String systemPrompt;

    public RiskScoringService(RiskAssessmentMapper mapper,
                              DischargeRecordMapper recordMapper,
                              ElderProfileMapper elderMapper,
                              AiCallService ai) {
        this.mapper = mapper;
        this.recordMapper = recordMapper;
        this.elderMapper = elderMapper;
        this.ai = ai;
        this.systemPrompt = loadPrompt();
    }

    /** 单个评分项。score 由规则决定，evidence 是可以给护士核对的原文依据。 */
    public record Factor(String code, String label, int score, String evidence) {
    }

    public RiskAssessment assess(Long elderId, Long recordId) {
        ElderProfile elder = elderMapper.selectById(elderId);
        if (elder == null) {
            throw new BizException(404, "老人档案不存在");
        }
        DischargeRecord record = loadRecord(elderId, recordId);

        List<Factor> factors = computeFactors(elder, record);
        int score = factors.stream().mapToInt(Factor::score).sum();
        String level = level(score);

        RiskAssessment assessment = new RiskAssessment();
        assessment.setElderId(elderId);
        assessment.setRecordId(record == null ? null : record.getId());
        assessment.setScore(score);
        assessment.setLevel(level);
        assessment.setFactorsJson(om.valueToTree(toMaps(factors)).toString());
        assessment.setAssessor("RULE");
        assessment.setExplanation(explain(elder, factors, score, level));
        mapper.insert(assessment);
        return assessment;
    }

    public List<Factor> computeFactors(ElderProfile elder, DischargeRecord record) {
        List<Factor> factors = new ArrayList<>();
        int age = JsonText.age(elder.getBirthDate());

        if (age >= 85) {
            factors.add(new Factor("AGE_85", "年龄 ≥ 85 岁", 2, "年龄 " + age + " 岁"));
        } else if (age >= 75) {
            factors.add(new Factor("AGE_75", "年龄 75–84 岁", 1, "年龄 " + age + " 岁"));
        }

        if ("ALONE".equals(elder.getLivingStatus())) {
            factors.add(new Factor("LIVING_ALONE", "独居", 2, "居住状态：独居"));
        }

        if (elder.getCaregiverName() == null || elder.getCaregiverName().isBlank()) {
            factors.add(new Factor("NO_CAREGIVER", "无固定照护人", 1, "未登记照护人"));
        }

        if (record != null) {
            int meds = size(record.getMedicationsJson());
            if (meds >= 6) {
                factors.add(new Factor("POLYPHARMACY_6", "多重用药 ≥ 6 种", 2, "出院带药 " + meds + " 种"));
            } else if (meds >= 4) {
                factors.add(new Factor("POLYPHARMACY_4", "多重用药 4–5 种", 1, "出院带药 " + meds + " 种"));
            }

            if (size(record.getTubesJson()) > 0) {
                factors.add(new Factor("TUBE", "带管路出院", 2, "管路：" + String.join("、", tubeNames(record.getTubesJson()))));
            }

            JsonNode wound = JsonText.parse(record.getWoundJson());
            if (wound != null && wound.path("present").asBoolean(false)) {
                factors.add(new Factor("WOUND", "存在伤口/敷料", 1, "伤口：" + wound.path("site").asText("未写明部位")));
            }

            String diagnosisText = String.join("、", JsonText.strings(record.getDiagnosesJson(), null))
                    + " " + (record.getDiagnosisPrimary() == null ? "" : record.getDiagnosisPrimary());
            if (diagnosisText.contains("痴呆") || diagnosisText.contains("认知") || diagnosisText.contains("阿尔茨")) {
                factors.add(new Factor("COGNITION", "认知功能障碍", 2, "诊断含认知/痴呆相关"));
            }
        }

        // 叠加项：独居本身是 2 分，但"独居 + 需要居家换药/带管"是照护缺口，风险再加一档。
        // 这一条是刻意设的：评分卡要能区分"有人照顾的伤口"和"没人照顾的伤口"。
        if ("ALONE".equals(elder.getLivingStatus()) && record != null
                && (size(record.getTubesJson()) > 0 || hasWound(record))) {
            factors.add(new Factor("HOME_GAP", "独居且有伤口或管路", 2, "独居 + 需居家换药或管路护理"));
        }
        return factors;
    }

    public String level(int score) {
        if (score >= HIGH_THRESHOLD) {
            return "HIGH";
        }
        return score >= MEDIUM_THRESHOLD ? "MEDIUM" : "LOW";
    }

    public Map<String, Object> toView(RiskAssessment a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", a.getId());
        view.put("elderId", a.getElderId());
        view.put("recordId", a.getRecordId());
        view.put("score", a.getScore());
        view.put("level", a.getLevel());
        view.put("levelLabel", switch (a.getLevel()) {
            case "HIGH" -> "高风险";
            case "MEDIUM" -> "中风险";
            default -> "低风险";
        });
        view.put("factors", JsonText.parse(a.getFactorsJson()));
        view.put("explanation", a.getExplanation());
        view.put("assessor", a.getAssessor());
        view.put("createdAt", a.getCreatedAt());
        return view;
    }

    public RiskAssessment latest(Long elderId) {
        return mapper.selectOne(Wrappers.<RiskAssessment>lambdaQuery()
                .eq(RiskAssessment::getElderId, elderId)
                .orderByDesc(RiskAssessment::getId)
                .last("limit 1"));
    }

    // ------------------------------------------------------------------

    private DischargeRecord loadRecord(Long elderId, Long recordId) {
        if (recordId != null) {
            return recordMapper.selectById(recordId);
        }
        return recordMapper.selectOne(Wrappers.<DischargeRecord>lambdaQuery()
                .eq(DischargeRecord::getElderId, elderId)
                .orderByDesc(DischargeRecord::getId)
                .last("limit 1"));
    }

    /** 解释文本：模型能写就用模型，写不了就用确定性模板，绝不返回空。 */
    private String explain(ElderProfile elder, List<Factor> factors, int score, String level) {
        String facts = factors.stream()
                .map(f -> "- " + f.label() + "（+ " + f.score() + "）：" + f.evidence())
                .reduce("", (a, b) -> a + b + "\n");
        if (ai.configured()) {
            try {
                return ai.chat(SCENE, null, systemPrompt,
                        "老人：" + elder.getName() + "；总分 " + score + "；分层 " + level + "。\n命中项：\n" + facts);
            } catch (RuntimeException e) {
                log.warn("风险解释生成失败，改用模板：{}", e.getMessage());
            }
        }
        return fallbackExplanation(elder, factors, score, level);
    }

    private String fallbackExplanation(ElderProfile elder, List<Factor> factors, int score, String level) {
        if (factors.isEmpty()) {
            return elder.getName() + "本次未命中任何风险项，按常规频次随访即可；如出现新的不适请随时联系社区。";
        }
        String labels = factors.stream().map(Factor::label).reduce((a, b) -> a + "、" + b).orElse("");
        return elder.getName() + "本次风险评分 " + score + " 分（" + level + "），主要因为存在：" + labels
                + "。建议提高随访频次、重点核对用药与居家安全，并把红线症状提前讲给家属。";
    }

    private List<Map<String, Object>> toMaps(List<Factor> factors) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Factor f : factors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("label", f.label());
            m.put("score", f.score());
            m.put("evidence", f.evidence());
            out.add(m);
        }
        return out;
    }

    private int size(String json) {
        JsonNode node = JsonText.parse(json);
        return node != null && node.isArray() ? node.size() : 0;
    }

    private boolean hasWound(DischargeRecord record) {
        JsonNode wound = JsonText.parse(record.getWoundJson());
        return wound != null && wound.path("present").asBoolean(false);
    }

    private List<String> tubeNames(String json) {
        List<String> names = JsonText.strings(json, "type");
        return names.isEmpty() ? List.of("管路") : names;
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词文件缺失：" + PROMPT_PATH, e);
        }
    }
}
