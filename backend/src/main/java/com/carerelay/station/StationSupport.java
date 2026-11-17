package com.carerelay.station;

import com.carerelay.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 新版工作台公共工具：字段清单（统一把 created_at 格式化成 yyyy-MM-dd HH:mm:ss 供前端直接展示）、
 * 入参取值、角色校验、档案归属校验。
 */
public final class StationSupport {

    private StationSupport() {
    }

    private static final String TS = "DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS created_at";

    public static final String ELDER_COLS = "id, name, age, gender, diag, risk, risk_score, rate, alerts, `day`, "
            + "contact, address, living, emergency_contact, emergency_phone, status, " + TS;

    public static final String PLAN_COLS = "id, elder_id, `type`, content, ai_draft, status, issued_by, " + TS;

    public static final String FOLLOWUP_COLS = "id, elder_id, `type`, scheduled_at, address, note, status, "
            + "reporter, review_status, review_note, " + TS;

    public static final String MESSAGE_COLS = "id, elder_id, sender_role, sender_name, content, " + TS;

    public static final String REVISIT_COLS = "id, elder_id, plan_at, reason, status, doctor, conclusion, " + TS;

    public static final String ALERT_COLS = "id, elder_id, level, reason, status, conclusion, " + TS;

    public static final String RECORD_COLS = "id, elder_id, category, title, detail, `value`, source, " + TS;

    public static final String ANNOUNCEMENT_COLS = "id, title, content, publisher, " + TS;

    /** 取字符串入参，缺省 null；空白串按 null 处理。 */
    public static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    public static String str(Map<String, Object> body, String key, String def) {
        String v = str(body, key);
        return v == null ? def : v;
    }

    /** 取整数入参，缺省 null。 */
    public static Integer integer(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    public static Long longOrNull(Object v) {
        return v == null ? null : longOf(v);
    }

    /** 只允许指定角色，否则 403。 */
    public static StationUser requireRole(String... roles) {
        StationUser user = StationContext.require();
        if (!Arrays.asList(roles).contains(user.role())) {
            throw new BizException(403, "当前角色无权访问该资源");
        }
        return user;
    }

    /** 取老人档案，不存在则 404。 */
    public static Map<String, Object> elder(JdbcTemplate jdbc, Object id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + ELDER_COLS + " FROM elders WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(404, "老人档案不存在");
        }
        return rows.get(0);
    }

    /** 家属 / 老人本人只能访问绑定档案。 */
    public static void assertBound(StationUser user, Map<String, Object> elder) {
        if (user.isFamilyLike()) {
            Long bound = longOrNull(elder.get("id"));
            if (user.elderId() == null || !user.elderId().equals(bound)) {
                throw new BizException(403, "无权访问该档案");
            }
        }
    }
}
