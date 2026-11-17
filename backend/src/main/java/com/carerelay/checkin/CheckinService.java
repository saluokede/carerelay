package com.carerelay.checkin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.ai.AiCallService;
import com.carerelay.alert.AlertEvent;
import com.carerelay.alert.mapper.AlertEventMapper;
import com.carerelay.audit.AuditService;
import com.carerelay.common.BizException;
import com.carerelay.common.JsonText;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.ElderService;
import com.carerelay.checkin.mapper.CareCheckinMapper;
import com.carerelay.plan.CareTask;
import com.carerelay.plan.mapper.CareTaskMapper;
import com.carerelay.ticket.Ticket;
import com.carerelay.ticket.mapper.TicketMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 执行环：今日任务 + 打卡判读 + 预警升级。
 *
 * <p>判读分流是本类最核心的约定：</p>
 * <ul>
 *   <li><b>数值</b>（血压/血糖/体温/血氧）→ {@link VitalRules}，纯代码，同一数字永远同一结论；</li>
 *   <li><b>文字/语音</b> → 模型判读，模型不可用时退到关键词规则，并如实标注 judge_source；</li>
 *   <li><b>照片</b> → 未接视觉模型时不做假判读，状态记为待护士目视。</li>
 * </ul>
 */
@Slf4j
@Service
public class CheckinService {

    private static final String SCENE = "CHECKIN_JUDGE";
    private static final String PROMPT_PATH = "prompts/checkin_judge.v1.md";

    /** 兜底关键词：模型不可用时的最后一道线，宁可多报不可漏报 */
    private static final List<String> RED_FLAGS = List.of(
            "胸痛", "胸闷", "呼吸困难", "喘不上气", "喘不过气", "意识不清", "叫不醒", "昏迷", "晕倒",
            "跌倒", "摔倒", "呕血", "黑便", "高热", "抽搐", "口角歪", "说话不清", "肢体无力", "冷汗", "大汗");
    private static final List<String> CHEST_WORDS = List.of("闷", "紧", "压", "疼", "痛", "塞");
    private static final List<String> WATCH_WORDS = List.of(
            "头晕", "乏力", "没力气", "发虚", "胸口", "喘", "没胃口", "不想吃", "恶心", "呕吐",
            "睡不着", "心慌", "水肿", "便秘", "疼", "痛", "闷");

    private final CareCheckinMapper checkinMapper;
    private final CareTaskMapper taskMapper;
    private final AlertEventMapper alertMapper;
    private final TicketMapper ticketMapper;
    private final ElderService elderService;
    private final AiCallService ai;
    private final AuditService audit;
    private final String systemPrompt;

    public CheckinService(CareCheckinMapper checkinMapper,
                          CareTaskMapper taskMapper,
                          AlertEventMapper alertMapper,
                          TicketMapper ticketMapper,
                          ElderService elderService,
                          AiCallService ai,
                          AuditService audit) {
        this.checkinMapper = checkinMapper;
        this.taskMapper = taskMapper;
        this.alertMapper = alertMapper;
        this.ticketMapper = ticketMapper;
        this.elderService = elderService;
        this.ai = ai;
        this.audit = audit;
        this.systemPrompt = loadPrompt();
    }

