package com.chala.posapp.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MISS-02: Token-bucket rate limiting on heavy/export endpoints.
 *
 * Limits are keyed by (IP + path prefix) so:
 *  - /api/reports/export  → 10 requests / minute  (Excel export is CPU/IO heavy)
 *  - /api/reports/**      → 60 requests / minute  (report queries)
 *  - /auth/**, /api/auth/** → 20 requests / minute  (brute-force guard)
 *
 * AuthController is mapped at BOTH roots, so both spellings must be limited and must
 * share one bucket — otherwise an attacker just switches to the unlimited spelling.
 *
 * All other paths are not limited by this filter.
 *
 * Buckets are stored in-memory; they are evicted by the JVM GC when the
 * ConcurrentHashMap grows too large — good enough for a single-node POS backend.
 * If you scale to multiple nodes you should replace the map with a Redis-backed
 * Bucket4j ProxyManager.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    // Bucket specs ─────────────────────────────────────────────────────────────
    private static final int EXPORT_PER_MIN  = 10;
    private static final int REPORT_PER_MIN  = 60;
    private static final int AUTH_PER_MIN    = 20;

    // Per-(IP + route-group) bucket map
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String ip   = resolveIp(request);

        // Determine which limit applies (null = no limit)
        Bandwidth bandwidth = resolveBandwidth(path);

        if (bandwidth != null) {
            String bucketKey = ip + "|" + bucketGroup(path);
            Bucket bucket = buckets.computeIfAbsent(bucketKey,
                    k -> Bucket.builder().addLimit(bandwidth).build());

            if (!bucket.tryConsume(1)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"TOO_MANY_REQUESTS\"," +
                    "\"message\":\"Rate limit exceeded. Please slow down.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Bandwidth resolveBandwidth(String path) {
        if (path.contains("/api/reports/export") || path.contains("/api/reports/pdf")) {
            return Bandwidth.classic(EXPORT_PER_MIN,
                    Refill.greedy(EXPORT_PER_MIN, Duration.ofMinutes(1)));
        }
        if (path.startsWith("/api/reports/")) {
            return Bandwidth.classic(REPORT_PER_MIN,
                    Refill.greedy(REPORT_PER_MIN, Duration.ofMinutes(1)));
        }
        if (isAuthPath(path)) {
            return Bandwidth.classic(AUTH_PER_MIN,
                    Refill.greedy(AUTH_PER_MIN, Duration.ofMinutes(1)));
        }
        return null; // no limit
    }

    private String bucketGroup(String path) {
        if (path.contains("/api/reports/export") || path.contains("/api/reports/pdf")) return "export";
        if (path.startsWith("/api/reports/")) return "reports";
        if (isAuthPath(path))                 return "auth";
        return "other";
    }

    /**
     * {@code AuthController} answers on {@code /auth/**} and {@code /api/auth/**} alike, and
     * the POS app happens to call the shorter one. Both map to the same bucket group so the
     * limit counts a client's attempts across both, not once per spelling.
     */
    private boolean isAuthPath(String path) {
        return path.startsWith("/api/auth/") || path.startsWith("/auth/");
    }

    /**
     * Reading X-Forwarded-For here directly would hand every caller a free bucket key: the
     * header is attacker-controlled, so rotating it defeated the limit entirely.
     *
     * <p>{@code server.forward-headers-strategy=native} makes Tomcat's RemoteIpValve resolve
     * the header instead, and it only honours it when the request actually arrived from a
     * trusted internal proxy — so getRemoteAddr() is the real client for proxied traffic and
     * the socket peer for everything else. Both are things the caller cannot choose.
     */
    private String resolveIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
