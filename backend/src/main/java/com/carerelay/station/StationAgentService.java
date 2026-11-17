package com.carerelay.station;

import com.carerelay.common.AiGateway;
import com.carerelay.system.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · 「AI 接力台」Agent。
 *
 * <p>和旧的「一个按钮 = 一次 prompt」不同，这里由模型自己决定下一步：
 * 先读档案、再查预警、必要时检索知识库、最后给结论与待确认动作。
 * 代码只提供工具清单与边界（写库必须人工确认、医学结论必须引用知识库），
 * 具体调用顺序、调几次、调哪个工具由模型在 {@link #MAX_ROUNDS} 轮内自主规划。</p>
 *
 * <p>没配 Key 或上游异常时降级为「规则编排」：按同样的工具顺序跑一遍，
 * 结论由本地规则生成，响应里 {@code mode=RULE} 标注，演示不会白屏。</p>
 */
@Service
public class StationAgentService {

    private static final int MAX_ROUNDS = 6;
    private static final String PROMPT_VERSION = "agent-v1";

    private static final String SYSTEM_PROMPT = """
            你是「医养接力站」的社区照护决策助手，服务对象是出院回家的老人、社区照护员与医院主治医生。

            你可以调用这些工具（是否调用、调用顺序由你决定）：
            - get_elder_profile：读取老人档案与最近 5 条随访/体征记录
            - list_open_alerts：查询该老人未闭环的预警
            - assess_risk：按当前数据评估风险等级与得分
            - search_kb：检索本地康复/用药/预警知识库，返回条目与出处
            - draft_rehab_plan：基于诊断与知识库生成康复方案草稿（不会落库）

            硬性规则：
            1. 任何医学结论都要有工具结果支撑；涉及康复训练、用药、伤口、预警处置时，必须先调用 search_kb，
               并在结论文字里写出出处，格式如《股骨置换术后居家康复规范》第 3 条。
            2. 不做诊断、不给具体药物剂量、不替代医生决策；知识库没有覆盖时明确写「知识库未覆盖，建议人工处理」。
            3. 最终回答只输出 JSON，不要 Markdown 代码块，结构如下：
               {"summary":"3-5 句中文结论","findings":[{"title":"...","detail":"...","level":"高/中/低"}],
                "actions":[{"type":"plan|followup|record","title":"...","detail":"...","payload":{}}]}
            4. actions 只是「待人工确认」的建议动作，最多 3 条；写库由医生点「确认执行」完成，你不得声称已经执行。
            """;

    private final JdbcTemplate jdbc;
    private final StationAiService aiService;
    private final StationRagService rag;
    private final AiGateway gateway;
    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public StationAgentService(JdbcTemplate jdbc, StationAiService aiService, StationRagService rag,
                               AiGateway gateway, SettingsService settings) {
        this.jdbc = jdbc;
        this.aiService = aiService;
        this.rag = rag;
        this.gateway = gateway;
        this.settings = settings;
    }

    /** 跑一次接力评估，返回时间轴步骤 + 结论 + 引用 + 待确认动作。 */
    public Map<String, Object> run(long elderId, String goal) {
        Map<String, Object> elder = StationSupport.elder(jdbc, elderId);
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<Long, Map<String, Object>> citations = new LinkedHashMap<>();
        long started = System.currentTimeMillis();

        SettingsService.AiConfig cfg = settings.resolve();
        String summary = null;
        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        int rounds = 0;
        String mode = "RULE";
        String error = null;

        if (cfg.configured()) {
            try {
                Map<String, Object> r = runLlm(cfg, elder, goal, steps, citations);
                summary = String.valueOf(r.get("summary"));
                findings = castList(r.get("findings"));
                actions = castList(r.get("actions"));
                rounds = ((Number) r.getOrDefault("rounds", 0)).intValue();
                mode = "LLM";
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        if (!"LLM".equals(mode)) {
            steps.clear();
            citations.clear();
            Map<String, Object> r = runRule(elder, goal, steps, citations);
            summary = String.valueOf(r.get("summary"));
            findings = castList(r.get("findings"));
            actions = castList(r.get("actions"));
            rounds = steps.size();
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("mode", mode);
        trace.put("rounds", rounds);
        trace.put("steps", steps);
        trace.put("error", error);
        jdbc.update("INSERT INTO ai_logs (feature, elder_id, output, used_mock, trace, prompt_version) VALUES (?,?,?,?,?,?)",
                "agent", elderId, summary, "LLM".equals(mode) ? 0 : 1, json(trace), PROMPT_VERSION);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("model", cfg.model());
        out.put("usedMock", !"LLM".equals(mode));
        out.put("error", error);
        out.put("elderId", elderId);
        out.put("elderName", elder.get("name"));
        out.put("goal", goal == null || goal.isBlank() ? defaultGoal(elder) : goal);
        out.put("steps", steps);
        out.put("citations", new ArrayList<>(citations.values()));
        out.put("summary", summary);
        out.put("findings", findings);
        out.put("actions", actions);
        out.put("rounds", rounds);
        out.put("latencyMs", System.currentTimeMillis() - started);
        out.put("promptVersion", PROMPT_VERSION);
        return out;
    }

    /* ==================== 模型自主规划 ==================== */

    private Map<String, Object> runLlm(SettingsService.AiConfig cfg, Map<String, Object> elder, String goal,
                                       List<Map<String, Object>> steps, Map<Long, Map<String, Object>> citations) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", userPrompt(elder, goal)));

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            JsonNode reply = gateway.chatMessage(cfg, messages, tools(), 1400);
            JsonNode toolCalls = reply.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = reply.path("content").asText("");
                Map<String, Object> parsed = parseFinal(content, elder);
                parsed.put("rounds", round);
                return parsed;
            }
            messages.add(assistantMessage(reply, toolCalls));
            for (JsonNode call : toolCalls) {
                String callId = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                Map<String, Object> args = parseArgs(call.path("function").path("arguments").asText());
                long t = System.currentTimeMillis();
                Map<String, Object> result;
                try {
                    result = invoke(name, args, elder, citations);
                } catch (Exception e) {
                    result = Map.of("ok", false, "error", String.valueOf(e.getMessage()));
                }
                steps.add(step(steps.size() + 1, name, args, result, System.currentTimeMillis() - t));
                messages.add(toolMessage(callId, result));
            }
        }
        throw new IllegalStateException("Agent 超过 " + MAX_ROUNDS + " 轮仍未给出结论");
    }

    /* ==================== 无模型时的规则编排 ==================== */

    private Map<String, Object> runRule(Map<String, Object> elder, String goal,
                                        List<Map<String, Object>> steps, Map<Long, Map<String, Object>> citations) {
        Object id = elder.get("id");
        long t;

        t = System.currentTimeMillis();
        Map<String, Object> profile = invoke("get_elder_profile", Map.of("elder_id", id), elder, citations);
        steps.add(step(1, "get_elder_profile", Map.of("elder_id", id), profile, System.currentTimeMillis() - t));

        t = System.currentTimeMillis();
        Map<String, Object> alerts = invoke("list_open_alerts", Map.of("elder_id", id), elder, citations);
        steps.add(step(2, "list_open_alerts", Map.of("elder_id", id), alerts, System.currentTimeMillis() - t));

        String query = elder.get("diag") + " " + riskQuery(elder);
        if (goal != null && !goal.isBlank()) {
            query = query + " " + goal;
        }
        t = System.currentTimeMillis();
        Map<String, Object> hits = invoke("search_kb", Map.of("query", query, "top_k", 3), elder, citations);
        steps.add(step(3, "search_kb", Map.of("query", query, "top_k", 3), hits, System.currentTimeMillis() - t));

        t = System.currentTimeMillis();
        Map<String, Object> risk = invoke("assess_risk", Map.of("elder_id", id), elder, citations);
        steps.add(step(4, "assess_risk", Map.of("elder_id", id), risk, System.currentTimeMillis() - t));

        t = System.currentTimeMillis();
        Map<String, Object> plan = invoke("draft_rehab_plan", Map.of("elder_id", id, "focus", "术后居家康复"), elder, citations);
        steps.add(step(5, "draft_rehab_plan", Map.of("elder_id", id), plan, System.currentTimeMillis() - t));

        List<Map<String, Object>> findings = new ArrayList<>();
        findings.add(finding("风险等级", risk.get("level") + "（" + risk.get("score") + " 分）", String.valueOf(risk.get("level"))));
        findings.add(finding("未闭环预警", toInt(elder.get("alerts")) + " 条", toInt(elder.get("alerts")) > 0 ? "高" : "低"));
        findings.add(finding("康复任务完成率", elder.get("rate") + "%", toInt(elder.get("rate")) < 60 ? "高" : "低"));
        List<Map<String, Object>> citationList = new ArrayList<>(citations.values());
        for (Map<String, Object> c : citationList.subList(0, Math.min(2, citationList.size()))) {
            findings.add(finding(String.valueOf(c.get("title")), String.valueOf(c.get("snippet")), "依据"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(elder.get("name")).append("，").append(elder.get("age")).append(" 岁，")
                .append(elder.get("diag")).append("，出院第 ").append(elder.get("day")).append(" 天，当前")
                .append(risk.get("level")).append("风险（").append(risk.get("score")).append(" 分）。");
        sb.append("未闭环预警 ").append(toInt(elder.get("alerts"))).append(" 条，康复任务完成率 ")
                .append(elder.get("rate")).append("%。");
        if (!citationList.isEmpty()) {
            Map<String, Object> top = citationList.get(0);
            sb.append("依据").append(top.get("citation")).append("：").append(top.get("snippet"));
        }
        sb.append("建议 ").append(risk.get("level")).append("风险按规范处置，先签发康复方案，")
                .append("3 天内安排电话随访复核体征，出现异常立即转人工。");

        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(action("plan", "签发康复方案（AI 草稿）", String.valueOf(plan.get("content")),
                Map.of("content", String.valueOf(plan.get("content")))));
        actions.add(action("followup", "安排 3 天后电话随访", "复核血压与伤口情况，必要时安排上门",
                Map.of("type", "电话随访", "scheduled_at", "3 天后", "note", "复盘体征与伤口，确认用药依从性")));
        actions.add(action("record", "归档本次 AI 接力评估", sb.toString(), Map.of("category", "护理")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", sb.toString());
        out.put("findings", findings);
        out.put("actions", actions);
        return out;
    }

    /* ==================== 工具实现 ==================== */

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(function("get_elder_profile", "读取老人档案与最近 5 条随访/体征记录",
                schema(Map.of("elder_id", prop("integer", "老人档案 id")), List.of("elder_id"))));
        list.add(function("list_open_alerts", "查询该老人未闭环的预警记录",
                schema(Map.of("elder_id", prop("integer", "老人档案 id")), List.of("elder_id"))));
        list.add(function("assess_risk", "根据当前档案与预警评估风险等级与得分",
                schema(Map.of("elder_id", prop("integer", "老人档案 id")), List.of("elder_id"))));
        list.add(function("search_kb", "检索本地康复/用药/预警知识库，返回条目正文与出处",
                schema(Map.of(
                        "query", prop("string", "检索关键词，如 股骨置换术后 伤口 红肿 渗液"),
                        "top_k", prop("integer", "返回条数，默认 4")), List.of("query"))));
        list.add(function("draft_rehab_plan", "基于诊断与知识库生成康复方案草稿（不会落库）",
                schema(Map.of(
                        "elder_id", prop("integer", "老人档案 id"),
                        "focus", prop("string", "本次重点方向，如 伤口护理 / 负重训练")), List.of("elder_id"))));
        return list;
    }

    private Map<String, Object> invoke(String name, Map<String, Object> args, Map<String, Object> elder,
                                       Map<Long, Map<String, Object>> citations) {
        Long elderId = toLong(args.get("elder_id"), toLong(elder.get("id"), 0L));
        switch (name == null ? "" : name) {
            case "get_elder_profile" -> {
                List<Map<String, Object>> records = jdbc.queryForList(
                        "SELECT " + StationSupport.RECORD_COLS + " FROM records WHERE elder_id = ? "
                                + "ORDER BY created_at DESC, id DESC LIMIT 5", elderId);
                Map<String, Object> profile = new LinkedHashMap<>();
                profile.put("ok", true);
                profile.put("elder", elderBrief(elder));
                profile.put("records", records);
                return profile;
            }
            case "list_open_alerts" -> {
                List<Map<String, Object>> alerts = jdbc.queryForList(
                        "SELECT " + StationSupport.ALERT_COLS + " FROM alerts WHERE elder_id = ? AND status <> '已闭环' "
                                + "ORDER BY created_at DESC", elderId);
                return Map.of("ok", true, "count", alerts.size(), "alerts", alerts);
            }
            case "assess_risk" -> {
                Map<String, Object> risk = aiService.assessRisk(elder);
                jdbc.update("UPDATE elders SET risk_score = ? WHERE id = ?", risk.get("score"), elderId);
                return Map.of("ok", true, "score", risk.get("score"), "level", risk.get("level"),
                        "reasons", risk.get("reasons"));
            }
            case "search_kb" -> {
                String query = StationSupport.str(args, "query", String.valueOf(elder.get("diag")));
                int topK = args.get("top_k") == null ? 4 : toInt(args.get("top_k"));
                List<Map<String, Object>> hits = rag.search(query, topK);
                for (Map<String, Object> h : hits) {
                    citations.putIfAbsent(((Number) h.get("id")).longValue(), h);
                }
                return Map.of("ok", true, "query", query, "count", hits.size(), "hits", hits);
            }
            case "draft_rehab_plan" -> {
                Map<String, Object> plan = aiService.rulePlan(elder);
                String content = "运动康复：" + plan.get("motion") + "\n饮食建议：" + plan.get("diet")
                        + "\n用药提醒：" + plan.get("med");
                List<Map<String, Object>> grounding = rag.search(String.valueOf(elder.get("diag")) + " 康复 训练", 2);
                for (Map<String, Object> g : grounding) {
                    citations.putIfAbsent(((Number) g.get("id")).longValue(), g);
                }
                return Map.of("ok", true, "content", content, "motion", plan.get("motion"),
                        "diet", plan.get("diet"), "med", plan.get("med"),
                        "grounding", grounding.stream().map(g -> g.get("citation")).toList());
            }
            default -> {
                return Map.of("ok", false, "error", "未知工具：" + name);
            }
        }
    }

    private Map<String, Object> elderBrief(Map<String, Object> elder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", elder.get("id"));
        m.put("name", elder.get("name"));
        m.put("age", elder.get("age"));
        m.put("gender", elder.get("gender"));
        m.put("diag", elder.get("diag"));
        m.put("day", elder.get("day"));
        m.put("risk", elder.get("risk"));
        m.put("risk_score", elder.get("risk_score"));
        m.put("rate", elder.get("rate"));
        m.put("alerts", elder.get("alerts"));
        m.put("living", elder.get("living"));
        m.put("address", elder.get("address"));
        return m;
    }

    /* ==================== 结论解析 ==================== */

    private Map<String, Object> parseFinal(String content, Map<String, Object> elder) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode node = mapper.readTree(stripFence(content));
            out.put("summary", node.path("summary").asText(content.trim()));
            out.put("findings", toList(node.path("findings")));
            out.put("actions", toList(node.path("actions")));
        } catch (Exception e) {
            out.put("summary", content == null || content.isBlank()
                    ? elder.get("name") + " 的 AI 接力评估已完成，但模型未返回结构化结论。" : content.trim());
            out.put("findings", List.of());
            out.put("actions", List.of());
        }
        return out;
    }

    private List<Map<String, Object>> toList(JsonNode array) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return list;
        }
        for (JsonNode item : array) {
            Map<String, Object> m = new LinkedHashMap<>();
            item.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    m.put(e.getKey(), e.getValue().asText());
                } else {
                    m.put(e.getKey(), mapper.convertValue(e.getValue(), Map.class));
                }
            });
            list.add(m);
        }
        return list;
    }

    /* ==================== 消息构造 ==================== */

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private Map<String, Object> toolMessage(String callId, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", callId);
        m.put("content", json(result));
        return m;
    }

    private Map<String, Object> assistantMessage(JsonNode reply, JsonNode toolCalls) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", reply.path("content").isNull() ? "" : reply.path("content").asText(""));
        List<Map<String, Object>> calls = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", call.path("function").path("name").asText());
            fn.put("arguments", call.path("function").path("arguments").asText("{}"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", call.path("id").asText());
            c.put("type", "function");
            c.put("function", fn);
            calls.add(c);
        }
        m.put("tool_calls", calls);
        return m;
    }

    private String userPrompt(Map<String, Object> elder, String goal) {
        return "请为下面这位老人完成一次「出院接力评估」，给出当前风险判断、本周康复与随访安排，并引用知识库条目作为依据。\n"
                + "老人档案：" + json(elderBrief(elder)) + "\n"
                + (goal == null || goal.isBlank() ? "重点：" + defaultGoal(elder) : "本次重点：" + goal) + "\n"
                + "请先调用工具核实情况，不要凭描述推断；涉及康复、用药、伤口处置的结论必须给出知识库出处。";
    }

    private String defaultGoal(Map<String, Object> elder) {
        String diag = String.valueOf(elder.get("diag"));
        return diag + " 出院第 " + elder.get("day") + " 天，评估居家康复进展、识别风险并给出下一步安排";
    }

    private String riskQuery(Map<String, Object> elder) {
        int alerts = toInt(elder.get("alerts"));
        String base = "康复 训练 随访 用药";
        return alerts > 0 ? base + " 预警 处置 伤口 异常" : base;
    }

    /* ==================== 小工具 ==================== */

    private Map<String, Object> step(int n, String tool, Map<String, Object> args,
                                     Map<String, Object> result, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("tool", tool);
        m.put("label", labelOf(tool));
        m.put("args", args);
        m.put("ms", ms);
        m.put("ok", !Boolean.FALSE.equals(result.get("ok")));
        m.put("summary", summarize(tool, result));
        m.put("hits", result.get("hits"));
        m.put("detail", result);
        return m;
    }

    private String labelOf(String tool) {
        return switch (tool == null ? "" : tool) {
            case "get_elder_profile" -> "读取档案与近期记录";
            case "list_open_alerts" -> "查询未闭环预警";
            case "assess_risk" -> "评估风险等级";
            case "search_kb" -> "检索本地知识库";
            case "draft_rehab_plan" -> "起草康复方案";
            default -> tool;
        };
    }

    @SuppressWarnings("unchecked")
    private String summarize(String tool, Map<String, Object> r) {
        try {
            return switch (tool == null ? "" : tool) {
                case "get_elder_profile" -> {
                    Map<String, Object> e = (Map<String, Object>) r.get("elder");
                    List<Map<String, Object>> recs = (List<Map<String, Object>>) r.get("records");
                    yield e.get("name") + " · " + e.get("age") + " 岁 · " + e.get("diag")
                            + " · 出院第 " + e.get("day") + " 天 · 近 " + recs.size() + " 条记录";
                }
                case "list_open_alerts" -> "未闭环预警 " + r.get("count") + " 条";
                case "assess_risk" -> "风险 " + r.get("level") + " · " + r.get("score") + " 分";
                case "search_kb" -> "命中 " + r.get("count") + " 条知识库条目";
                case "draft_rehab_plan" -> "已生成方案草稿（含饮食与用药提醒）";
                default -> String.valueOf(r.get("ok"));
            };
        } catch (Exception e) {
            return String.valueOf(tool);
        }
    }

    private Map<String, Object> finding(String title, String detail, String level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("detail", detail);
        m.put("level", level);
        return m;
    }

    private Map<String, Object> action(String type, String title, String detail, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("detail", detail);
        m.put("payload", payload);
        return m;
    }

    private Map<String, Object> function(String name, String desc, Map<String, Object> params) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", desc);
        fn.put("parameters", params);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        return wrapper;
    }

    private Map<String, Object> schema(Map<String, ?> props, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", new LinkedHashMap<>(props));
        m.put("required", required);
        return m;
    }

    private Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    private Map<String, Object> parseArgs(String raw) {
        try {
            JsonNode node = mapper.readTree(stripFence(raw == null || raw.isBlank() ? "{}" : raw));
            Map<String, Object> m = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> m.put(e.getKey(),
                    e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()));
            return m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object v) {
        return v instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String stripFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        return s;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long toLong(Object v, Long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? def : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
