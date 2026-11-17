package com.carerelay.checkin;

import com.fasterxml.jackson.databind.JsonNode;
import com.carerelay.common.JsonText;

import java.util.Locale;

/**
 * 数值判读：**纯规则，不经过模型**。
 *
 * <p>血压/血糖/体温/血氧这类"有明确医学阈值"的指标，必须走确定性代码：
 * 同一个数字，今天判高危、明天判正常是不可接受的；而且规则命中要能直接落成
 * `alert_event.rule_code`，护士一眼能看出是哪条线被跨过了。</p>
 */
public final class VitalRules {

    private VitalRules() {
    }

    /** 判读结果：ABNORMAL 即视为异常，level=HIGH 的会升级成工单。 */
    public record Judge(String status, String level, String ruleCode, String message) {

        public boolean normal() {
            return "NORMAL".equals(status);
        }

        public boolean critical() {
            return !normal() && "HIGH".equals(level);
        }
    }

    private static final Judge OK = new Judge("NORMAL", "", "", "指标在正常范围内");

    public static Judge judge(String valueJson) {
        JsonNode v = JsonText.parse(valueJson);
        if (v == null) {
            return new Judge("NORMAL", "", "", "未提供可判读的数值");
        }
        String kind = v.path("kind").asText("").toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "BP" -> bloodPressure(v.path("systolic").asInt(0), v.path("diastolic").asInt(0));
            case "GLU", "GLUCOSE" -> glucose(v.path("value").asDouble(0), v.path("unit").asText("mmol/L"));
            case "TEMP" -> temperature(v.path("value").asDouble(0));
            case "SPO2" -> spo2(v.path("value").asInt(0));
            case "WEIGHT" -> new Judge("NORMAL", "", "WEIGHT", "体重已记录：" + v.path("value").asText(""));
            default -> new Judge("NORMAL", "", "", "该类型暂无数值判读规则");
        };
    }

    static Judge bloodPressure(int sys, int dia) {
        if (sys == 0 && dia == 0) {
            return OK;
        }
        if (sys >= 180 || dia >= 110) {
            return new Judge("ABNORMAL", "HIGH", "BP_CRITICAL_180_110",
                    "血压 " + sys + "/" + dia + " mmHg，已达重度升高（≥180/110），1 小时内复测并联系家庭医生");
        }
        if (sys >= 160 || dia >= 100) {
            return new Judge("ABNORMAL", "MEDIUM", "BP_HIGH_160_100",
                    "血压 " + sys + "/" + dia + " mmHg 偏高，安静休息 30 分钟后复测并记录");
        }
        if (sys < 90) {
            return new Judge("ABNORMAL", "HIGH", "BP_LOW_90",
                    "收缩压 " + sys + " mmHg 偏低，注意有无头晕、乏力，避免突然起身");
        }
        if (sys >= 140 || dia >= 90) {
            return new Judge("ABNORMAL", "MEDIUM", "BP_ELEVATED_140_90",
                    "血压 " + sys + "/" + dia + " mmHg 略高于目标，连续 3 天偏高请告知护士");
        }
        return new Judge("NORMAL", "", "BP_OK", "血压 " + sys + "/" + dia + " mmHg，在目标范围内");
    }

    static Judge glucose(double value, String unit) {
        if (value <= 0) {
            return OK;
        }
        double mmol = unit != null && unit.toUpperCase(Locale.ROOT).contains("MG") ? value / 18.0 : value;
        String shown = String.format(Locale.ROOT, "%.1f", mmol);
        if (mmol >= 16.7 || mmol < 3.9) {
            return new Judge("ABNORMAL", "HIGH", "GLU_CRITICAL_16_7_3_9",
                    "血糖 " + shown + " mmol/L 超出安全范围，立即复测；低血糖先口服糖并观察 15 分钟");
        }
        if (mmol >= 11.1) {
            return new Judge("ABNORMAL", "MEDIUM", "GLU_HIGH_11_1",
                    "血糖 " + shown + " mmol/L 明显偏高，核对饮食与服药后复测");
        }
        return new Judge("NORMAL", "", "GLU_OK", "血糖 " + shown + " mmol/L");
    }

    static Judge temperature(double value) {
        if (value <= 0) {
            return OK;
        }
        if (value >= 38.5) {
            return new Judge("ABNORMAL", "HIGH", "TEMP_38_5",
                    "体温 " + value + " ℃，高热，需排查感染并联系家庭医生");
        }
        if (value >= 37.5) {
            return new Judge("ABNORMAL", "MEDIUM", "TEMP_37_5",
                    "体温 " + value + " ℃ 低热，多饮水并每 4 小时复测");
        }
        return new Judge("NORMAL", "", "TEMP_OK", "体温 " + value + " ℃");
    }

    static Judge spo2(int value) {
        if (value <= 0) {
            return OK;
        }
        if (value < 92) {
            return new Judge("ABNORMAL", "HIGH", "SPO2_92",
                    "血氧 " + value + "%，低于 92%，休息后复测，仍低立即就医");
        }
        if (value < 95) {
            return new Judge("ABNORMAL", "MEDIUM", "SPO2_95",
                    "血氧 " + value + "%，偏低，注意呼吸情况并复测");
        }
        return new Judge("NORMAL", "", "SPO2_OK", "血氧 " + value + "%");
    }
}
