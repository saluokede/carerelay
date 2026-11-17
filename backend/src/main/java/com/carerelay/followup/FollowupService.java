package com.carerelay.followup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carerelay.ai.AiCallService;
import com.carerelay.alert.AlertEvent;
import com.carerelay.alert.mapper.AlertEventMapper;
import com.carerelay.audit.AuditService;
import com.carerelay.common.BizException;
import com.carerelay.common.JsonText;
import com.carerelay.discharge.DischargeRecord;
import com.carerelay.discharge.mapper.DischargeRecordMapper;
import com.carerelay.elder.ElderProfile;
import com.carerelay.elder.ElderService;
import com.carerelay.followup.mapper.FollowupRecordMapper;
import com.carerelay.followup.mapper.FollowupSessionMapper;
import com.carerelay.plan.CarePlan;
import com.carerelay.plan.mapper.CarePlanMapper;
import com.carerelay.risk.RiskAssessment;
import com.carerelay.risk.mapper.RiskAssessmentMapper;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 随访环：按计划的随访间隔生成会话 → 多轮对话 → 结构化记录 → 红旗升级。
 *
 * <p>对话有两条实现：</p>
 * <ul>
 *   <li><b>MODEL</b>：模型按六件事的顺序问，一次只问一个问题，问完打 <code>[END]</code>；</li>
 *   <li><b>SCRIPT</b>：没有 Key 时用固定脚本推进（题干数组 + 题号），保证演示时对话不空转。</li>
 * </ul>
 * <p>两条路最后的落点完全一样：三行摘要 + 结构化字段 + 红旗位，红旗自动生成预警与工单。</p>
 */
@Slf4j
@Service
public class FollowupService {

    private static final String CHAT_SCENE = "FOLLOWUP_CHAT";
    private static final String SUMMARY_SCENE = "FOLLOWUP_SUMMARY";
    private static final String CHAT_PROMPT = "prompts/followup_chat.v1.md";
    private static final String SUMMARY_PROMPT = "prompts/followup_summarize.v1.md";
    private static final String END_MARK = "[END]";

    /** 脚本模式的问题顺序：与提示词里的六件事一一对应 */
    private static final List<String> SCRIPT_QUESTIONS = List.of(
            "这几天有没有胸闷、胸痛，或者喘不上气的时候？",
            "药都按时吃了吗？有没有漏服或者自己停过？",
            "这一周有没有摔倒，或者起身发晕、差点摔倒？",
            "伤口换药还顺利吗？有没有红肿、渗液或者发热？",
            "饭吃得下吗，晚上睡得好不好？心情怎么样？",
            "还有没有想让我转告护士的事，比如换药、开药或者上门？");

    private static final List<String> RED_FLAG_WORDS = List.of(
            "胸痛", "胸闷", "喘不上气", "喘不过气", "呼吸困难", "意识", "晕倒", "跌倒", "摔倒",
            "出血", "停药", "不吃了", "渗液", "流脓", "高热", "发热", "发烧");

    /** 症状词表：只在"症状那一问"的回答里找，避免把用药/伤口的词误当症状。 */
    private static final List<String> SYMPTOM_WORDS = List.of(
            "胸闷", "胸痛", "心慌", "气短", "喘", "头晕", "乏力", "没力气", "咳嗽", "发热", "发烧",
            "疼痛", "疼", "痛", "恶心", "呕吐", "腹泻", "便秘", "水肿", "麻木");

    private final FollowupSessionMapper sessionMapper;
    private final FollowupRecordMapper recordMapper;
    private final ElderService elderService;
    private final DischargeRecordMapper dischargeMapper;
    private final CarePlanMapper planMapper;
    private final RiskAssessmentMapper riskMapper;
    private final AlertEventMapper alertMapper;
    private final TicketMapper ticketMapper;
    private final AiCallService ai;
    private final AuditService audit;
    private final String chatPrompt;
    private final String summaryPrompt;

