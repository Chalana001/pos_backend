package com.chala.posapp.config;

import com.chala.posapp.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Collapses accidentally double-submitted write requests.
 *
 * <p><b>The problem.</b> Cashiers on Windows habitually double-click buttons.
 * Two clicks 100 ms apart send two identical POSTs. Every write path in this
 * codebase guards itself with a check-then-insert
 * ({@code findBy...().ifPresent(throw)} then {@code save()}), and both requests
 * run their SELECT before either runs its INSERT — so both pass the guard and
 * two rows are written. That produced duplicate open shifts, duplicate orders,
 * duplicate GRNs and so on. No amount of tightening the service-layer check
 * closes that window, because the window is between the check and the write.
 *
 * <p><b>What this filter does.</b> For state-changing methods it derives a key
 * from (tenant, user, method, URI, query string, request body) and lets only the
 * first request through. A second request carrying the same key while the first
 * is still running <em>waits</em> for it and is then served the first request's
 * recorded response — byte for byte, same status, same body. A second request
 * arriving after the first finished is served the recorded response directly,
 * for as long as the replay window lasts. Either way the caller sees exactly one
 * receipt, one shift, one order; the UI behaves as if the user clicked once.
 *
 * <p><b>Failures are never replayed.</b> If the first attempt returned anything
 * other than 2xx the key is dropped immediately, so a genuine retry after a real
 * error still reaches the controller.
 *
 * <p><b>Two windows.</b> A client that sends an explicit {@code Idempotency-Key}
 * header gets a long window ({@code explicit-key-window-seconds}) keyed on that
 * header — the caller is telling us "these two requests are the same action".
 * Everything else falls back to a body fingerprint with a deliberately short
 * window ({@code window-seconds}), long enough to swallow a double- or
 * triple-click but short enough that two genuinely separate identical actions
 * (the same cash drop entered twice, the same item sold twice) are not merged.
 * Keep it small; widening it trades a real class of correctness bug for this one.
 *
 * <p>State is held in-memory, which is correct for the single-node POS backend.
 * Behind more than one backend instance this degrades to per-node protection and
 * the entries would need to move to Redis.
 */
@Component
public class DuplicateRequestFilter extends OncePerRequestFilter {

    /** Only these methods can create duplicate rows; GET/HEAD/OPTIONS are untouched. */
    private static final Set<String> GUARDED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * Paths that must never be collapsed. Login is excluded because replaying a
     * cached token response across two deliberate attempts is confusing, and
     * because it is already rate-limited.
     */
    private static final Set<String> EXCLUDED_PATH_PREFIXES = Set.of(
            "/auth/login",
            "/api/auth/login"
    );

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "X-Duplicate-Request";

    /** Bodies/responses larger than this are passed through without dedupe. */
    private static final int MAX_CACHED_BYTES = 512 * 1024;

    /** How long a duplicate waits for the in-flight original before giving up. */
    private static final long WAIT_FOR_ORIGINAL_SECONDS = 30;

    private final boolean enabled;
    private final Cache<String, Entry> fingerprintEntries;
    private final Cache<String, Entry> explicitKeyEntries;

    public DuplicateRequestFilter(
            @Value("${pos.duplicate-request.enabled:true}") boolean enabled,
            @Value("${pos.duplicate-request.window-seconds:5}") long windowSeconds,
            @Value("${pos.duplicate-request.explicit-key-window-seconds:600}") long explicitKeyWindowSeconds
    ) {
        this.enabled = enabled;
        this.fingerprintEntries = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(10_000)
                .build();
        this.explicitKeyEntries = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(explicitKeyWindowSeconds))
                .maximumSize(10_000)
                .build();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        if (!enabled || !isGuarded(request)) {
            chain.doFilter(request, response);
            return;
        }

        // The body has to be read here to fingerprint it, so it is buffered and
        // replayed to the controller from memory.
        byte[] body = request.getInputStream().readAllBytes();
        HttpServletRequest buffered = new BufferedBodyRequest(request, body);

        if (body.length > MAX_CACHED_BYTES) {
            chain.doFilter(buffered, response);
            return;
        }

        String explicitKey = request.getHeader(IDEMPOTENCY_HEADER);
        boolean explicit = explicitKey != null && !explicitKey.isBlank();
        Cache<String, Entry> entries = explicit ? explicitKeyEntries : fingerprintEntries;
        String key = buildKey(request, body, explicit ? explicitKey.trim() : null);

