package com.carerelay.discharge.dto;

/** 抽取入参：elderId 关联老人档案，text 为脱敏后的出院小结原文。 */
public record ExtractRequest(Long elderId, String text, String sourceUrl) {
}
