package com.carerelay.ai.dto;

import java.time.LocalDateTime;

/** AI 记录列表项（不含完整输入输出，避免把长文本刷到列表页）。 */
public record AiRecordView(
        Long id,
        String scene,
        String mode,
        String model,
        String status,
        String error,
        Integer latencyMs,
        boolean adopted,
        LocalDateTime createdAt
) {
}
