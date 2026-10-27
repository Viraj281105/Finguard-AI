package com.finguard.backend.identity.security;

import com.finguard.backend.common.error.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-process rate limiter for authentication endpoints (D-04, R-3).
 * Throttles rapid failed logins per (IP, email) pair, per-account, and per-IP to guard against
 * distributed brute-force attacks and credential stuffing across accounts.
 * State is in-process and memory usage is bounded via LRU eviction to prevent memory DoS.
 */
@Component
public class AuthRateLimiter {

    public static final int MAX_FAILED_LOGINS = 5;
    public static final int MAX_FAILED_LOGINS_PER_IP = 20;
    public static final int MAX_FAILED_LOGINS_PER_ACCOUNT = 15;
    public static final long LOGIN_LOCKOUT_SECONDS = 60;
    public static final int MAX_REGISTRATIONS_PER_MINUTE = 1000;
    public static final int MAX_REFRESHES_PER_MINUTE = 1000;
    public static final int MAX_MAP_ENTRIES = 5000;

    private static class LoginAttemptState {
        int failedAttempts = 0;
        Instant lockedUntil = Instant.EPOCH;
        Instant lastAttempt = Instant.now();
    }

    private static <K, V> Map<K, V> createBoundedMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    private final Map<String, LoginAttemptState> loginAttempts = createBoundedMap(MAX_MAP_ENTRIES);
    private final Map<String, Deque<Instant>> registerRequests = createBoundedMap(MAX_MAP_ENTRIES);
    private final Map<String, Deque<Instant>> refreshRequests = createBoundedMap(MAX_MAP_ENTRIES);

    public void checkLoginAllowed(String clientIp, String email) {
        Instant now = Instant.now();
        // 1. Check pair (IP, email)
        checkLockout(loginAttempts.get(loginKey(clientIp, email)), now);
        // 2. Check account
        checkLockout(loginAttempts.get(accountKey(email)), now);
        // 3. Check IP
        checkLockout(loginAttempts.get(ipKey(clientIp)), now);
    }

    private void checkLockout(LoginAttemptState state, Instant now) {
        if (state == null) {
            return;
        }
        if (state.lockedUntil.isAfter(now)) {
            long retryAfter = Math.max(1, state.lockedUntil.getEpochSecond() - now.getEpochSecond());
            throw new RateLimitExceededException(
                    "Too many failed login attempts. Please try again in " + retryAfter + " seconds.", retryAfter);
        }
    }

    public void recordLoginFailure(String clientIp, String email) {
        Instant now = Instant.now();
        recordFailureOnKey(loginKey(clientIp, email), MAX_FAILED_LOGINS, now);
        recordFailureOnKey(accountKey(email), MAX_FAILED_LOGINS_PER_ACCOUNT, now);
        recordFailureOnKey(ipKey(clientIp), MAX_FAILED_LOGINS_PER_IP, now);
    }

    private void recordFailureOnKey(String key, int maxAllowed, Instant now) {
        synchronized (loginAttempts) {
            LoginAttemptState state = loginAttempts.computeIfAbsent(key, k -> new LoginAttemptState());
            if (state.lockedUntil.isBefore(now) && now.isAfter(state.lastAttempt.plusSeconds(LOGIN_LOCKOUT_SECONDS))) {
                state.failedAttempts = 0;
            }
            state.failedAttempts++;
            state.lastAttempt = now;
            if (state.failedAttempts >= maxAllowed) {
                state.lockedUntil = now.plusSeconds(LOGIN_LOCKOUT_SECONDS);
            }
        }
    }

    public void recordLoginSuccess(String clientIp, String email) {
        loginAttempts.remove(loginKey(clientIp, email));
        loginAttempts.remove(accountKey(email));
    }

    public void checkRegisterAllowed(String clientIp) {
        checkRate(registerRequests, "register:" + clean(clientIp), MAX_REGISTRATIONS_PER_MINUTE, 60, "registration");
    }

    public void checkRefreshAllowed(String clientIp) {
        checkRate(refreshRequests, "refresh:" + clean(clientIp), MAX_REFRESHES_PER_MINUTE, 60, "refresh");
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
        return "pair:" + clean(clientIp) + ":" + clean(email);
    }

    private String accountKey(String email) {
        return "account:" + clean(email);
    }

    private String ipKey(String clientIp) {
        return "ip:" + clean(clientIp);
    }

    private String clean(String s) {
        return s != null ? s.trim().toLowerCase() : "unknown";
    }
}
