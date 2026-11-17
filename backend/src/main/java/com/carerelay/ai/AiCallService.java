package com.carerelay.ai;

import com.carerelay.ai.mapper.AiRequestMapper;
import com.carerelay.common.AiGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 调用的统一留痕：每次模型调用都落一行 ai_request。
 *
 * <p>为什么单独一层：业务代码不应该关心"记录成功还是失败"，也不应该在各处重复写 try/finally。
 * 留痕本身是产品的一部分——护士复核时能看到"这条草稿是哪个模型、什么时候、花了多久生成的"。</p>
 */
@Slf4j
@Service
public class AiCallService {

    /** 落库时截断，避免一条提示词把 ai_request 撑爆 */
    private static final int MAX_LOG_CHARS = 6000;

    private final AiGateway gateway;
    private final AiRequestMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiCallService(AiGateway gateway, AiRequestMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public boolean configured() {
        return gateway.chatConfigured();
    }

    public String model() {
        return gateway.model();
    }

    /**
     * 调用一次模型并留痕。失败也会落一行 FAILED，然后原样抛给调用方决定降级。
     */
    public String chat(String scene, Long userId, String systemPrompt, String userPrompt) {
        // 提示词为空时必须显式报错：否则 Map.of 塞 null 会抛 NPE，
        // 留痕里只剩一行 latency=0 的 FAILED，排查时看不出真正原因。
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalStateException("系统提示词为空，请检查 prompts 资源：" + scene);
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalStateException("调用数据序列化为空，请检查入参：" + scene);
        }
        long start = System.currentTimeMillis();
        AiRequestRecord record = baseRecord(scene, userId, "MODEL", userPrompt);
        try {
            String answer = gateway.chat(systemPrompt, userPrompt);
            long cost = System.currentTimeMillis() - start;
            record.setOutputJson(trim(answer));
            record.setStatus("SUCCESS");
            record.setLatencyMs((int) cost);
            mapper.insert(record);
            log.info("AI 调用完成 scene={} latencyMs={}", scene, cost);
            return answer;
        } catch (RuntimeException e) {
            long cost = System.currentTimeMillis() - start;
            record.setStatus("FAILED");
            record.setError(truncate(String.valueOf(e.getMessage()), 490));
            record.setLatencyMs((int) cost);
            mapper.insert(record);
            log.warn("AI 调用失败 scene={} latencyMs={} err={}", scene, cost, e.getMessage());
            throw e;
        }
    }

    /** 未配置 Key 或调用失败时的本地兜底也要留痕，否则"降级"就变成了偷偷摸摸。 */
    public void logLocal(String scene, Long userId, String input, String output, String note) {
        AiRequestRecord record = baseRecord(scene, userId, "LOCAL", input);
        record.setOutputJson(trim(output));
        record.setStatus("SUCCESS");
        record.setError(truncate(note, 490));
        record.setLatencyMs(0);
        mapper.insert(record);
    }

    private AiRequestRecord baseRecord(String scene, Long userId, String mode, String input) {
        AiRequestRecord record = new AiRequestRecord();
        record.setUserId(userId);
        record.setScene(scene);
        record.setMode(mode);
        record.setModel(gateway.model());
        record.setInputJson(trim(input));
        record.setAdopted(0);
        return record;
    }

    private String trim(String text) {
        return truncate(text, MAX_LOG_CHARS);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 给前端看的小卡片：模型名 + 是否真实调用。 */
    public Map<String, Object> meta(String mode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("model", "LOCAL".equals(mode) ? "本地模板" : gateway.model());
        return out;
    }

    public ObjectMapper json() {
        return objectMapper;
    }
}
