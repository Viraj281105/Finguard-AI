package com.finguard.backend.common.observability;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace-Context ({@code traceparent}) and privacy-safe correlation helpers (S1-01).
 *
 * <p>Propagates {@code trace_id}, {@code span_id}, {@code request_id}, and a hashed user correlation
 * token ({@code user_hash}) across HTTP filters, async worker threads, and outbound service calls
 * without ever exposing PII (email, raw user ID) or transaction narration text.
 */
public final class TraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_USER_HASH = "user_hash";

    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$"
    );
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_SPAN_ID = "0000000000000000";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private TraceContext() {
    }

    public record ParsedTraceparent(String traceId, String parentSpanId, String flags) {
    }

    /**
     * Parses a W3C {@code traceparent} header ({@code 00-<32-hex-trace-id>-<16-hex-span-id>-<2-hex-flags>}).
     * Returns {@code null} when absent or invalid per W3C Trace Context Level 1.
     */
    public static ParsedTraceparent parseTraceparent(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher matcher = TRACEPARENT_PATTERN.matcher(header.trim().toLowerCase());
        if (!matcher.matches()) {
            return null;
        }
        String traceId = matcher.group(1);
        String spanId = matcher.group(2);
        String flags = matcher.group(3);
        if (ALL_ZERO_TRACE_ID.equals(traceId) || ALL_ZERO_SPAN_ID.equals(spanId)) {
            return null;
        }
        return new ParsedTraceparent(traceId, spanId, flags);
    }

    public static String newTraceId() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long hi = rng.nextLong();
        long lo = rng.nextLong();
        if (hi == 0L && lo == 0L) {
            lo = 1L;
        }
        return String.format("%016x%016x", hi, lo);
    }

    public static String newSpanId() {
        long val = ThreadLocalRandom.current().nextLong();
        if (val == 0L) {
            val = 1L;
        }
        return String.format("%016x", val);
    }

    public static String sanitizeOrNewRequestId(String incoming) {
        if (incoming != null && SAFE_REQUEST_ID.matcher(incoming.trim()).matches()) {
            return incoming.trim();
        }
        return UUID.randomUUID().toString();
    }

    public static String formatTraceparent(String traceId, String spanId) {
        String resolvedTraceId = (traceId != null && traceId.length() == 32) ? traceId : newTraceId();
        String resolvedSpanId = (spanId != null && spanId.length() == 16) ? spanId : newSpanId();
        return "00-" + resolvedTraceId + "-" + resolvedSpanId + "-01";
    }

    /**
     * Returns the current W3C {@code traceparent} built from the thread's {@link MDC} {@code trace_id}
     * and a fresh child {@code span_id} (or existing {@code span_id}) for outbound HTTP requests.
     */
    public static String currentOutboundTraceparent() {
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId == null || traceId.length() != 32) {
            return null;
        }
        return formatTraceparent(traceId, newSpanId());
    }

    public static String currentRequestId() {
        return MDC.get(MDC_REQUEST_ID);
    }

    /**
     * Computes a privacy-safe 12-hex-character SHA-256 prefix of the internal user ID.
     * Never logs or emits raw email addresses or full user UUIDs.
     */
    public static String hashUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "anon";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

