package com.carerelay.checkin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数值判读的边界用例：这些阈值是"医生定的线"，必须由代码固定住，不能让模型自由发挥。
 */
class VitalRulesTest {

    @Test
    void criticalBloodPressure() {
        VitalRules.Judge j = VitalRules.judge("{\"kind\":\"BP\",\"systolic\":182,\"diastolic\":112}");
        assertEquals("ABNORMAL", j.status());
        assertEquals("HIGH", j.level());
        assertEquals("BP_CRITICAL_180_110", j.ruleCode());
    }

    @Test
    void normalBloodPressure() {
        VitalRules.Judge j = VitalRules.judge("{\"kind\":\"BP\",\"systolic\":132,\"diastolic\":82}");
        assertEquals("NORMAL", j.status());
        assertEquals("BP_OK", j.ruleCode());
    }

    @Test
    void elevatedBloodPressureIsMedium() {
        VitalRules.Judge j = VitalRules.judge("{\"kind\":\"BP\",\"systolic\":148,\"diastolic\":92}");
        assertEquals("MEDIUM", j.level());
        assertEquals("BP_ELEVATED_140_90", j.ruleCode());
    }

    @Test
    void lowBloodPressureIsHigh() {
        VitalRules.Judge j = VitalRules.judge("{\"kind\":\"BP\",\"systolic\":86,\"diastolic\":58}");
        assertEquals("HIGH", j.level());
        assertEquals("BP_LOW_90", j.ruleCode());
    }

    @Test
    void glucoseEdges() {
        assertEquals("GLU_CRITICAL_16_7_3_9",
                VitalRules.judge("{\"kind\":\"GLU\",\"value\":3.5}").ruleCode());
        assertEquals("GLU_CRITICAL_16_7_3_9",
                VitalRules.judge("{\"kind\":\"GLU\",\"value\":17.2}").ruleCode());
        assertEquals("GLU_HIGH_11_1",
                VitalRules.judge("{\"kind\":\"GLU\",\"value\":12.0}").ruleCode());
        assertEquals("NORMAL", VitalRules.judge("{\"kind\":\"GLU\",\"value\":6.4}").status());
    }

    @Test
    void temperatureAndSpo2() {
        assertEquals("TEMP_38_5", VitalRules.judge("{\"kind\":\"TEMP\",\"value\":38.6}").ruleCode());
        assertEquals("TEMP_37_5", VitalRules.judge("{\"kind\":\"TEMP\",\"value\":37.8}").ruleCode());
        assertEquals("SPO2_92", VitalRules.judge("{\"kind\":\"SPO2\",\"value\":91}").ruleCode());
        assertEquals("SPO2_95", VitalRules.judge("{\"kind\":\"SPO2\",\"value\":94}").ruleCode());
    }

    @Test
    void unknownOrEmptyValueFallsBackToNormal() {
        assertEquals("NORMAL", VitalRules.judge("{}").status());
        assertFalse(VitalRules.judge("{\"kind\":\"BP\"}").status().equals("ABNORMAL"));
        assertFalse(VitalRules.judge("{\"kind\":\"BP\"}").message().isBlank());
    }
}
