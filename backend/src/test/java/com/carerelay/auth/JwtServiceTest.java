package com.carerelay.auth;

import com.carerelay.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            new JwtProperties("unit-test-secret-0123456789-0123456789", 2));

    @Test
    void issueThenParseKeepsIdentity() {
        String token = jwtService.issue(7L, "ORG_ADMIN", 3L);

        CurrentUser user = jwtService.parse(token);

        assertEquals(7L, user.id());
        assertEquals("ORG_ADMIN", user.role());
        assertEquals(3L, user.orgId());
        assertEquals(2, jwtService.ttlHours());
    }

    @Test
    void publicUserHasNoOrg() {
        CurrentUser user = jwtService.parse(jwtService.issue(9L, "PUBLIC", null));

        assertNull(user.orgId());
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issue(7L, "ORG_ADMIN", 3L);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThrows(BizException.class, () -> jwtService.parse(tampered));
    }

    @Test
    void shortSecretFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService(new JwtProperties("too-short", 2)));
    }
}
