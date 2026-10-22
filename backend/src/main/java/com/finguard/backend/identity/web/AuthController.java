package com.finguard.backend.identity.web;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.security.JwtProperties;
import com.finguard.backend.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtProperties jwtProperties;
    @org.springframework.beans.factory.annotation.Value("${jwt.cookie-secure:false}")
    private boolean cookieSecure;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        AuthResponse auth = authService.register(request, resolveClientIp(httpRequest));
        setRefreshTokenCookie(httpResponse, auth.refreshToken(), isSecure(httpRequest));
        return ResponseEntity.ok(auth);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        AuthResponse auth = authService.login(request, resolveClientIp(httpRequest));
        setRefreshTokenCookie(httpResponse, auth.refreshToken(), isSecure(httpRequest));
        return ResponseEntity.ok(auth);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) RefreshRequest request,
                                                @CookieValue(name = "refreshToken", required = false) String cookieToken,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        String token = (request != null && request.refreshToken() != null && !request.refreshToken().isBlank())
                ? request.refreshToken()
                : cookieToken;
        AuthResponse auth = authService.refresh(token, resolveClientIp(httpRequest));
        setRefreshTokenCookie(httpResponse, auth.refreshToken(), isSecure(httpRequest));
        return ResponseEntity.ok(auth);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestBody(required = false) LogoutRequest request,
                                                      @CookieValue(name = "refreshToken", required = false) String cookieToken,
                                                      @AuthenticationPrincipal User user,
                                                      HttpServletRequest httpRequest,
                                                      HttpServletResponse httpResponse) {
        String token = (request != null && request.refreshToken() != null && !request.refreshToken().isBlank())
                ? request.refreshToken()
                : cookieToken;
        authService.logout(token, user);
        clearRefreshTokenCookie(httpResponse, isSecure(httpRequest));
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    private boolean isSecure(HttpServletRequest request) {
        return cookieSecure || request.isSecure();
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(jwtProperties.refreshExpiration() / 1000)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