    public FollowupService(FollowupSessionMapper sessionMapper,
                           FollowupRecordMapper recordMapper,
                           ElderService elderService,
                           DischargeRecordMapper dischargeMapper,
                           CarePlanMapper planMapper,
                           RiskAssessmentMapper riskMapper,
                           AlertEventMapper alertMapper,
                           TicketMapper ticketMapper,
                           AiCallService ai,
                           AuditService audit) {
        this.sessionMapper = sessionMapper;
        this.recordMapper = recordMapper;
        this.elderService = elderService;
        this.dischargeMapper = dischargeMapper;
        this.planMapper = planMapper;
        this.riskMapper = riskMapper;
        this.alertMapper = alertMapper;
        this.ticketMapper = ticketMapper;
        this.ai = ai;
        this.audit = audit;
        this.chatPrompt = load(CHAT_PROMPT);
        this.summaryPrompt = load(SUMMARY_PROMPT);
    }

    // ---------------------------------------------------------------- 排期

    /**
     * 按最新已签发计划的随访间隔生成下一次随访；已有同日的待随访会话则不重复生成。
     *
     * @return 新会话，或已存在的会话
     */
    @Transactional
    public Map<String, Object> schedule(Long elderId, LocalDate plannedDate) {
        ElderProfile elder = elderService.require(elderId);
        CarePlan plan = planMapper.selectOne(Wrappers.<CarePlan>lambdaQuery()
                .eq(CarePlan::getElderId, elderId)
                .eq(CarePlan::getStatus, "ISSUED")
                .orderByDesc(CarePlan::getId)
                .last("limit 1"));
        int interval = plan == null ? 7 : intervalOf(plan.getFollowupJson());
        LocalDate day = plannedDate == null ? LocalDate.now().plusDays(interval) : plannedDate;

        var existing = sessionMapper.selectList(Wrappers.<FollowupSession>lambdaQuery()
                .eq(FollowupSession::getElderId, elderId)
                .eq(FollowupSession::getPlannedDate, day)
                .eq(FollowupSession::getStatus, "PLANNED")
                .last("limit 1"));
        boolean reusable = !existing.isEmpty()
                && (existing.get(0).getTranscriptJson() == null || "[]".equals(existing.get(0).getTranscriptJson().trim()));
        FollowupSession session = reusable ? existing.get(0) : null;
        if (session == null) {
            session = new FollowupSession();
            session.setElderId(elderId);
            session.setChannel("PHONE");
            session.setPlannedDate(day);
            session.setStatus("PLANNED");
            session.setDurationSec(0);
            session.setQuestionIndex(0);
            session.setMode(ai.configured() ? "MODEL" : "SCRIPT");
            sessionMapper.insert(session);
            audit.record("FOLLOWUP_SCHEDULE", "followup_session", session.getId(),
                    "按 " + interval + " 天间隔排期：" + elder.getName() + " " + day);
        }
        return view(session, elder);
    }

    public List<Map<String, Object>> list(Long elderId) {
        elderService.require(elderId);
        return sessionMapper.selectList(Wrappers.<FollowupSession>lambdaQuery()
                        .eq(FollowupSession::getElderId, elderId)
                        .orderByDesc(FollowupSession::getPlannedDate)
                        .orderByDesc(FollowupSession::getId)
                        .last("limit 50"))
                .stream().map(s -> view(s, null)).toList();
    }

    /** 今天该随访、以及已经逾期的会话。 */
    public List<Map<String, Object>> due() {
        LocalDate today = LocalDate.now();
        return sessionMapper.selectList(Wrappers.<FollowupSession>lambdaQuery()
                        .eq(FollowupSession::getStatus, "PLANNED")
                        .le(FollowupSession::getPlannedDate, today)
                        .orderByAsc(FollowupSession::getPlannedDate))
                .stream().map(s -> view(s, null)).toList();
    }

    // ---------------------------------------------------------------- 对话

    /** 开始随访：写开场白，并把第一个问题发出去。 */
    @Transactional
    public Map<String, Object> start(Long sessionId) {
        FollowupSession session = require(sessionId);
        if ("DONE".equals(session.getStatus())) {
            throw new BizException(409, "该次随访已完成");
        }
        if (session.getStartedAt() == null) {
            session.setStartedAt(LocalDateTime.now());
            ElderProfile elder = elderService.require(session.getElderId());
            String opening = greeting(elder);
            appendTranscript(session, "assistant", opening);
            String first = nextQuestion(session, elder);
            appendTranscript(session, "assistant", first);
            sessionMapper.updateById(session);
        }
        audit.record("FOLLOWUP_START", "followup_session", session.getId(), "开始随访（" + session.getMode() + "）");
        return sessionView(session);
    }

