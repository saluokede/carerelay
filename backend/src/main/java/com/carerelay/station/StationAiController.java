package com.carerelay.station;

import com.carerelay.common.ApiResult;
import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台 · AI 能力入口：风险评分、病情摘要、症状初筛、在线问诊、康复动作。
 * 每次调用都写 ai_logs，便于在「AI 记录」里追溯是模型输出还是规则兜底。
 */
@RestController
@RequestMapping("/api/ai")
public class StationAiController {

    private final JdbcTemplate jdbc;
    private final StationAiService aiService;

    public StationAiController(JdbcTemplate jdbc, StationAiService aiService) {
        this.jdbc = jdbc;
        this.aiService = aiService;
    }

    @PostMapping("/risk-score")
    public ApiResult<Map<String, Object>> riskScore(@RequestBody(required = false) Map<String, Object> body) {
        StationContext.require();
        Map<String, Object> elder = elderOf(body);
        Map<String, Object> result = aiService.assessRisk(elder);
        jdbc.update("UPDATE elders SET risk_score = ? WHERE id = ?", result.get("score"), elder.get("id"));
        logAi("risk-score", elder.get("id"), result, result.get("usedMock"));
        return ApiResult.ok(result);
    }

    @PostMapping("/summary")
    public ApiResult<Map<String, Object>> summary(@RequestBody(required = false) Map<String, Object> body) {
        StationContext.require();
        Map<String, Object> elder = elderOf(body);
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT " + StationSupport.RECORD_COLS + " FROM records WHERE elder_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT 10", elder.get("id"));
        Map<String, Object> result = aiService.summarize(elder, records);
        logAi("summary", elder.get("id"), result.get("summary"), result.get("usedMock"));
        return ApiResult.ok(result);
    }

    @PostMapping("/triage")
    public ApiResult<Map<String, Object>> triage(@RequestBody(required = false) Map<String, Object> body) {
        StationContext.require();
        String text = StationSupport.str(body, "text");
        if (text == null) {
            throw new BizException(400, "请提供症状描述或照片说明");
        }
        Map<String, Object> result = aiService.triage(text);
        Long elderId = StationSupport.longOrNull(body == null ? null : body.get("elder_id"));
        logAi("triage", elderId, result, result.get("usedMock"));
        return ApiResult.ok(result);
    }

    @PostMapping("/chat")
    public ApiResult<Map<String, Object>> chat(@RequestBody(required = false) Map<String, Object> body) {
        String text = StationSupport.str(body, "text");
        if (text == null) {
            throw new BizException(400, "请描述您的症状或疑问");
        }
        Map<String, Object> elder = boundElder(body);
        Map<String, Object> result = aiService.chatTriage(elder, text.trim());
        logAi("chat", elder.get("id"), result, result.get("usedMock"));
        return ApiResult.ok(result);
    }

    @PostMapping("/exercise")
    public ApiResult<Map<String, Object>> exercise(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> elder = boundElder(body);
        Map<String, Object> result = aiService.exerciseGuide(elder);
        logAi("exercise", elder.get("id"), result, result.get("usedMock"));
        return ApiResult.ok(result);
    }

    private Map<String, Object> elderOf(Map<String, Object> body) {
        Long elderId = StationSupport.longOrNull(body == null ? null : body.get("elder_id"));
        return StationSupport.elder(jdbc, elderId);
    }

    /** 家属 / 老人仅可调用绑定老人的 AI 能力。 */
    private Map<String, Object> boundElder(Map<String, Object> body) {
        StationUser user = StationContext.require();
        Map<String, Object> elder = elderOf(body);
        if (user.isFamilyLike() && (user.elderId() == null
                || !user.elderId().equals(StationSupport.longOrNull(elder.get("id"))))) {
            throw new BizException(403, "无权使用该老人的 AI 服务");
        }
        return elder;
    }

    private void logAi(String feature, Object elderId, Object output, Object usedMock) {
        String text = output instanceof String s ? s : String.valueOf(output);
        boolean mock = Boolean.TRUE.equals(usedMock) || "true".equals(String.valueOf(usedMock));
        jdbc.update("INSERT INTO ai_logs (feature,elder_id,output,used_mock) VALUES (?,?,?,?)",
                feature, elderId, text, mock ? 1 : 0);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> empty() {
        return new LinkedHashMap<>();
    }
}