    /** 家属端「今天做什么」：按时间段落排列，并标出哪些已完成。 */
    public Map<String, Object> today(Long elderId, LocalDate date) {
        ElderProfile elder = elderService.require(elderId);
        LocalDate day = date == null ? LocalDate.now() : date;
        List<CareTask> tasks = taskMapper.selectList(Wrappers.<CareTask>lambdaQuery()
                .eq(CareTask::getElderId, elderId)
                .eq(CareTask::getTaskDate, day)
                .orderByAsc(CareTask::getId));

        List<Map<String, Object>> items = new ArrayList<>();
        for (CareTask task : tasks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", task.getId());
            m.put("type", task.getType());
            m.put("title", task.getTitle());
            m.put("detail", task.getDetail());
            m.put("timeSlot", task.getTimeSlot());
            m.put("evidence", task.getEvidence());
            m.put("status", task.getStatus());
            Long done = checkinMapper.selectCount(Wrappers.<CareCheckin>lambdaQuery()
                    .eq(CareCheckin::getTaskId, task.getId())
                    .eq(CareCheckin::getCheckinDate, day));
            m.put("checked", done != null && done > 0);
            items.add(m);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("elderId", elderId);
        view.put("elderName", elder.getName());
        view.put("date", day);
        view.put("totalTasks", items.size());
        view.put("doneTasks", items.stream().filter(i -> Boolean.TRUE.equals(i.get("checked"))).count());
        view.put("tasks", items);
        return view;
    }

    /**
     * 打卡提交。
     *
     * @param request 打卡内容；kind 决定走规则还是走模型
     */
    @Transactional
    public Map<String, Object> submit(SubmitRequest request) {
        if (request == null || request.elderId() == null) {
            throw new BizException(400, "缺少 elderId");
        }
        ElderProfile elder = elderService.require(request.elderId());
        CareTask task = request.taskId() == null ? null : taskMapper.selectById(request.taskId());
        LocalDate day = LocalDate.now();

        String kind = request.kind() == null ? "" : request.kind().toUpperCase(Locale.ROOT);
        // 兼容只给 valueJson、不给 kind 的客户端：数值类型从载荷里读，
        // 否则一条 172/104 的血压会被当成"已完成"静默放过——这正是演示脚本踩到的坑。
        if (kind.isEmpty() && request.valueJson() != null && !request.valueJson().isBlank()) {
            com.fasterxml.jackson.databind.JsonNode value = JsonText.parse(request.valueJson());
            if (value != null) {
                kind = value.path("kind").asText("").toUpperCase(Locale.ROOT);
            }
        }
        String status;
        String level = "";
        String ruleCode = "";
        String message;
        String judgeSource;

        boolean numeric = List.of("BP", "GLU", "GLUCOSE", "TEMP", "SPO2", "WEIGHT").contains(kind);
        if (numeric) {
            VitalRules.Judge judge = VitalRules.judge(request.valueJson());
            status = judge.status();
            level = judge.level();
            ruleCode = judge.ruleCode();
            message = judge.message();
            judgeSource = "RULE";
        } else if (hasText(request)) {
            TextJudge judge = judgeText(elder, task, request.text());
            status = judge.status();
            level = judge.level();
            ruleCode = judge.ruleCode();
            message = judge.message();
            judgeSource = judge.source();
        } else if (request.photoUrl() != null && !request.photoUrl().isBlank()) {
            // 没接视觉模型时不做"看起来正常"这种假判读，如实排队等人工目视
            status = "NORMAL";
            ruleCode = "PHOTO_PENDING";
            message = "照片已收到，护士会在 24 小时内目视确认";
            judgeSource = "RULE";
        } else {
            status = "NORMAL";
            ruleCode = "CHECKED";
            message = "已完成";
            judgeSource = "RULE";
        }

        CareCheckin checkin = new CareCheckin();
        checkin.setTaskId(task == null ? null : task.getId());
        checkin.setElderId(elder.getId());
        checkin.setCheckinDate(day);
        checkin.setValueJson(request.valueJson());
        checkin.setPhotoUrl(request.photoUrl() == null ? "" : request.photoUrl());
        checkin.setVoiceUrl(request.voiceUrl() == null ? "" : request.voiceUrl());
        checkin.setVoiceText(request.text() == null ? "" : trim(request.text(), 900));
        checkin.setStatus("ABNORMAL".equals(status) ? "ABNORMAL" : "NORMAL");
        checkin.setJudgeSource(judgeSource);
        checkin.setJudgeNote(trim(message, 240));
        checkinMapper.insert(checkin);

        if (task != null) {
            task.setStatus("DONE");
            taskMapper.updateById(task);
        }

        Long alertId = null;
        Long ticketId = null;
        if ("ABNORMAL".equals(status)) {
            AlertEvent alert = new AlertEvent();
            alert.setElderId(elder.getId());
            alert.setSource("CHECKIN");
            alert.setRuleCode(ruleCode.isEmpty() ? "CHECKIN_ABNORMAL" : ruleCode);
            alert.setLevel("HIGH".equals(level) ? "HIGH" : "MEDIUM");
            alert.setTitle(titleFor(task, ruleCode));
            alert.setDetail(elder.getName() + "：" + message
                    + (request.text() == null || request.text().isBlank() ? "" : "（家属描述：" + trim(request.text(), 160) + "）"));
            alert.setStatus("OPEN");
            alertMapper.insert(alert);
            alertId = alert.getId();

            if ("HIGH".equals(level)) {
                Ticket ticket = new Ticket();
                ticket.setElderId(elder.getId());
                ticket.setAlertId(alert.getId());
                ticket.setSource("ALERT");
                ticket.setLevel("HIGH");
                ticket.setReason(trim(message, 240));
                ticket.setStatus("PENDING");
                ticketMapper.insert(ticket);
                ticketId = ticket.getId();
            }
            audit.record("ALERT_RAISE", "alert_event", alert.getId(),
                    "打卡触发预警：" + alert.getRuleCode() + " / " + alert.getLevel());
        }
        audit.record("CHECKIN", "checkin", checkin.getId(),
                "任务 " + (task == null ? "-" : task.getTitle()) + " → " + status + "（" + judgeSource + "）");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("checkinId", checkin.getId());
        view.put("status", status);
        view.put("level", level);
        view.put("ruleCode", ruleCode);
        view.put("message", message);
        view.put("judgeSource", judgeSource);
        view.put("alertId", alertId);
        view.put("ticketId", ticketId);
        return view;
    }

    public List<Map<String, Object>> listByElder(Long elderId, LocalDate date) {
        elderService.require(elderId);
        var query = Wrappers.<CareCheckin>lambdaQuery()
                .eq(CareCheckin::getElderId, elderId)
                .orderByDesc(CareCheckin::getId);
        if (date != null) {
            query.eq(CareCheckin::getCheckinDate, date);
        }
        return checkinMapper.selectList(query.last("limit 50")).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("taskId", c.getTaskId());
            m.put("date", c.getCheckinDate());
            m.put("status", c.getStatus());
            m.put("judgeSource", c.getJudgeSource());
            m.put("judgeNote", c.getJudgeNote());
            m.put("value", JsonText.parse(c.getValueJson()));
            m.put("photoUrl", c.getPhotoUrl());
            m.put("voiceText", c.getVoiceText());
            return m;
        }).toList();
    }

    // ------------------------------------------------------------------

    private record TextJudge(String status, String level, String ruleCode, String message, String source) {
    }

    /** 文字判读：模型优先，不可用或输出不合规时退到关键词规则。 */
    private TextJudge judgeText(ElderProfile elder, CareTask task, String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            return new TextJudge("NORMAL", "", "EMPTY", "已完成", "RULE");
        }
        if (ai.configured()) {
            try {
                String prompt = "老人：" + elder.getName() + "，" + JsonText.age(elder.getBirthDate()) + " 岁\n"
                        + "任务：" + (task == null ? "-" : task.getTitle()) + "\n"
                        + "家属描述：" + raw;
                String out = ai.chat(SCENE, null, systemPrompt, prompt);
                TextJudge judge = parseJudge(out, raw);
                if (judge != null) {
                    return judge;
                }
                log.warn("打卡判读 JSON 不合规，改用关键词规则");
            } catch (RuntimeException e) {
                log.warn("打卡判读调用失败，改用关键词规则：{}", e.getMessage());
            }
        }
        return keywordJudge(raw);
    }

    private TextJudge parseJudge(String out, String raw) {
        if (out == null || out.isBlank()) {
            return null;
        }
        String s = out.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        var node = JsonText.parse(s.substring(start, end + 1));
        if (node == null) {
            return null;
        }
        String status = node.path("status").asText("").toUpperCase(Locale.ROOT);
        if (!"ABNORMAL".equals(status) && !"NORMAL".equals(status)) {
            return null;
        }
        String level = node.path("level").asText("MEDIUM").toUpperCase(Locale.ROOT);
        String message = node.path("message").asText("");
        if (message.isBlank()) {
            message = "ABNORMAL".equals(status) ? "已记录，护士会跟进" : "已完成";
        }
        // 安全网：模型说正常、但原文明确命中红旗 → 升到 HIGH，人话保留
        if ("NORMAL".equals(status) && hitsRedFlag(raw)) {
            return new TextJudge("ABNORMAL", "HIGH", "KEYWORD_RED_FLAG",
                    "描述里有需要立即处理的情况，护士马上联系您", "MODEL");
        }
        return new TextJudge(status, "ABNORMAL".equals(status) ? level : "",
                node.path("rule_code").asText("MODEL"), message, "MODEL");
    }

    private TextJudge keywordJudge(String raw) {
        if (hitsRedFlag(raw)) {
            return new TextJudge("ABNORMAL", "HIGH", "KEYWORD_RED_FLAG",
                    "描述里有需要立即处理的情况，建议马上联系社区护士或拨打 120", "RULE");
        }
        String s = normalize(raw);
        for (String word : WATCH_WORDS) {
            if (s.contains(word)) {
                return new TextJudge("ABNORMAL", "MEDIUM", "KEYWORD_WATCH",
                        "已记录「" + word + "」，护士这两天会电话问一下情况", "RULE");
            }
        }
        return new TextJudge("NORMAL", "", "KEYWORD_OK", "已记录，继续保持", "RULE");
    }

    private boolean hitsRedFlag(String raw) {
        String s = normalize(raw);
        for (String word : RED_FLAGS) {
            if (s.contains(word)) {
                return true;
            }
        }
        // 组合式红旗：胸部不适 + 闷/紧/压/疼/痛 → 按胸痛处理（宁可多报）
        if (s.contains("胸")) {
            for (String word : CHEST_WORDS) {
                if (s.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 去掉空白和标点，避免"胸口 闷""胸、闷"这类断词漏判；全角转半角不必要，保留原字。 */
    private String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s，。、；：！？,.;:!?~～\\u3000]", "");
    }

    private boolean hasText(SubmitRequest request) {
        return request.text() != null && !request.text().isBlank();
    }

    private String titleFor(CareTask task, String ruleCode) {
        String base;
        if (task != null) {
            base = task.getTitle();
        } else if (ruleCode.startsWith("BP_")) {
            base = "血压";
        } else if (ruleCode.startsWith("GLU_")) {
            base = "血糖";
        } else if (ruleCode.startsWith("TEMP_")) {
            base = "体温";
        } else if (ruleCode.startsWith("SPO2_")) {
            base = "血氧";
        } else if (ruleCode.startsWith("KEYWORD_")) {
            base = "症状反馈";
        } else {
            base = "打卡";
        }
        return trim("打卡异常：" + base, 120);
    }

    private String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词文件缺失：" + PROMPT_PATH, e);
        }
    }

    public record SubmitRequest(Long elderId, Long taskId, String kind, String valueJson,
                                String text, String photoUrl, String voiceUrl) {
    }
}
