package com.finguard.backend.identity;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.security.JwtProperties;
import com.finguard.backend.identity.security.JwtService;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";

    private final JwtService jwt = new JwtService(new JwtProperties(SECRET, 60_000, 120_000));
    private final User user = userWithEmail("a@example.com");

    @Test
    void accessTokenRoundTripsTheSubject() {
        String token = jwt.generateAccessToken(user);
        assertThat(jwt.extractUsername(token, JwtService.ACCESS)).isEqualTo("a@example.com");
    }

    @Test
    void refreshTokenIsRejectedWhereAnAccessTokenIsExpected() {
        String refresh = jwt.generateRefreshToken(user);
        assertThatThrownBy(() -> jwt.extractUsername(refresh, JwtService.ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void accessTokenIsRejectedWhereARefreshTokenIsExpected() {
        String access = jwt.generateAccessToken(user);
        assertThatThrownBy(() -> jwt.extractUsername(access, JwtService.REFRESH)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expired = new JwtService(new JwtProperties(SECRET, -1_000, -1_000));
        String token = expired.generateAccessToken(user);
        assertThatThrownBy(() -> jwt.extractUsername(token, JwtService.ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtService other = new JwtService(new JwtProperties("another-secret-0123456789abcdef0123456789abcdef", 60_000, 60_000));
        String token = other.generateAccessToken(user);
        assertThatThrownBy(() -> jwt.extractUsername(token, JwtService.ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> jwt.extractUsername("not-a-jwt", JwtService.ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void everyTokenIsUnique() {
        assertThat(jwt.generateAccessToken(user)).isNotEqualTo(jwt.generateAccessToken(user));
    }

    @Test
    void missingOrShortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtProperties("", 1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(null, 1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties("too-short", 1, 1)).isInstanceOf(IllegalStateException.class);
    }

    private static User userWithEmail(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }
}
