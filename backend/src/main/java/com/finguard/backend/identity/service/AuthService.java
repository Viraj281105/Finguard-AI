package com.finguard.backend.identity.service;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.RefreshToken;
import com.finguard.backend.identity.domain.RefreshTokenRepository;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.domain.UserRepository;
import com.finguard.backend.identity.security.AuthRateLimiter;
import com.finguard.backend.identity.security.JwtService;
import com.finguard.backend.identity.web.AuthResponse;
import com.finguard.backend.identity.web.LoginRequest;
import com.finguard.backend.identity.web.RegisterRequest;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final AuthRateLimiter authRateLimiter;

    @Transactional
    public AuthResponse register(RegisterRequest request, String clientIp) {
        authRateLimiter.checkRegisterAllowed(clientIp);

        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("Email already registered");
        }

        User user = new User();
        user.setName(request.name().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        return issueTokens(user);
    }

    public AuthResponse register(RegisterRequest request) {
        return register(request, "127.0.0.1");
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = request.email().trim().toLowerCase();
        authRateLimiter.checkLoginAllowed(clientIp, email);

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException e) {
            authRateLimiter.recordLoginFailure(clientIp, email);
            throw ApiException.unauthorized("Invalid email or password");
        }

        authRateLimiter.recordLoginSuccess(clientIp, email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
        return issueTokens(user);
    }

    public AuthResponse login(LoginRequest request) {
        return login(request, "127.0.0.1");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String refreshToken, String clientIp) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("Refresh token is required");
        }
        authRateLimiter.checkRefreshAllowed(clientIp);

        try {
            String email = jwtService.extractUsername(refreshToken, JwtService.REFRESH);
            String tokenHash = jwtService.hashToken(refreshToken);

            RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                    .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

            if (stored.isRevoked()) {
                // Reuse detection: an already rotated or revoked token was replayed.
                // Revoke the entire family of sessions for this user.
                refreshTokenRepository.revokeAllForUserId(stored.getUser().getId());
                log.warn("Revoked refresh token replayed for user id={}; revoked all active sessions", stored.getUser().getId());
                throw ApiException.unauthorized("Invalid refresh token");
            }

            if (stored.getExpiresAt().isBefore(Instant.now())) {
                throw ApiException.unauthorized("Refresh token expired");
            }

            User user = stored.getUser();
            if (!user.getEmail().equalsIgnoreCase(email)) {
                throw ApiException.unauthorized("Invalid refresh token");
            }

            // Rotate: revoke the current token and issue a new pair
            String newAccessToken = jwtService.generateAccessToken(user);
            String newRefreshToken = jwtService.generateRefreshToken(user);
            String newHash = jwtService.hashToken(newRefreshToken);

            stored.setRevoked(true);
            stored.setReplacedByTokenHash(newHash);
            refreshTokenRepository.save(stored);

            Instant expiresAt = Instant.now().plusMillis(jwtService.getProperties().refreshExpiration());
            refreshTokenRepository.save(new RefreshToken(user, newHash, expiresAt));

            return new AuthResponse(newAccessToken, newRefreshToken, user.getEmail(), user.getName());
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("Invalid refresh token");
        }
    }

    public AuthResponse refresh(String refreshToken) {
        return refresh(refreshToken, "127.0.0.1");
    }

    @Transactional
    public void logout(String refreshToken, User user) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                String tokenHash = jwtService.hashToken(refreshToken);
                refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(rt -> {
                    rt.setRevoked(true);
                    refreshTokenRepository.save(rt);
                });
            } catch (Exception e) {
                log.debug("Could not hash refresh token on logout: {}", e.getMessage());
            }
        }
        if (user != null) {
            refreshTokenRepository.revokeAllForUserId(user.getId());
        }
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        String tokenHash = jwtService.hashToken(refreshToken);

        Instant expiresAt = Instant.now().plusMillis(jwtService.getProperties().refreshExpiration());
        refreshTokenRepository.save(new RefreshToken(user, tokenHash, expiresAt));

        return new AuthResponse(accessToken, refreshToken, user.getEmail(), user.getName());
    }
}
