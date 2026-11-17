package com.carerelay.station;

import com.carerelay.common.AiGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新版工作台 · AI 能力：优先走原版 {@link AiGateway}（OpenAI 兼容配置），
 * 未配置或调用失败时降级为本地规则，响应里用 usedMock 标记，保证演示环境始终可用。
 */
@Service
public class StationAiService {

    private static final Pattern ACUTE = Pattern.compile("脑卒中|置换|骨折|冠心病");
    private static final Pattern STROKE = Pattern.compile("脑卒中");
    private static final Pattern ORTHO = Pattern.compile("置换|骨折");
    private static final Pattern HEART = Pattern.compile("冠心病|心脏");
    private static final Pattern HIGH_RISK = Pattern.compile(
            "出血|发紫|剧烈疼痛|意识|胸闷|呼吸困难|红肿发热|伤口裂开");
    private static final Pattern HIGH_RISK_CHAT = Pattern.compile(
            "出血|发紫|剧烈疼痛|意识|胸闷|呼吸困难|红肿发热|伤口裂开|偏瘫|言语不清|胸痛");
    private static final Pattern MID_RISK = Pattern.compile("疼痛|不适|头晕|肿胀|麻木");
    private static final Pattern MID_RISK_CHAT = Pattern.compile(
            "疼痛|不适|头晕|肿|胀|麻木|乏力|失眠|咳嗽|水肿|痛|疼");

    private final AiGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public StationAiService(AiGateway gateway) {
        this.gateway = gateway;
    }

    /* ---------- 风险评分 ---------- */

    public Map<String, Object> assessRisk(Map<String, Object> elder) {
        Map<String, Object> fallback = ruleAssess(elder);
        String out = ask(
                "你是社区老年康复风险评估助手，只输出 JSON：{\"score\":0-100,\"level\":\"高/中/低\",\"reasons\":[\"...\"]}",
                "患者档案：" + json(elder));
        if (out == null) {
            return fallback;
        }
        try {
            JsonNode parsed = mapper.readTree(stripFence(out));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("score", clamp(30, 95, parsed.path("score").asDouble(((Number) fallback.get("score")).doubleValue())));
            data.put("level", text(parsed, "level", (String) fallback.get("level")));
            data.put("reasons", parsed.path("reasons").isArray() && !parsed.path("reasons").isEmpty()
                    ? parsed.path("reasons") : fallback.get("reasons"));
            data.put("usedMock", false);
            return data;
        } catch (Exception e) {
            return fallback;
        }
    }

