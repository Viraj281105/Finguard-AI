package com.finguard.backend.identity.security;

import com.finguard.backend.common.error.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process rate limiter for authentication endpoints (D-04).
 * Throttles rapid failed logins per (IP, email) pair and guards against account discovery/brute-force attacks.
 * Also rate-limits registration and token refresh attempts per IP.
 * State is in-process and valid for a single instance.
 */
@Component
public class AuthRateLimiter {

    public static final int MAX_FAILED_LOGINS = 5;
    public static final long LOGIN_LOCKOUT_SECONDS = 60;
    public static final int MAX_REGISTRATIONS_PER_MINUTE = 1000;
    public static final int MAX_REFRESHES_PER_MINUTE = 1000;

    private static class LoginAttemptState {
        int failedAttempts = 0;
        Instant lockedUntil = Instant.EPOCH;
        Instant lastAttempt = Instant.now();
    }

    private final Map<String, LoginAttemptState> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> registerRequests = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> refreshRequests = new ConcurrentHashMap<>();

    public void checkLoginAllowed(String clientIp, String email) {
        String key = loginKey(clientIp, email);
        LoginAttemptState state = loginAttempts.get(key);
        if (state == null) {
            return;
        }

        Instant now = Instant.now();
        if (state.lockedUntil.isAfter(now)) {
            long retryAfter = Math.max(1, state.lockedUntil.getEpochSecond() - now.getEpochSecond());
            throw new RateLimitExceededException(
                    "Too many failed login attempts. Please try again in " + retryAfter + " seconds.", retryAfter);
        }
    }

    public void recordLoginFailure(String clientIp, String email) {
        String key = loginKey(clientIp, email);
        Instant now = Instant.now();

        loginAttempts.compute(key, (k, state) -> {
            if (state == null) {
                state = new LoginAttemptState();
            }
            // If previous lockout has expired, reset counter
            if (state.lockedUntil.isBefore(now) && now.isAfter(state.lastAttempt.plusSeconds(LOGIN_LOCKOUT_SECONDS))) {
                state.failedAttempts = 0;
            }
            state.failedAttempts++;
            state.lastAttempt = now;

            if (state.failedAttempts >= MAX_FAILED_LOGINS) {
                state.lockedUntil = now.plusSeconds(LOGIN_LOCKOUT_SECONDS);
            }
            return state;
        });
    }

    public void recordLoginSuccess(String clientIp, String email) {
        String key = loginKey(clientIp, email);
        loginAttempts.remove(key);
    }

    public void checkRegisterAllowed(String clientIp) {
        checkRate(registerRequests, "register:" + clientIp, MAX_REGISTRATIONS_PER_MINUTE, 60, "registration");
    }

    public void checkRefreshAllowed(String clientIp) {
        checkRate(refreshRequests, "refresh:" + clientIp, MAX_REFRESHES_PER_MINUTE, 60, "refresh");
    }

    public void reset() {
        loginAttempts.clear();
        registerRequests.clear();
        refreshRequests.clear();
    }

    private void checkRate(Map<String, Deque<Instant>> map, String key, int maxRequests, long windowSeconds, String action) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(windowSeconds);

        Deque<Instant> timestamps = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                Instant oldest = timestamps.peekFirst();
                long retryAfter = oldest != null
                        ? Math.max(1, oldest.plusSeconds(windowSeconds).getEpochSecond() - now.getEpochSecond())
                        : windowSeconds;
                throw new RateLimitExceededException(
                        "Too many " + action + " requests. Please try again later.", retryAfter);
            }
            timestamps.addLast(now);
        }
    }

    private String loginKey(String clientIp, String email) {
        String cleanIp = clientIp != null ? clientIp.trim() : "unknown";
        String cleanEmail = email != null ? email.trim().toLowerCase() : "unknown";
        return cleanIp + ":" + cleanEmail;
    }
}
