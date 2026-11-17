package com.carerelay.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-04 回归用例：提示词数据里带 LocalDate 时，序列化不能返回 null。
 *
 * <p>背景：周报生成把 LocalDate 放进提示词入参，默认 ObjectMapper 抛异常、
 * JsonText.of 吞成 null，最终表现是"模型 0 毫秒失败、页面静默退回模板"。</p>
 */
class JsonTextTest {

    @Test
    void localDateInPromptDataIsSerialized() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("姓名", "赵德海");
        data.put("周期开始", LocalDate.of(2026, 10, 29));
        data.put("周期结束", LocalDate.of(2026, 11, 4));

        String json = JsonText.of(data);

        assertNotNull(json, "带 LocalDate 的提示词数据必须能序列化，否则模型调用会静默失败");
        assertTrue(json.contains("2026-10-29"), "日期应写成 ISO 文本: " + json);
        assertTrue(json.contains("赵德海"));
    }

    @Test
    void stringsReadsOnlyFilledValues() {
        String json = "[{\"name\":\"胸闷\"},{\"name\":\"\"},{\"name\":null}]";
        assertEquals(List.of("胸闷"), JsonText.strings(json, "name"));
    }

    @Test
    void ageFromBirthDate() {
        int age = JsonText.age(LocalDate.now().minusYears(81).minusDays(1));
        assertEquals(81, age);
    }

    @Test
    void parseInvalidJsonReturnsNull() {
        assertEquals(null, JsonText.parse("not-json"));
    }
}
