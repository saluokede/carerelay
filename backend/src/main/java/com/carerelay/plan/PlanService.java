package com.carerelay.plan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.ai.AiCallService;
import com.carerelay.audit.AuditService;
import com.carerelay.auth.CurrentUser;
import com.carerelay.auth.RoleRules;
import com.carerelay.auth.UserContext;
import com.carerelay.common.BizException;
import com.carerelay.common.JsonText;
import com.carerelay.discharge.DischargeRecord;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.ElderService;
import com.carerelay.plan.mapper.CarePlanMapper;
import com.carerelay.plan.mapper.CareTaskMapper;
import com.carerelay.plan.mapper.PlanCommentMapper;
import com.carerelay.risk.RiskAssessment;
import com.carerelay.risk.RiskScoringService;
import com.carerelay.user.AppUser;
import com.carerelay.user.mapper.AppUserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交接环第二步：风险分层 → 30 天计划草案 → 护士签发。
 *
 * <p>三个刻意的设计：</p>
 * <ol>
 *   <li><b>评分与草案同一次生成</b>：护士看到的计划一定对应某个分数，不会出现"分数是老的分层、计划是新的"。</li>
 *   <li><b>任务由代码展开</b>：模型只写目标与总述，具体到"哪天吃什么药"是代码按结构化用药逐条展开的，
 *       模型无法在任务列表里偷偷加一条不存在的药。</li>
 *   <li><b>签发是人的动作</b>：DRAFT 不可用于执行，ISSUED 才下发；签发写 audit_log。</li>
 * </ol>
 */
@Slf4j
@Service
public class PlanService {

    private static final String SCENE = "PLAN_GENERATE";
    private static final String PROMPT_PATH = "prompts/plan_generate.v1.md";
    /** 演示期先展开 7 天任务；30 天全展开会有上百行，演示反而看不清 */
    private static final int TASK_DAYS = 7;
    private static final int PLAN_DAYS = 30;

    private final CarePlanMapper planMapper;
    private final CareTaskMapper taskMapper;
    private final DischargeRecordMapper recordMapper;
    private final ElderService elderService;
    private final RiskScoringService riskService;
    private final AiCallService ai;
    private final AuditService audit;
    private final PlanCommentMapper commentMapper;
    private final AppUserMapper userMapper;
    private final ObjectMapper om = new ObjectMapper();
    private final String systemPrompt;

    public PlanService(CarePlanMapper planMapper,
                       CareTaskMapper taskMapper,
                       DischargeRecordMapper recordMapper,
                       ElderService elderService,
                       RiskScoringService riskService,
                       AiCallService ai,
                       AuditService audit,
                       PlanCommentMapper commentMapper,
                       AppUserMapper userMapper) {
        this.planMapper = planMapper;
        this.taskMapper = taskMapper;
        this.recordMapper = recordMapper;
        this.elderService = elderService;
        this.riskService = riskService;
        this.ai = ai;
        this.audit = audit;
        this.commentMapper = commentMapper;
        this.userMapper = userMapper;
        this.systemPrompt = loadPrompt();
    }

    @Transactional
    public Map<String, Object> generate(Long elderId, Long recordId) {
        ElderProfile elder = elderService.require(elderId);
        DischargeRecord record = loadRecord(elderId, recordId);
        RiskAssessment assessment = riskService.assess(elderId, recordId);

        JsonNode draft = draftPlan(elder, record, assessment);
        LocalDate today = LocalDate.now();

        CarePlan plan = new CarePlan();
        plan.setElderId(elderId);
        plan.setAssessmentId(assessment.getId());
        plan.setVersion(nextVersion(elderId));
        plan.setStatus("DRAFT");
        plan.setStartDate(today);
        plan.setEndDate(today.plusDays(PLAN_DAYS));
        plan.setGoalsJson(JsonText.of(draft.path("goals")));
        plan.setRedFlagsJson(JsonText.of(draft.path("red_flags")));
        plan.setFollowupJson(JsonText.of(draft.path("followup")));
        plan.setAiSummary(draft.path("summary").asText(""));
        planMapper.insert(plan);

        int taskCount = buildTasks(plan, record);
        audit.record("PLAN_GENERATE", "care_plan", plan.getId(),
                "生成草稿 v" + plan.getVersion() + "，任务 " + taskCount + " 条，风险 " + assessment.getLevel());

        Map<String, Object> view = toView(plan, assessment);
        view.put("taskCount", taskCount);
        return view;
    }