    public Map<String, Object> ruleAssess(Map<String, Object> elder) {
        int alerts = toInt(elder.get("alerts"));
        int rate = toInt(elder.get("rate"));
        String diag = elder.get("diag") == null ? "" : String.valueOf(elder.get("diag"));
        boolean acute = ACUTE.matcher(diag).find();
        double score = 80 - alerts * 8 - Math.max(0, 70 - rate) * 0.5 - (acute ? 5 : 0);
        score = clamp(30, 95, Math.round(score));
        String level;
        if (alerts > 0 && (rate < 60 || acute)) {
            level = "高";
        } else if (alerts > 0 || rate < 60) {
            level = "中";
        } else {
            level = "低";
        }
        List<String> reasons = new ArrayList<>();
        if (alerts > 0) {
            reasons.add("存在 " + alerts + " 条未闭环预警");
        }
        if (rate < 60) {
            reasons.add("康复任务完成率偏低");
        }
        if (acute) {
            reasons.add("术后/慢病康复期需重点关注");
        }
        if (reasons.isEmpty()) {
            reasons.add("体征与打卡整体平稳");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("score", score);
        data.put("level", level);
        data.put("reasons", reasons);
        data.put("usedMock", true);
        return data;
    }

    /* ---------- 康复方案草稿 ---------- */

    public Map<String, Object> draftPlan(Map<String, Object> elder) {
        Map<String, Object> fallback = rulePlan(elder);
        String out = ask("你是康复方案制定助手，按 JSON 输出 {\"motion\":\"...\",\"diet\":\"...\",\"med\":\"...\"}，内容贴合老年术后/慢病康复。",
                "患者：" + json(elder));
        if (out == null) {
            return fallback;
        }
        try {
            JsonNode parsed = mapper.readTree(stripFence(out));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("motion", text(parsed, "motion", (String) fallback.get("motion")));
            data.put("diet", text(parsed, "diet", (String) fallback.get("diet")));
            data.put("med", text(parsed, "med", (String) fallback.get("med")));
            data.put("usedMock", false);
            return data;
        } catch (Exception e) {
            return fallback;
        }
    }

    public Map<String, Object> rulePlan(Map<String, Object> elder) {
        String diag = elder.get("diag") == null ? "" : String.valueOf(elder.get("diag"));
        String motion;
        if (STROKE.matcher(diag).find()) {
            motion = "肢体被动活动 + 吞咽训练，每日 2 次，每次 15 分钟";
        } else if (ORTHO.matcher(diag).find()) {
            motion = "踝泵运动与关节活动度训练，坐姿缓慢完成，避免患肢负重";
        } else {
            motion = "每日适度步行与关节拉伸，避免剧烈运动";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("motion", motion);
        data.put("diet", "低盐低脂、优质蛋白、足量饮水；按慢病要求控制糖分与油脂");
        data.put("med", "遵出院医嘱按时服药，不可自行停药或加减剂量；出现不适及时上报");
        data.put("usedMock", true);
        return data;
    }

    /* ---------- 病情摘要 ---------- */

    public Map<String, Object> summarize(Map<String, Object> elder, List<Map<String, Object>> records) {
        String fallback = ruleSummary(elder, records);
        String out = ask("你是病历摘要助手，把多条随访/体征记录压缩成一段客观、简洁的病情变化摘要。",
                "患者：" + json(elder) + "\n记录：" + json(records));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", out == null || out.isBlank() ? fallback : out);
        data.put("usedMock", out == null || out.isBlank());
        return data;
    }

    private String ruleSummary(Map<String, Object> elder, List<Map<String, Object>> records) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> r : records == null ? List.<Map<String, Object>>of() : records.subList(0, Math.min(5, records.size()))) {
            String ts = String.valueOf(r.get("created_at"));
            String stamp = ts.length() >= 16 ? ts.substring(5, 16) : ts;
            String detail = r.get("detail") == null ? "" : String.valueOf(r.get("detail"));
            lines.add("· " + stamp + " " + r.get("title") + (detail.isBlank() ? "" : "：" + detail));
        }
        return elder.get("name") + "（" + elder.get("age") + "岁，" + elder.get("diag") + "）出院第 "
                + elder.get("day") + " 天，当前" + elder.get("risk") + "风险。近期记录：\n"
                + (lines.isEmpty() ? "暂无记录" : String.join("\n", lines));
    }

    /* ---------- 症状初筛 ---------- */

    public Map<String, Object> triage(String text) {
        Map<String, Object> fallback = ruleTriage(text);
        String out = ask("你是症状初筛助手，只输出 JSON：{\"level\":\"高/中/低\",\"advice\":\"...\"}，不做确诊。",
                "症状描述：" + text);
        if (out == null) {
            return fallback;
        }
        try {
            JsonNode parsed = mapper.readTree(stripFence(out));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("level", text(parsed, "level", (String) fallback.get("level")));
            data.put("advice", text(parsed, "advice", (String) fallback.get("advice")));
            data.put("usedMock", false);
            return data;
        } catch (Exception e) {
            return fallback;
        }
    }

