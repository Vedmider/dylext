package io.github.vedmider.dylext.adapter.in.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Cross-service correlation: lifts the inbound {@code X-Trace-Id} (or a W3C {@code traceparent}
 * trace-id) and {@code X-Request-Id} into MDC so every Dylext log line — and every outbound federation
 * call to Sestacon/code-memory — carries the same trace. Generated when absent; echoed to the caller.
 * Runs first so later filters (access guard) and handlers log with the trace already set.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    static final String TRACE_ID = "X-Trace-Id";
    static final String REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolveTrace(request);
        String requestId = headerOr(request, REQUEST_ID, UUID.randomUUID().toString());
        MDC.put("traceId", traceId);
        MDC.put("requestId", requestId);
        response.setHeader(TRACE_ID, traceId);
        response.setHeader(REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("requestId");
        }
    }

    private static String resolveTrace(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        return headerOr(request, TRACE_ID, UUID.randomUUID().toString().replace("-", ""));
    }

    private static String headerOr(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
