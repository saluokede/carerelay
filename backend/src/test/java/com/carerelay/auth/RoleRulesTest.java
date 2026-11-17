package com.carerelay.auth;

import com.carerelay.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色边界测试：家属能做什么、不能做什么，必须由服务端说了算。
 */
class RoleRulesTest {

    private static final CurrentUser FAMILY = new CurrentUser(4L, "FAMILY", null);
    private static final CurrentUser NURSE = new CurrentUser(2L, "NURSE", null);
    private static final CurrentUser DOCTOR = new CurrentUser(3L, "DOCTOR", null);
    private static final CurrentUser ADMIN = new CurrentUser(1L, "ADMIN", null);

    @Test
    void familyCannotIssuePlan() {
        BizException ex = assertThrows(BizException.class,
                () -> RoleRules.check("POST", "/api/v1/plans/3/issue", FAMILY));
        assertEquals(403, ex.code());
    }

    @Test
    void familyCannotCloseAlertOrHandleTicket() {
        assertThrows(BizException.class, () -> RoleRules.check("POST", "/api/v1/alerts/8/close", FAMILY));
        assertThrows(BizException.class, () -> RoleRules.check("POST", "/api/v1/tickets/7/handle", FAMILY));
    }

    @Test
    void familyCannotReadNurseDashboard() {
        assertThrows(BizException.class, () -> RoleRules.check("GET", "/api/v1/reports/overview", FAMILY));
    }

    @Test
    void familyCanCheckInAndReadReports() {
        assertDoesNotThrow(() -> RoleRules.check("POST", "/api/v1/checkins", FAMILY));
        assertDoesNotThrow(() -> RoleRules.check("GET", "/api/v1/checkins/today", FAMILY));
        assertDoesNotThrow(() -> RoleRules.check("GET", "/api/v1/reports?elderId=1", FAMILY));
        assertDoesNotThrow(() -> RoleRules.check("GET", "/api/v1/elders", FAMILY));
    }

    @Test
    void cliniciansPassEveryGuardedPath() {
        String[] writes = {
                "/api/v1/elders",
                "/api/v1/discharge/extract",
                "/api/v1/risk/assess",
                "/api/v1/plans/generate",
                "/api/v1/plans/3/issue",
                "/api/v1/alerts/8/close",
                "/api/v1/tickets/7/handle",
                "/api/v1/followups/2/messages",
                "/api/v1/reports/weekly"
        };
        for (String path : writes) {
            assertDoesNotThrow(() -> RoleRules.check("POST", path, NURSE), path);
            assertDoesNotThrow(() -> RoleRules.check("POST", path, DOCTOR), path);
            assertDoesNotThrow(() -> RoleRules.check("POST", path, ADMIN), path);
        }
        assertDoesNotThrow(() -> RoleRules.check("GET", "/api/v1/reports/overview", NURSE));
    }

    @Test
    void queryStringDoesNotBypassRules() {
        // 加 ? 后缀不能把受限路径变成白名单
        assertTrue(RoleRules.requiresClinician("POST", "/api/v1/plans/generate?x=1"));
        assertThrows(BizException.class, () -> RoleRules.check("POST", "/api/v1/plans/generate?x=1", FAMILY));
    }

    @Test
    void readOnlyRoutesAreOpen() {
        assertFalse(RoleRules.requiresClinician("GET", "/api/v1/elders"));
        assertFalse(RoleRules.requiresClinician("GET", "/api/v1/alerts"));
        assertFalse(RoleRules.requiresClinician("POST", "/api/v1/checkins"));
        assertFalse(RoleRules.requiresClinician("GET", "/api/v1/followups/due"));
    }

    @Test
    void unknownRoleIsTreatedAsNonClinician() {
        CurrentUser stranger = new CurrentUser(99L, "GUEST", null);
        assertThrows(BizException.class, () -> RoleRules.check("POST", "/api/v1/risk/assess", stranger));
    }

    @Test
    void onlyNurseIssuesPlan() {
        assertDoesNotThrow(() -> RoleRules.requirePlanIssuer(NURSE));
        assertDoesNotThrow(() -> RoleRules.requirePlanIssuer(ADMIN));
        BizException doctor = assertThrows(BizException.class, () -> RoleRules.requirePlanIssuer(DOCTOR));
        assertEquals(403, doctor.code());
        assertThrows(BizException.class, () -> RoleRules.requirePlanIssuer(FAMILY));
    }

    @Test
    void highAlertOnlyDoctorCanClose() {
        // 高危：护士关不掉，医生/管理员可以
        BizException nurse = assertThrows(BizException.class,
                () -> RoleRules.requireHighAlertCloser(NURSE, "HIGH"));
        assertEquals(403, nurse.code());
        assertDoesNotThrow(() -> RoleRules.requireHighAlertCloser(DOCTOR, "HIGH"));
        assertDoesNotThrow(() -> RoleRules.requireHighAlertCloser(ADMIN, "HIGH"));
        // 中低危：护士自己就能关
        assertDoesNotThrow(() -> RoleRules.requireHighAlertCloser(NURSE, "MEDIUM"));
        assertDoesNotThrow(() -> RoleRules.requireHighAlertCloser(NURSE, "LOW"));
        assertDoesNotThrow(() -> RoleRules.requireHighAlertCloser(NURSE, null));
    }

    @Test
    void nurseAndDoctorAreDifferentKindsOfClinician() {
        assertTrue(RoleRules.isClinician(NURSE));
        assertTrue(RoleRules.isClinician(DOCTOR));
        assertTrue(RoleRules.isNurse(NURSE));
        assertFalse(RoleRules.isNurse(DOCTOR));
        assertTrue(RoleRules.isDoctor(DOCTOR));
        assertFalse(RoleRules.isDoctor(NURSE));
        assertFalse(RoleRules.isClinician(FAMILY));
    }
}
