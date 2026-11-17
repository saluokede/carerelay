package com.carerelay.discharge.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 抽取结果。
 *
 * @param data          结构化 JSON（前端按字段渲染成表单，护士可逐项修改）
 * @param missingFields 代码侧复核出来的"需要人工补齐"清单，与模型自报的合并
 * @param mode          MODEL 真实模型 / LOCAL 本地兜底
 * @param notice        给护士看的一句话说明，例如"当前为本地模式，请手工填写"
 */
public record ExtractResult(
        Long recordId,
        String extractStatus,
        String mode,
        String model,
        List<String> missingFields,
        JsonNode data,
        String notice) {
}