    public Map<String, Object> issue(Long planId) {
        // 角色分工：签发是护士的职责，医生只能看草案、提会诊意见
        RoleRules.requirePlanIssuer(com.carerelay.auth.UserContext.require());
        CarePlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(404, "计划不存在");
        }
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new BizException(409, "只有草稿可以签发，当前状态：" + plan.getStatus());
        }
        plan.setStatus("ISSUED");
        plan.setIssuedBy(com.carerelay.auth.UserContext.require().id());
        plan.setIssuedAt(java.time.LocalDateTime.now());
        planMapper.updateById(plan);

        audit.record("PLAN_ISSUE", "care_plan", plan.getId(),
                "护士签发 30 天照护计划 v" + plan.getVersion() + "（老人 " + plan.getElderId() + "）");

        RiskAssessment assessment = plan.getAssessmentId() == null ? null : riskService.latest(plan.getElderId());
        Map<String, Object> view = toView(plan, assessment);
        view.put("taskCount", taskMapper.selectCount(Wrappers.<CareTask>lambdaQuery()
                .eq(CareTask::getPlanId, plan.getId())));
        return view;
    }

    public Map<String, Object> detail(Long planId) {
        CarePlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(404, "计划不存在");
        }
        RiskAssessment assessment = plan.getAssessmentId() == null ? null : riskService.latest(plan.getElderId());
        Map<String, Object> view = toView(plan, assessment);
        view.put("taskCount", taskMapper.selectCount(Wrappers.<CareTask>lambdaQuery()
                .eq(CareTask::getPlanId, plan.getId())));
        return view;
    }

    public List<Map<String, Object>> listByElder(Long elderId) {
        return planMapper.selectList(Wrappers.<CarePlan>lambdaQuery()
                        .eq(CarePlan::getElderId, elderId)
                        .orderByDesc(CarePlan::getId))
                .stream().map(p -> toView(p, null)).toList();
    }

    public List<Map<String, Object>> tasks(Long planId) {
        return taskMapper.selectList(Wrappers.<CareTask>lambdaQuery()
                        .eq(CareTask::getPlanId, planId)
                        .orderByAsc(CareTask::getTaskDate)
                        .orderByAsc(CareTask::getId))
                .stream().map(this::taskView).toList();
    }

    // ------------------------------------------------------------------
    // 草案：模型优先，失败或未配置时用确定性模板
    // ------------------------------------------------------------------

    private JsonNode draftPlan(ElderProfile elder, DischargeRecord record, RiskAssessment assessment) {
        if (ai.configured()) {
            try {
                String raw = ai.chat(SCENE, null, systemPrompt, promptInput(elder, record, assessment));
                JsonNode node = parseJson(raw);
                if (node != null && node.path("goals").isArray() && !node.path("goals").isEmpty()) {
                    return normalize(node, assessment);
                }
                log.warn("计划草案 JSON 校验失败，改用模板");
            } catch (RuntimeException e) {
                log.warn("计划草案生成失败，改用模板：{}", e.getMessage());
            }
        }
        return fallbackDraft(elder, record, assessment);
    }

    /** 模型漏字段时补齐，避免前端拿到半张计划。 */
    private JsonNode normalize(JsonNode node, RiskAssessment assessment) {
        ObjectNode plan = (ObjectNode) node;
        if (!plan.path("followup").isObject()) {
            plan.set("followup", fallbackFollowup(assessment.getLevel()));
        }
        if (!plan.path("red_flags").isArray() || plan.path("red_flags").isEmpty()) {
            plan.set("red_flags", fallbackRedFlags());
        }
        if (plan.path("summary").asText("").isBlank()) {
            plan.put("summary", "按计划执行每日任务，护士会按风险分层安排随访；出现红线症状请立即联系社区或拨打 120。");
        }
        return plan;
    }

    private JsonNode fallbackDraft(ElderProfile elder, DischargeRecord record, RiskAssessment assessment) {
        ObjectNode plan = om.createObjectNode();
        ArrayNode goals = plan.putArray("goals");

        List<String> meds = JsonText.strings(record == null ? null : record.getMedicationsJson(), "name");
        if (!meds.isEmpty()) {
            goals.add(goal("按时按量服药，不漏服不自行停药", "每日用药打卡到位率", "HIGH"));
        }
        goals.add(goal("每日测量并记录血压", "连续记录晨起血压", "MEDIUM"));
        if (record != null && !JsonText.strings(record.getActivityJson(), null).isEmpty()) {
            goals.add(goal("按出院要求完成康复活动", "每日步行与肢体活动次数", "MEDIUM"));
        }
        if (record != null && hasWound(record)) {
            goals.add(goal("观察伤口并按医嘱换药", "伤口有无红肿渗液", "HIGH"));
        }
        goals.add(goal("按时复诊，不带漏项", "复诊日期与携带材料", "HIGH"));

        plan.set("followup", fallbackFollowup(assessment.getLevel()));
        plan.set("red_flags", fallbackRedFlags());

        String medText = meds.isEmpty() ? "本次出院未列带药" : "共 " + meds.size() + " 种出院带药";
        plan.put("summary", elder.getName() + "本次出院为" + levelLabel(assessment.getLevel())
                + "（评分 " + assessment.getScore() + "）。这 " + PLAN_DAYS + " 天的重点是：" + medText
                + "、每日血压记录、按能力做康复活动。" + followupSentence(assessment.getLevel())
                + "出现胸痛、意识改变、跌倒后疼痛或原有症状明显加重时，立即联系社区或拨打 120。");
        return plan;
    }

    private ObjectNode goal(String title, String metric, String priority) {
        ObjectNode g = om.createObjectNode();
        g.put("title", title);
        g.put("metric", metric);
        g.put("priority", priority);
        return g;
    }

    private ObjectNode fallbackFollowup(String level) {
        ObjectNode f = om.createObjectNode();
        int days = switch (level) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 7;
            default -> 14;
        };
        f.put("interval_days", days);
        f.put("channel", "PHONE");
        f.put("reason", "按" + levelLabel(level) + "分层，每 " + days + " 天电话随访一次，必要时上门。");
        return f;
    }

    private ArrayNode fallbackRedFlags() {
        ArrayNode arr = om.createArrayNode();
        arr.add("胸痛、胸闷持续不缓解，或伴大汗");
        arr.add("意识改变、言语不清、口角歪斜、肢体无力加重");
        arr.add("跌倒后出现疼痛、无法站立或呕吐");
        arr.add("高热不退、呼吸困难或血氧下降");
        return arr;
    }

    private String followupSentence(String level) {
        return switch (level) {
            case "HIGH" -> "护士每 3 天会电话随访一次，必要时上门。";
            case "MEDIUM" -> "护士每 7 天会电话随访一次。";
            default -> "护士每 2 周会电话随访一次。";
        };
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "HIGH" -> "高风险";
            case "MEDIUM" -> "中风险";
            default -> "低风险";
        };
    }

    private String promptInput(ElderProfile elder, DischargeRecord record, RiskAssessment assessment) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("老人", JsonText.age(elder.getBirthDate()) + " 岁，" + elder.getLivingStatus());
        input.put("风险", Map.of("score", assessment.getScore(), "level", assessment.getLevel(),
                "factors", JsonText.parse(assessment.getFactorsJson())));
        if (record != null) {
            Map<String, Object> discharge = new LinkedHashMap<>();
            discharge.put("diagnoses", JsonText.parse(record.getDiagnosesJson()));
            discharge.put("medications", JsonText.parse(record.getMedicationsJson()));
            discharge.put("tubes", JsonText.parse(record.getTubesJson()));
            discharge.put("wound", JsonText.parse(record.getWoundJson()));
            discharge.put("diet", JsonText.parse(record.getDietJson()));
            discharge.put("activity_limit", JsonText.parse(record.getActivityJson()));
            discharge.put("followup", JsonText.parse(record.getFollowupJson()));
            discharge.put("red_flags", JsonText.parse(record.getRedFlagsJson()));
            input.put("出院信息", discharge);
        }
        try {
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(input);
        } catch (Exception e) {
            return input.toString();
        }
    }

    // ------------------------------------------------------------------
    // 任务展开：完全由代码按结构化信息生成
    // ------------------------------------------------------------------

    private int buildTasks(CarePlan plan, DischargeRecord record) {
        int created = 0;
        LocalDate start = plan.getStartDate();
        JsonNode medications = record == null ? null : JsonText.parse(record.getMedicationsJson());
        List<String> activity = record == null ? List.of() : JsonText.strings(record.getActivityJson(), null);

        for (int d = 0; d < TASK_DAYS; d++) {
            LocalDate date = start.plusDays(d);

            if (medications != null && medications.isArray()) {
                for (JsonNode med : medications) {
                    String name = med.path("name").asText("");
                    if (name.isBlank()) {
                        continue;
                    }
                    String dose = med.path("dose").asText("");
                    String freq = med.path("frequency").asText("");
                    boolean evening = freq.contains("qn") || freq.contains("晚");
                    created += insertTask(plan, date, "MED", "服药：" + name,
                            join("剂量", dose, "频次", freq), evening ? "EVENING" : "MORNING", "PHOTO");
                }
            }

            created += insertTask(plan, date, "VITAL", "测量血压", "静坐 5 分钟后测量并记录收缩压/舒张压",
                    "MORNING", "TEXT");

            // 原文很简（典型：一句"感冒发烧，回家观察"）时抽不到药单，
            // 这里补两条通用观察任务，让家属当天有明确的事可做，而不是拿到一张空计划。
            if (medications == null || !medications.isArray() || medications.isEmpty()) {
                created += insertTask(plan, date, "VITAL", "测量体温", "早晚各一次，记下度数；超过 38.5℃ 或持续三天不降，联系护士",
                        "EVENING", "TEXT");
                created += insertTask(plan, date, "SYMPTOM", "记一句今天的感觉", "精神、胃口、睡眠、有没有哪里疼——一句话就行",
                        "ANYTIME", "TEXT");
            }

            if (!activity.isEmpty()) {
                created += insertTask(plan, date, "REHAB", "康复活动",
                        String.join("；", activity), "EVENING", "TEXT");
            }

            if (record != null && hasWound(record)) {
                created += insertTask(plan, date, "WOUND", "伤口观察与换药",
                        "拍照记录伤口颜色、渗液与敷料情况", "ANYTIME", "PHOTO");
            }
        }
        return created;
    }

    private int insertTask(CarePlan plan, LocalDate date, String type, String title, String detail,
                           String timeSlot, String evidence) {
        CareTask task = new CareTask();
        task.setPlanId(plan.getId());
        task.setElderId(plan.getElderId());
        task.setTaskDate(date);
        task.setType(type);
        task.setTitle(title);
        task.setDetail(detail == null ? "" : detail);
        task.setTimeSlot(timeSlot);
        task.setEvidence(evidence);
        task.setStatus("PENDING");
        task.setSource("AI");
        taskMapper.insert(task);
        return 1;
    }

    private String join(String k1, String v1, String k2, String v2) {
        List<String> parts = new ArrayList<>();
        if (!v1.isBlank()) {
            parts.add(k1 + " " + v1);
        }
        if (!v2.isBlank()) {
            parts.add(k2 + " " + v2);
        }
        return parts.isEmpty() ? "按原处方执行" : String.join("，", parts);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> toView(CarePlan plan, RiskAssessment assessment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", plan.getId());
        view.put("elderId", plan.getElderId());
        view.put("assessmentId", plan.getAssessmentId());
        view.put("version", plan.getVersion());
        view.put("status", plan.getStatus());
        view.put("startDate", plan.getStartDate());
        view.put("endDate", plan.getEndDate());
        view.put("goals", JsonText.parse(plan.getGoalsJson()));
        view.put("redFlags", JsonText.parse(plan.getRedFlagsJson()));
        view.put("followup", JsonText.parse(plan.getFollowupJson()));
        view.put("aiSummary", plan.getAiSummary());
        view.put("issuedBy", plan.getIssuedBy());
        view.put("issuedAt", plan.getIssuedAt());
        if (assessment != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("score", assessment.getScore());
            a.put("level", assessment.getLevel());
            a.put("levelLabel", levelLabel(assessment.getLevel()));
            a.put("explanation", assessment.getExplanation());
            view.put("assessment", a);
        }
        return view;
    }

    private Map<String, Object> taskView(CareTask t) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", t.getId());
        view.put("taskDate", t.getTaskDate());
        view.put("type", t.getType());
        view.put("title", t.getTitle());
        view.put("detail", t.getDetail());
        view.put("timeSlot", t.getTimeSlot());
        view.put("evidence", t.getEvidence());
        view.put("status", t.getStatus());
        view.put("source", t.getSource());
        return view;
    }

    private DischargeRecord loadRecord(Long elderId, Long recordId) {
        if (recordId != null) {
            return recordMapper.selectById(recordId);
        }
        return recordMapper.selectOne(Wrappers.<DischargeRecord>lambdaQuery()
                .eq(DischargeRecord::getElderId, elderId)
                .orderByDesc(DischargeRecord::getId)
                .last("limit 1"));
    }

    private int nextVersion(Long elderId) {
        Long count = planMapper.selectCount(Wrappers.<CarePlan>lambdaQuery().eq(CarePlan::getElderId, elderId));
        return count == null ? 1 : (int) (count + 1);
    }

    private boolean hasWound(DischargeRecord record) {
        JsonNode wound = JsonText.parse(record.getWoundJson());
        return wound != null && wound.path("present").asBoolean(false);
    }

    private JsonNode parseJson(String raw) {
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

    private String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词文件缺失：" + PROMPT_PATH, e);
        }
    }

    // ------------------------------------------------------------------
    // 会诊留言：医生不能签发，但必须能表达意见
    // ------------------------------------------------------------------

    public List<Map<String, Object>> comments(Long planId) {
        requirePlan(planId);
        return commentMapper.selectList(Wrappers.<PlanComment>lambdaQuery()
                        .eq(PlanComment::getPlanId, planId)
                        .orderByAsc(PlanComment::getId))
                .stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("planId", c.getPlanId());
                    m.put("authorRole", c.getAuthorRole());
                    m.put("authorName", c.getAuthorName());
                    m.put("content", c.getContent());
                    m.put("createdAt", c.getCreatedAt());
                    return m;
                })
                .toList();
    }

    @Transactional
    public Map<String, Object> addComment(Long planId, String content) {
        CarePlan plan = requirePlan(planId);
        if (content == null || content.isBlank()) {
            throw new BizException(400, "留言不能为空");
        }
        String text = content.trim();
        if (text.length() > 600) {
            throw new BizException(400, "留言太长了，请控制在 600 字以内");
        }
        CurrentUser current = UserContext.require();
        AppUser author = userMapper.selectById(current.id());
        PlanComment c = new PlanComment();
        c.setPlanId(planId);
        c.setElderId(plan.getElderId());
        c.setAuthorId(current.id());
        c.setAuthorRole(current.role());
        c.setAuthorName(author == null ? "未知账号" : author.getNickname());
        c.setContent(text);
        commentMapper.insert(c);
        audit.record("PLAN_COMMENT", "CARE_PLAN", planId, truncate(text, 120));
        // 重新读一次，拿到数据库生成的 created_at（否则前端时间列是空的）
        PlanComment saved = commentMapper.selectById(c.getId());
        if (saved != null) {
            c = saved;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("planId", planId);
        m.put("authorRole", c.getAuthorRole());
        m.put("authorName", c.getAuthorName());
        m.put("content", c.getContent());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    private CarePlan requirePlan(Long planId) {
        CarePlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(404, "计划不存在");
        }
        return plan;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