        Entry mine = new Entry();
        Entry original = entries.asMap().putIfAbsent(key, mine);

        if (original == null) {
            runAsOriginal(buffered, response, chain, entries, key, mine);
        } else {
            serveAsDuplicate(buffered, response, chain, original);
        }
    }

    /** First request through: run it for real and record the response for replay. */
    private void runAsOriginal(HttpServletRequest request,
                               HttpServletResponse response,
                               FilterChain chain,
                               Cache<String, Entry> entries,
                               String key,
                               Entry mine) throws ServletException, IOException {

        ContentCachingResponseWrapper recorder = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, recorder);

            int status = recorder.getStatus();
            byte[] responseBody = recorder.getContentAsByteArray();

            // Only successful responses are worth replaying. Anything else must
            // stay retryable, so the key is released.
            if (status >= 200 && status < 300 && responseBody.length <= MAX_CACHED_BYTES) {
                mine.status = status;
                mine.contentType = recorder.getContentType();
                mine.body = responseBody;
                mine.replayable = true;
            }
        } finally {
            if (!mine.replayable) {
                entries.invalidate(key);
            }
            // Released before copyBodyToResponse so a waiting duplicate is
            // unblocked even if flushing the original response fails.
            mine.done.countDown();
            recorder.copyBodyToResponse();
        }
    }

    /**
     * Second (and later) request with the same key: wait for the original if it
     * is still running, then hand back its response.
     */
    private void serveAsDuplicate(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain chain,
                                  Entry original) throws ServletException, IOException {

        boolean finished;
        try {
            finished = original.done.await(WAIT_FOR_ORIGINAL_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        if (finished && original.replayable) {
            response.setStatus(original.status);
            if (original.contentType != null) {
                response.setContentType(original.contentType);
            }
            response.setContentLength(original.body.length);
            response.setHeader(REPLAY_HEADER, "replayed");
            response.getOutputStream().write(original.body);
            return;
        }

        if (finished) {
            // The original failed and released its key — this is a legitimate
            // retry of a failed operation, so let it through.
            chain.doFilter(request, response);
            return;
        }

        // The original is still running well past any reasonable request time.
        // Refuse rather than risk writing a second row.
        response.setStatus(HttpStatus.CONFLICT.value());
        response.setContentType("application/json");
        response.setHeader(REPLAY_HEADER, "timeout");
        response.getWriter().write(
                "{\"status\":409,"
                + "\"title\":\"Duplicate request\","
                + "\"detail\":\"An identical request is still being processed. Please wait and check before retrying.\","
                + "\"message\":\"An identical request is still being processed. Please wait and check before retrying.\"}");
    }

    private boolean isGuarded(HttpServletRequest request) {
        if (!GUARDED_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        for (String excluded : EXCLUDED_PATH_PREFIXES) {
            if (path.startsWith(excluded)) {
                return false;
            }
        }
        // File uploads are streamed and parsed by Spring; do not buffer them.
        String contentType = request.getContentType();
        return contentType == null || !contentType.toLowerCase().startsWith("multipart/");
    }

    /**
     * Scopes the key so two different cashiers — or two different tenants —
     * performing the same action at the same moment never collide.
     */
    private String buildKey(HttpServletRequest request, byte[] body, String explicitKey) {
        String tenant = TenantContext.getTenant();
        StringBuilder raw = new StringBuilder()
                .append(tenant == null ? "-" : tenant).append('|')
                .append(currentUser()).append('|')
                .append(request.getMethod()).append('|')
                .append(request.getRequestURI()).append('|')
                .append(request.getQueryString() == null ? "" : request.getQueryString()).append('|');

        if (explicitKey != null) {
            raw.append("k:").append(explicitKey);
            return raw.toString();
        }
        raw.append("b:").append(sha256(body));
        return raw.toString();
    }

    private String currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Recorded outcome of the first request, plus the latch duplicates wait on. */
    private static final class Entry {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile int status;
        private volatile String contentType;
        private volatile byte[] body;
        private volatile boolean replayable;
    }

    /** Serves an already-consumed request body from memory. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(@NonNull byte[] buffer, int off, int len) {
                    return source.read(buffer, off, len);
                }

                @Override
                public int available() {
                    return source.available();
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Async reads are not supported here");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }
}
