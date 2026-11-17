package com.carerelay.auth;

import com.carerelay.common.BizException;

/**
 * 角色边界：不信任前端的隐藏按钮，服务端按"方法 + 路径"二次校验。
 *
 * <p>规则刻意写得窄——只拦"会改变照护结论"的写操作与"会改变系统行为"的设置：
 * 建档、抽取、人工填写、评分、生成/签发计划、关闭预警、处置工单、发起随访、生成周报；系统设置仅管理员。
 * 家属只读今日任务与周报、只提交打卡，这些路径不在拦截范围内。</p>
 */
public final class RoleRules {

    private RoleRules() {
    }

    /** 医护口径：管理员、社区护士、家庭医生。 */
    public static boolean isClinician(CurrentUser user) {
        if (user == null) {
            return false;
        }
        String role = user.role();
        return "ADMIN".equals(role) || "NURSE".equals(role) || "DOCTOR".equals(role);
    }

    /** 护士口径：社区护士与管理员（护士是计划的签发人）。 */
    public static boolean isNurse(CurrentUser user) {
        return user != null && ("NURSE".equals(user.role()) || "ADMIN".equals(user.role()));
    }

    /** 医生口径：家庭医生与管理员（医生负责判断与升级）。 */
    public static boolean isDoctor(CurrentUser user) {
        return user != null && ("DOCTOR".equals(user.role()) || "ADMIN".equals(user.role()));
    }

    /** 计划签发只能由护士做：医生可以看、可以提意见，但不能签发。 */
    public static void requirePlanIssuer(CurrentUser user) {
        if (!isNurse(user)) {
            throw new BizException(403, "计划只能由社区护士签发；家庭医生可以留言会诊意见");
        }
    }

    /** 高危预警必须由医生确认关闭，护士关不掉。 */
    public static void requireHighAlertCloser(CurrentUser user, String level) {
        if ("HIGH".equalsIgnoreCase(level) && !isDoctor(user)) {
            throw new BizException(403, "高危预警需家庭医生确认后才能关闭");
        }
    }
    /** 管理员口径：只有 ADMIN。 */
    public static boolean isAdmin(CurrentUser user) {
        return user != null && "ADMIN".equals(user.role());
    }

    /** 系统设置与 AI 调用记录：仅管理员。 */
    public static boolean requiresAdmin(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String p = normalize(path);
        return p.startsWith("/api/v1/settings") || p.startsWith("/api/v1/ai/records");
    }

    /**
     * 需要医护身份的写操作。
     *
     * @param method HTTP 方法
     * @param path   请求路径（含 query 也可以，会被截断）
     * @return true 表示该请求仅限医护
     */
    public static boolean requiresClinician(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String p = normalize(path);

        if ("GET".equalsIgnoreCase(method)) {
            return p.startsWith("/api/v1/reports/overview");
        }
        // 写操作一律按同一份清单拦：PUT 用于"人工填写/修订出院档案"，漏掉它等于给家属开了后门。
        boolean write = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
        if (!write) {
            return false;
        }
        return p.equals("/api/v1/elders")
                || p.startsWith("/api/v1/elders/")
                || p.startsWith("/api/v1/discharge")
                || p.startsWith("/api/v1/risk/")
                || p.startsWith("/api/v1/plans")
                || p.startsWith("/api/v1/alerts")
                || p.startsWith("/api/v1/tickets")
                || p.startsWith("/api/v1/followups")
                || p.startsWith("/api/v1/reports")
                || p.startsWith("/api/v1/tasks")
                || p.startsWith("/api/v1/admissions");
    }

    /** 拦截入口：不满足角色要求时抛 403。 */
    public static void check(String method, String path, CurrentUser user) {
        if (requiresAdmin(method, path)) {
            if (!isAdmin(user)) {
                throw new BizException(403, "仅管理员可访问（系统设置 / AI 调用记录）");
            }
            return;
        }
        if (!requiresClinician(method, path)) {
            return;
        }
        if (isClinician(user)) {
            return;
        }
        throw new BizException(403, "该操作仅限医护人员");
    }

    private static String normalize(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        return p;
    }
}
