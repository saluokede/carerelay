package com.carerelay.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 文本列的小工具：库里存 String，读出时按需转 JsonNode，不做全局 TypeHandler。 */
public final class JsonText {

    /**
     * 必须注册 JavaTimeModule：给模型看的提示词数据里有 LocalDate，
     * 默认 ObjectMapper 遇到 java.time 会直接抛异常，而这里又把异常吞成 null，
     * 结果就是"提示词静默变空、模型调用 0 毫秒失败、页面悄悄退回模板"。
     */
    private static final ObjectMapper OM = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonText() {
    }

    public static String of(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    /** 任意对象（Map/List/record）转 JSON 文本。 */
    public static String of(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OM.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** JsonNode 对象转有序 Map，便于在视图层拼装。 */
    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        try {
            return OM.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取数组里的某个文本字段，全部拼成 List；解析不了就返回空列表。 */
    public static List<String> strings(String json, String field) {
        JsonNode node = parse(json);
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            JsonNode value = field == null ? item : item.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                out.add(value.asText());
            }
        }
        return out;
    }

    public static int age(LocalDate birthDate) {
        if (birthDate == null) {
            return 0;
        }
        return Period.between(birthDate, LocalDate.now()).getYears();
    }
}