    public Map<String, Object> ruleTriage(String text) {
        String t = text == null ? "" : text;
        Map<String, Object> data = new LinkedHashMap<>();
        if (HIGH_RISK.matcher(t).find()) {
            data.put("level", "高");
            data.put("advice", "提示存在较高风险，建议立即联系社区医护，必要时急诊");
        } else if (MID_RISK.matcher(t).find()) {
            data.put("level", "中");
            data.put("advice", "存在中等不适，建议尽快随访复核并记录变化");
        } else {
            data.put("level", "低");
            data.put("advice", "暂未见明显高危信号，继续按计划康复并观察");
        }
        data.put("usedMock", true);
        return data;
    }

    /* ---------- 问诊对话 ---------- */

    public Map<String, Object> chatTriage(Map<String, Object> elder, String text) {
        Map<String, Object> fallback = ruleChat(elder, text);
        String out = ask("你是社区老年康复问诊助手，只做健康建议与风险提示，不做确诊、不开处方。"
                        + "只输出 JSON：{\"reply\":\"给老人/家属的口语化回复\",\"level\":\"高/中/低\",\"advice\":\"处置建议\"}。",
                "患者档案：" + json(elder) + "\n家属/老人描述：" + text);
        if (out == null) {
            return fallback;
        }
        try {
            JsonNode parsed = mapper.readTree(stripFence(out));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reply", text(parsed, "reply", (String) fallback.get("reply")));
            String level = text(parsed, "level", (String) fallback.get("level"));
            data.put("level", List.of("高", "中", "低").contains(level) ? level : fallback.get("level"));
            data.put("advice", text(parsed, "advice", (String) fallback.get("advice")));
            data.put("usedMock", false);
            return data;
        } catch (Exception e) {
            return fallback;
        }
    }

    public Map<String, Object> ruleChat(Map<String, Object> elder, String text) {
        String t = text == null ? "" : text;
        Map<String, Object> data = new LinkedHashMap<>();
        if (HIGH_RISK_CHAT.matcher(t).find()) {
            data.put("level", "高");
            data.put("reply", "您描述的情况需要重视。请先保持安静休息，不要自行走动或加药；我们已提醒社区医生尽快与您联系，"
                    + "若出现胸痛加重、呼吸困难或意识不清，请立即拨打急救电话。");
            data.put("advice", "高危信号，需尽快医护介入，必要时急诊。");
        } else if (MID_RISK_CHAT.matcher(t).find()) {
            data.put("level", "中");
            data.put("reply", "收到您的情况。建议先记录出现的时间、持续时长和诱发因素，按时测量血压并打卡；"
                    + "社区医生会在随访中重点复核，若加重请随时上报。");
            data.put("advice", "中等不适，建议尽快随访复核并观察变化。");
        } else {
            data.put("level", "低");
            data.put("reply", "目前看暂未见明显高危信号，请继续按康复计划活动、规律服药并按时测量体征；"
                    + "有新的不适可以随时在这里留言。");
            data.put("advice", "继续按计划康复并观察，异常及时上报。");
        }
        data.put("usedMock", true);
        return data;
    }

    /* ---------- 康复动作指导 ---------- */

    public Map<String, Object> exerciseGuide(Map<String, Object> elder) {
        Map<String, Object> fallback = ruleExercise(elder);
        String out = ask("你是老年康复指导助手，按 JSON 输出：{\"summary\":\"阶段说明\",\"steps\":[{\"action\":\"动作\","
                        + "\"detail\":\"做法要点\",\"count\":\"频次/时长\"}],\"cautions\":\"禁忌与风险提示\",\"diet\":\"饮食建议\"}，"
                        + "内容贴合术后/慢病居家康复，安全第一。",
                "患者：" + json(elder));
        if (out == null) {
            return fallback;
        }
        try {
            JsonNode parsed = mapper.readTree(stripFence(out));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("summary", text(parsed, "summary", (String) fallback.get("summary")));
            data.put("steps", parsed.path("steps").isArray() && !parsed.path("steps").isEmpty()
                    ? mapper.convertValue(parsed.path("steps"), List.class) : fallback.get("steps"));
            data.put("cautions", text(parsed, "cautions", (String) fallback.get("cautions")));
            data.put("diet", text(parsed, "diet", (String) fallback.get("diet")));
            data.put("usedMock", false);
            return data;
        } catch (Exception e) {
            return fallback;
        }
    }

