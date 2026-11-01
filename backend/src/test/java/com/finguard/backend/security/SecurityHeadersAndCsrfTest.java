package com.finguard.backend.security;

import com.finguard.backend.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1-06 Security Headers & CSRF Verification Test (06 §9):
 * Verifies standard defense-in-depth HTTP response headers (HSTS, CSP, X-Frame-Options,
 * X-Content-Type-Options, Referrer-Policy) and validates that cookie-bearing authentication
 * endpoints (/api/auth/refresh, /api/auth/logout) enforce SameSite=Strict and HttpOnly to prevent CSRF.
 */
@DisplayName("S1-06 — Security Headers & Cookie CSRF Defense Gate")
class SecurityHeadersAndCsrfTest extends IntegrationTest {

    @Test
    @DisplayName("Secure HTTPS requests return complete set of security headers including HSTS and CSP")
    void secureRequests_returnCompleteSecurityHeaders() throws Exception {
        mvc.perform(get("/api/auth/unregistered-path")
                        .secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security", containsString("includeSubDomains")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none';"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    @DisplayName("Plain HTTP requests omit HSTS per RFC 6797 while retaining content protection headers")
    void plainHttpRequests_omitHstsPerRfc6797() throws Exception {
        mvc.perform(get("/api/auth/unregistered-path")
                        .secure(false))
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none';"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    @DisplayName("POST /api/auth/login sets HttpOnly, SameSite=Strict, Path=/api/auth refresh cookie for CSRF defense")
    void loginEndpoint_setsStrictCookieForCsrfDefense() throws Exception {
        TestUser user = registerUser();

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.email(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotEmpty();

        String refreshCookie = setCookies.stream()
                .filter(c -> c.startsWith("refreshToken="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected refreshToken cookie in Set-Cookie headers"));

        assertThat(refreshCookie).contains("HttpOnly");
        assertThat(refreshCookie).containsIgnoringCase("SameSite=Strict");
        assertThat(refreshCookie).contains("Path=/api/auth");
    }

    @Test
    @DisplayName("POST /api/auth/logout clears refresh cookie with SameSite=Strict and max-age=0")
    void logoutEndpoint_clearsCookieWithSameSiteStrict() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("refreshToken", "mock-token-to-clear")))
                .andExpect(status().isOk())
                .andReturn();

        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotEmpty();

        String refreshCookie = setCookies.stream()
                .filter(c -> c.startsWith("refreshToken="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected refreshToken cookie clearing in Set-Cookie headers"));

        assertThat(refreshCookie).contains("Max-Age=0");
        assertThat(refreshCookie).contains("HttpOnly");
        assertThat(refreshCookie).containsIgnoringCase("SameSite=Strict");
        assertThat(refreshCookie).contains("Path=/api/auth");
    }
}
