package com.finguard.backend.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.identity.domain.RefreshToken;
import com.finguard.backend.identity.domain.RefreshTokenRepository;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.security.AuthRateLimiter;
import com.finguard.backend.identity.security.JwtProperties;
import com.finguard.backend.identity.security.JwtService;
import com.finguard.backend.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthApiTest extends IntegrationTest {

    @Autowired
    private AuthRateLimiter rateLimiter;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
    }

    private String email() {
        return "auth-" + UUID.randomUUID() + "@example.com";
    }

    private String credentials(String email, String password) throws Exception {
        return json(Map.of("email", email, "password", password));
    }

    @Test
    void registerReturnsTokensAndProfileAndSetsCookie() throws Exception {
        String email = email();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Asha", "email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Asha"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refreshToken=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")));
    }

    @Test
    void registeringTheSameEmailTwiceIsAConflict() throws Exception {
        TestUser user = registerUser();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Dup", "email", user.email(), "password", PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void registerNormalizesEmailAndEnforcesCaseInsensitiveUniqueness() throws Exception {
        String raw = "User." + UUID.randomUUID() + "@Example.COM";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Case Test", "email", raw, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(raw.trim().toLowerCase()));

        // Case-variant duplicate must be rejected with 409
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Case Test 2", "email", raw.toLowerCase(), "password", PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));

        // Login with uppercase variant works
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(raw.toUpperCase(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()));
    }

    @Test
    void registerEnforcesPasswordMinimumOfTenCharacters() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Short", "email", email(), "password", "123456789"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password", containsString("at least 10 characters")));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ExactTen", "email", email(), "password", "1234567890"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()));
    }

    @Test
    void registerValidatesNameEmailAndPassword() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "", "email", "not-an-email", "password", "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasKey("name")))
                .andExpect(jsonPath("$.fieldErrors", hasKey("email")))
                .andExpect(jsonPath("$.fieldErrors", hasKey("password")));
    }

    @Test
    void loginSucceedsWithCorrectCredentials() throws Exception {
        TestUser user = registerUser();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(user.email(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailIsUnauthorizedWithoutLeakingWhich() throws Exception {
        TestUser user = registerUser();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(user.email(), "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void rateLimitingThrottlesRapidWrongPasswordLogins() throws Exception {
        TestUser user = registerUser();
        String creds = credentials(user.email(), "wrong-pass-12345");

        // 5 wrong attempts produce 401
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .with(request -> { request.setRemoteAddr("198.51.100.1"); return request; })
                            .content(creds))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt is throttled with 429 and Retry-After
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(request -> { request.setRemoteAddr("198.51.100.1"); return request; })
                        .content(creds))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.message", containsString("Too many failed login attempts")));

        // Another IP is not locked out
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(request -> { request.setRemoteAddr("198.51.100.2"); return request; })
                        .content(credentials(user.email(), PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointsRequireAValidAccessToken() throws Exception {
        mvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void errorBodiesNeverLeakInternals() throws Exception {
        mvc.perform(get("/api/transactions"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$", not(hasKey("trace"))));
    }

    @Test
    void validAccessTokenIsAccepted() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, get("/api/transactions"))).andExpect(status().isOk());
    }

    @Test
    void refreshTokenCannotBeUsedToCallTheApi() throws Exception {
        TestUser user = registerUser();
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + user.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        TestUser user = registerUser();
        JwtService expiredIssuer = new JwtService(
                new JwtProperties("test-only-secret-0123456789abcdef0123456789abcdef", -1_000, -1_000));
        User principal = new User();
        principal.setEmail(user.email());
        mvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + expiredIssuer.generateAccessToken(principal)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesRefreshTokenAndInvalidatesOldOne() throws Exception {
        TestUser user = registerUser();

        // 1st refresh: succeeds, yields new refresh token in cookie
        var refreshResult = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        jakarta.servlet.http.Cookie refreshCookie = refreshResult.getResponse().getCookie("refreshToken");
        org.assertj.core.api.Assertions.assertThat(refreshCookie).isNotNull();
        String newRefreshToken = refreshCookie.getValue();

        // 2nd refresh with the old token: old token is rejected
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized());

        // Within grace window (30s, O-03), concurrent tab races do not revoke the replacement token
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", newRefreshToken))))
                .andExpect(status().isOk());

        // Outside grace window: replaying the old token revokes the entire family
        RefreshToken rep = refreshTokenRepository.findByTokenHash(jwtService.hashToken(newRefreshToken)).orElseThrow();
        rep.setCreatedAt(Instant.now().minusSeconds(31));
        refreshTokenRepository.save(rep);

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized());

        // Now the entire session family is revoked
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", newRefreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshViaCookieWorksWithoutBody() throws Exception {
        TestUser user = registerUser();
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", user.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        TestUser user = registerUser();

        // Logout
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Calling refresh with the revoked token fails
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsAnAccessToken() throws Exception {
        TestUser user = registerUser();
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.accessToken()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