    public Map<String, Object> ruleExercise(Map<String, Object> elder) {
        String diag = elder.get("diag") == null ? "" : String.valueOf(elder.get("diag"));
        String main;
        if (STROKE.matcher(diag).find()) {
            main = "肢体被动活动与吞咽训练";
        } else if (ORTHO.matcher(diag).find()) {
            main = "踝泵运动与关节活动度训练";
        } else if (HEART.matcher(diag).find()) {
            main = "平地步行与呼吸放松训练";
        } else {
            main = "适度步行与关节拉伸";
        }
        Object day = elder.get("day");
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("热身活动", "坐位或卧位缓慢活动手脚关节，每个方向停留 5 秒，放松肌肉。", "5 分钟"));
        steps.add(step(main, "动作缓慢、幅度由小到大，过程中如疼痛或气促立即停下休息。", "每日 2 次，每次 10–15 分钟"));
        steps.add(step("放松整理", "缓慢深呼吸，放松肩颈与四肢，记录完成情况并打卡。", "3 分钟"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", "针对" + (diag.isBlank() ? "术后康复" : diag) + "第 " + (day == null ? 1 : day)
                + " 天的居家阶段，建议以“循序渐进、避免负重”为原则开展" + main + "。");
        data.put("steps", steps);
        data.put("cautions", "避免患肢过早负重、剧烈扭转和屏气用力；出现胸痛、头晕、明显肿胀或疼痛加重时停止训练并上报。");
        data.put("diet", "低盐低脂、优质蛋白、足量饮水；按慢病要求控制糖分与油脂，遵医嘱服药。");
        data.put("usedMock", true);
        return data;
    }

    private Map<String, Object> step(String action, String detail, String count) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", action);
        step.put("detail", detail);
        step.put("count", count);
        return step;
    }

    /* ---------- 体征越界 ---------- */

    /** 体征上报越界判定：血压 ≥160/100 或心率 ≥110 时返回预警原因，否则 null。 */
    public String vitalAlertReason(String category, String value) {
        if (!"体征".equals(category) || value == null || value.isBlank()) {
            return null;
        }
        Matcher bp = Pattern.compile("(\\d{2,3})\\s*/\\s*(\\d{2,3})").matcher(value);
        if (bp.find()) {
            int sys = Integer.parseInt(bp.group(1));
            int dia = Integer.parseInt(bp.group(2));
            if (sys >= 160 || dia >= 100) {
                return "血压 " + sys + "/" + dia + " mmHg 超出安全范围，建议尽快复核";
            }
        }
        Matcher hr = Pattern.compile("心率\\s*(\\d{2,3})|(\\d{2,3})\\s*次").matcher(value);
        if (hr.find()) {
            String raw = hr.group(1) == null ? hr.group(2) : hr.group(1);
            if (raw != null && Integer.parseInt(raw) >= 110) {
                return "心率 " + Integer.parseInt(raw) + " 次/分偏快，建议复测并观察";
            }
        }
        return null;
    }

    /* ---------- 内部 ---------- */

    /** 调用大模型；未配置或异常时返回 null，由调用方降级到规则。 */
    private String ask(String systemPrompt, String userPrompt) {
        try {
            if (!gateway.chatConfigured()) {
                return null;
            }
            return gateway.chat(systemPrompt, userPrompt);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String stripFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        return s;
    }

    private String text(JsonNode node, String field, String def) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? def : v.asText();
    }

    private int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double clamp(double min, double max, double v) {
        return Math.min(max, Math.max(min, v));
    }

    @SuppressWarnings("unused")
    private static final List<String> LEVELS = Arrays.asList("高", "中", "低");
}
