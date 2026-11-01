package com.finguard.backend.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Highest-precedence servlet filter that extracts or generates W3C {@code traceparent} and
 * {@code X-Request-Id} headers, populates SLF4J {@link MDC} for privacy-safe structured logging,
 * echoes correlation headers on the HTTP response, and cleans up {@link MDC} after request completion.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        TraceContext.ParsedTraceparent parsed = TraceContext.parseTraceparent(
                request.getHeader(TraceContext.TRACEPARENT_HEADER)
        );
        String traceId = parsed != null ? parsed.traceId() : TraceContext.newTraceId();
        String spanId = TraceContext.newSpanId();
        String requestId = TraceContext.sanitizeOrNewRequestId(
                request.getHeader(TraceContext.REQUEST_ID_HEADER)
        );

        MDC.put(TraceContext.MDC_TRACE_ID, traceId);
        MDC.put(TraceContext.MDC_SPAN_ID, spanId);
        MDC.put(TraceContext.MDC_REQUEST_ID, requestId);

        response.setHeader(TraceContext.TRACEPARENT_HEADER, TraceContext.formatTraceparent(traceId, spanId));
        response.setHeader(TraceContext.REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.MDC_TRACE_ID);
            MDC.remove(TraceContext.MDC_SPAN_ID);
            MDC.remove(TraceContext.MDC_REQUEST_ID);
            MDC.remove(TraceContext.MDC_USER_HASH);
        }
    }
}