    /**
     * 一轮对话：写入对方的话，产出下一句（模型或脚本）。
     *
     * @return {reply, ended, questionIndex, mode}
     */
    @Transactional
    public Map<String, Object> message(Long sessionId, String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(400, "内容为空");
        }
        FollowupSession session = require(sessionId);
        if ("DONE".equals(session.getStatus())) {
            throw new BizException(409, "该次随访已完成");
        }
        if (session.getStartedAt() == null) {
            start(sessionId);
            session = require(sessionId);
        }
        ElderProfile elder = elderService.require(session.getElderId());
        appendTranscript(session, "user", text);

        String reply;
        boolean ended;
        if ("MODEL".equals(session.getMode()) && ai.configured()) {
            reply = modelReply(session, elder, text);
            ended = reply.contains(END_MARK);
            reply = reply.replace(END_MARK, "").trim();
            if (reply.isEmpty()) {
                reply = "好的，我记下了，护士会看这段记录。";
            }
        } else {
            // questionIndex = 已问过几题。先问第 asked 题（0 基），再 +1；问满即收尾。
            int asked = session.getQuestionIndex() == null ? 0 : session.getQuestionIndex();
            if (asked >= SCRIPT_QUESTIONS.size()) {
                ended = true;
                reply = "谢谢您，我这边整理一下给护士看，有问题她会联系您。";
            } else {
                ended = false;
                reply = SCRIPT_QUESTIONS.get(asked);
                session.setQuestionIndex(asked + 1);
            }
        }
        appendTranscript(session, "assistant", reply);
        sessionMapper.updateById(session);
        audit.record("FOLLOWUP_TURN", "followup_session", session.getId(), "第 " + turns(session) + " 轮");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reply", reply);
        view.put("ended", ended);
        view.put("questionIndex", session.getQuestionIndex());
        view.put("mode", session.getMode());
        return view;
    }

    /** 结束随访：生成结构化记录；红旗自动升级预警与工单。 */
    @Transactional
    public Map<String, Object> finish(Long sessionId) {
        FollowupSession session = require(sessionId);
        if (recordMapper.selectCount(Wrappers.<FollowupRecord>lambdaQuery()
                .eq(FollowupRecord::getSessionId, sessionId)) > 0) {
            throw new BizException(409, "该次随访已生成记录");
        }
        ElderProfile elder = elderService.require(session.getElderId());
        DischargeRecord record = latestRecord(elder.getId());
        RiskAssessment risk = latestRisk(elder.getId());
        String transcript = transcriptText(session);

        Map<String, Object> payload;
        String source;
        if (ai.configured() && !transcript.isBlank()) {
            payload = modelSummary(elder, record, transcript);
            source = "MODEL";
        } else {
            payload = null;
            source = "RULE";
        }
        if (payload == null) {
            payload = ruleSummary(answerTexts(session));
            source = "RULE";
        }

        boolean redFlag = Boolean.TRUE.equals(payload.get("red_flag"));
        FollowupRecord out = new FollowupRecord();
        out.setSessionId(session.getId());
        out.setElderId(elder.getId());
        out.setPayloadJson(JsonText.of(payload));
        out.setSummary(String.valueOf(payload.getOrDefault("summary", "")));
        out.setRedFlag(redFlag ? 1 : 0);
        recordMapper.insert(out);

        session.setStatus("DONE");
        session.setEndedAt(LocalDateTime.now());
        if (session.getStartedAt() != null) {
            session.setDurationSec((int) Math.max(1,
                    java.time.Duration.between(session.getStartedAt(), session.getEndedAt()).toSeconds()));
        }
        sessionMapper.updateById(session);

        Long alertId = null;
        Long ticketId = null;
        if (redFlag) {
            AlertEvent alert = new AlertEvent();
            alert.setElderId(elder.getId());
            alert.setSource("FOLLOWUP");
            alert.setRuleCode("FOLLOWUP_RED_FLAG");
            alert.setLevel("HIGH");
            alert.setTitle("随访红旗：需立即处置");
            alert.setDetail(elder.getName() + "（" + (risk == null ? "未知" : risk.getLevel()) + "风险）："
                    + firstLine(out.getSummary()));
            alert.setStatus("OPEN");
            alertMapper.insert(alert);
            alertId = alert.getId();

            Ticket ticket = new Ticket();
            ticket.setElderId(elder.getId());
            ticket.setAlertId(alert.getId());
            ticket.setSource("FOLLOWUP");
            ticket.setLevel("HIGH");
            ticket.setReason("随访命中红旗：" + firstLine(out.getSummary()));
            ticket.setStatus("PENDING");
            ticketMapper.insert(ticket);
            ticketId = ticket.getId();
            audit.record("ALERT_RAISE", "alert_event", alert.getId(), "随访红旗 → 预警 + 工单");
        }
        audit.record("FOLLOWUP_FINISH", "followup_record", out.getId(),
                "随访记录生成（" + source + "），红旗=" + redFlag);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("recordId", out.getId());
        view.put("summary", out.getSummary());
        view.put("payload", payload);
        view.put("redFlag", redFlag);
        view.put("source", source);
        view.put("alertId", alertId);
        view.put("ticketId", ticketId);
        view.put("durationSec", session.getDurationSec());
        return view;
    }

    // ---------------------------------------------------------------- 内部

    private String greeting(ElderProfile elder) {
        String hour = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH"));
        String when = hour.compareTo("11") < 0 ? "上午" : hour.compareTo("18") < 0 ? "下午" : "晚上";
        return elder.getName() + (elder.getGender() != null && "FEMALE".equals(elder.getGender()) ? "阿姨" : "叔叔")
                + "您好，" + when + "好，我是社区卫生服务中心的随访助手。出院后我们打个电话问问情况，"
                + "就问几个问题，您方便吗？";
    }

    private String nextQuestion(FollowupSession session, ElderProfile elder) {
        if ("MODEL".equals(session.getMode()) && ai.configured()) {
            String reply = modelReply(session, elder, "（家属：方便，你说吧）");
            return reply.replace(END_MARK, "").trim();
        }
        session.setQuestionIndex(1);
        return SCRIPT_QUESTIONS.get(0);
    }

    private String modelReply(FollowupSession session, ElderProfile elder, String userText) {
        String prompt = "【本次随访背景】\n"
                + "老人：" + elder.getName() + "，" + JsonText.age(elder.getBirthDate()) + " 岁，"
                + safe(diagnosisOf(elder.getId())) + "，出院 " + dischargeDate(elder.getId()) + "\n"
                + "【已问过】\n" + transcriptText(session) + "\n"
                + "【对方刚说】\n" + userText;
        try {
            String out = ai.chat(CHAT_SCENE, null, chatPrompt, prompt);
            return out == null ? "" : out.trim();
        } catch (RuntimeException e) {
            log.warn("随访对话模型调用失败，本轮回退脚本问题：{}", e.getMessage());
            int asked = session.getQuestionIndex() == null ? 0 : session.getQuestionIndex();
            if (asked >= SCRIPT_QUESTIONS.size()) {
                return "谢谢您，我这边整理一下给护士看。" + END_MARK;
            }
            session.setQuestionIndex(asked + 1);
            return SCRIPT_QUESTIONS.get(asked);
        }
    }

    private Map<String, Object> modelSummary(ElderProfile elder, DischargeRecord record, String transcript) {
        String prompt = "老人：" + elder.getName() + "，" + JsonText.age(elder.getBirthDate()) + " 岁，"
                + (record == null ? "" : "主要诊断 " + record.getDiagnosisPrimary()) + "\n"
                + "对话原文：\n" + transcript;
        try {
            String out = ai.chat(SUMMARY_SCENE, null, summaryPrompt, prompt);
            if (out == null) {
                return null;
            }
            String s = out.trim();
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            var node = JsonText.parse(s.substring(start, end + 1));
            if (node == null || !node.has("summary")) {
                return null;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("symptoms", node.path("symptoms"));
            payload.put("medication", node.path("medication"));
            payload.put("falls", node.path("falls"));
            payload.put("wound", node.path("wound"));
            payload.put("mood", node.path("mood").asText("平稳"));
            payload.put("needs", node.path("needs"));
            payload.put("red_flag", node.path("red_flag").asBoolean(false));
            payload.put("summary", node.path("summary").asText(""));
            payload.put("_source", "MODEL");
            return payload;
        } catch (RuntimeException e) {
            log.warn("随访摘要模型调用失败，改用规则摘要：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 无模型时的规则摘要：按"第几个问题对应的答案"逐项抽取，避免把不同问题的关键词混在一起。
     * 顺序固定为脚本六问：症状 / 用药 / 跌倒 / 伤口 / 饮食睡眠情绪 / 需求。
     */
    private Map<String, Object> ruleSummary(List<String> answers) {
        String at = answers.size() > 0 ? answers.get(0) : "";
        String med = answers.size() > 1 ? answers.get(1) : "";
        String fall = answers.size() > 2 ? answers.get(2) : "";
        String wound = answers.size() > 3 ? answers.get(3) : "";
        String moodText = answers.size() > 4 ? answers.get(4) : "";
        String needText = answers.size() > 5 ? answers.get(5) : "";
        String all = String.join("", answers);

        List<String> hits = new ArrayList<>();
        for (String word : SYMPTOM_WORDS) {
            if (mentioned(at, word) && !hits.contains(word)) {
                hits.add(word);
            }
        }
        boolean red = false;
        for (String word : RED_FLAG_WORDS) {
            if (mentioned(all, word)) {
                red = true;
            }
        }
        boolean fell = mentioned(fall, "摔") || mentioned(fall, "跌");
        boolean dizzy = mentioned(fall, "晕");
        // 只把"红肿/渗液/渗血/发红"算伤口问题；单纯换药时疼归到 detail，不升级
        boolean woundIssue = mentioned(wound, "红肿") || mentioned(wound, "渗液") || mentioned(wound, "渗血")
                || mentioned(wound, "发红") || mentioned(wound, "流脓");
        boolean missMed = mentioned(med, "漏") || mentioned(med, "忘") || mentioned(med, "停");
        String mood = "未提及";
        if (!moodText.isBlank()) {
            mood = moodText.contains("睡不好") || moodText.contains("失眠") || moodText.contains("烦")
                    || moodText.contains("低落") || moodText.contains("没意思") ? "低落"
                    : moodText.contains("挺好") || moodText.contains("还行") || moodText.contains("不错")
                    ? "平稳" : "一般";
        }

        List<String> needs = new ArrayList<>();
        if (needText.contains("换药")) {
            needs.add("换药");
        }
        if (needText.contains("上门")) {
            needs.add("上门");
        }
        if (needText.contains("开药") || needText.contains("配药") || needText.contains("拿药")) {
            needs.add("开药");
        }

        StringBuilder summary = new StringBuilder();
        summary.append("本次随访六问已完，主诉：").append(hits.isEmpty() ? "未诉明显不适" : String.join("、", hits)).append("\n");
        summary.append("需注意：").append(red ? "出现红旗症状，须当日处理" : woundIssue ? "伤口/换药情况需跟进" : "暂无红旗，关注依从性").append("\n");
        summary.append("下一步：").append(needs.isEmpty() ? "按计划继续随访" : "安排" + String.join("、", needs));

        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> symptoms = new ArrayList<>();
        for (String word : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", word);
            m.put("duration", "本次随访提及");
            m.put("trend", "未评估");
            m.put("note", "");
            symptoms.add(m);
        }
        payload.put("symptoms", symptoms);
        payload.put("medication", Map.of("adherence", missMed ? "存在漏服或停药" : "按时", "detail", med));
        payload.put("falls", Map.of("happened", fell, "detail", fall + (dizzy ? "（诉头晕）" : "")));
        payload.put("wound", Map.of("issue", woundIssue, "detail", wound));
        payload.put("mood", mood);
        payload.put("needs", needs);
        payload.put("red_flag", red);
        payload.put("summary", summary.toString());
        payload.put("_source", "RULE");
        return payload;
    }

    private Map<String, Object> sessionView(FollowupSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("elderId", s.getElderId());
        m.put("plannedDate", s.getPlannedDate());
        m.put("status", s.getStatus());
        m.put("mode", s.getMode());
        m.put("questionIndex", s.getQuestionIndex());
        m.put("startedAt", s.getStartedAt());
        m.put("transcript", JsonText.parse(s.getTranscriptJson()));
        return m;
    }

    private Map<String, Object> view(FollowupSession s, ElderProfile elder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("elderId", s.getElderId());
        if (elder == null && s.getElderId() != null) {
            try {
                elder = elderService.require(s.getElderId());
            } catch (RuntimeException ignored) {
                elder = null;
            }
        }
        m.put("elderName", elder == null ? null : elder.getName());
        m.put("plannedDate", s.getPlannedDate());
        m.put("status", s.getStatus());
        m.put("mode", s.getMode());
        m.put("channel", s.getChannel());
        m.put("questionIndex", s.getQuestionIndex());
        m.put("startedAt", s.getStartedAt());
        m.put("endedAt", s.getEndedAt());
        m.put("durationSec", s.getDurationSec());
        // 列表也要带对话内容，否则刷新页面后历史气泡会空掉
        m.put("transcript", JsonText.parse(s.getTranscriptJson()));
        return m;
    }

    private FollowupSession require(Long id) {
        FollowupSession s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "随访会话不存在");
        }
        return s;
    }

    private void appendTranscript(FollowupSession session, String role, String content) {
        var arr = JsonText.parse(session.getTranscriptJson());
        List<Map<String, Object>> items = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> items.add(JsonText.toMap(n)));
        }
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("role", role);
        turn.put("content", content);
        turn.put("at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        items.add(turn);
        session.setTranscriptJson(JsonText.of(items));
    }

    private String transcriptText(FollowupSession session) {
        var node = JsonText.parse(session.getTranscriptJson());
        if (node == null || !node.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var item : node) {
            String role = item.path("role").asText("");
            String content = item.path("content").asText("");
            sb.append("user".equals(role) ? "家属：" : "助手：").append(content).append("\n");
        }
        return sb.toString().trim();
    }

    /** 按顺序取出家属每一轮的回答，供规则摘要按"第几问"对应。 */
    private List<String> answerTexts(FollowupSession session) {
        var node = JsonText.parse(session.getTranscriptJson());
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (var item : node) {
            if ("user".equals(item.path("role").asText(""))) {
                out.add(item.path("content").asText(""));
            }
        }
        return out;
    }

    /** 是否真的"提到了"某词：前面 6 个字里带否定词（没有/没/无/不/未）就算没提。 */
    private static boolean mentioned(String text, String word) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int idx = text.indexOf(word);
        while (idx >= 0) {
            String pre = text.substring(Math.max(0, idx - 6), idx);
            boolean negated = pre.contains("没有") || pre.contains("没") || pre.contains("无")
                    || pre.contains("不") || pre.contains("未") || pre.contains("否认");
            if (!negated) {
                return true;
            }
            idx = text.indexOf(word, idx + word.length());
        }
        return false;
    }

    private int turns(FollowupSession session) {
        var node = JsonText.parse(session.getTranscriptJson());
        return node == null || !node.isArray() ? 0 : node.size();
    }

    private String firstLine(String summary) {
        if (summary == null) {
            return "";
        }
        int i = summary.indexOf('\n');
        return i < 0 ? summary : summary.substring(0, i);
    }

    private DischargeRecord latestRecord(Long elderId) {
        var list = dischargeMapper.selectList(Wrappers.<DischargeRecord>lambdaQuery()
                .eq(DischargeRecord::getElderId, elderId)
                .orderByDesc(DischargeRecord::getId).last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private RiskAssessment latestRisk(Long elderId) {
        var list = riskMapper.selectList(Wrappers.<RiskAssessment>lambdaQuery()
                .eq(RiskAssessment::getElderId, elderId)
                .orderByDesc(RiskAssessment::getId).last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private String diagnosisOf(Long elderId) {
        DischargeRecord r = latestRecord(elderId);
        return r == null || r.getDiagnosisPrimary() == null ? "出院康复中" : r.getDiagnosisPrimary();
    }

    private String dischargeDate(Long elderId) {
        DischargeRecord r = latestRecord(elderId);
        return r == null || r.getDischargeDate() == null ? "近期"
                : r.getDischargeDate().format(DateTimeFormatter.ISO_DATE);
    }

    private int intervalOf(String followupJson) {
        var node = JsonText.parse(followupJson);
        if (node == null) {
            return 7;
        }
        int v = node.path("interval_days").asInt(7);
        return v <= 0 ? 7 : v;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词文件缺失：" + path, e);
        }
    }
}
