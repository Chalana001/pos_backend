package com.chala.posapp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the double-click race the filter exists to close: two identical
 * write requests arriving at the same moment must reach the controller once.
 */
class DuplicateRequestFilterTest {

    private static final String BODY = "{\"openingCash\":1000}";

    private DuplicateRequestFilter filter() {
        return new DuplicateRequestFilter(true, 5, 600);
    }

    private MockHttpServletRequest post(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** Chain that records how many times it ran and writes a JSON response. */
    private static FilterChain countingChain(AtomicInteger calls, Runnable beforeResponding) {
        return (req, res) -> {
            calls.incrementAndGet();
            beforeResponding.run();
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(200);
            response.setContentType("application/json");
            response.getWriter().write("{\"shiftId\":1}");
        };
    }

    @Test
    void twoSimultaneousIdenticalRequestsReachTheControllerOnce() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);

        // The original holds the chain open long enough for the duplicate to
        // arrive — exactly the window a check-then-insert cannot protect.
        FilterChain chain = countingChain(calls, () -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                bothStarted.countDown();
                filter.doFilter(post("/shifts/open", BODY), first, chain);
                return null;
            });
            pool.submit(() -> {
                bothStarted.countDown();
                Thread.sleep(50); // land while the first is still in flight
                filter.doFilter(post("/shifts/open", BODY), second, chain);
                return null;
            });
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, calls.get(), "the shift must be opened once, not twice");
        assertEquals(200, first.getStatus());
        assertEquals(200, second.getStatus());
        assertEquals("{\"shiftId\":1}", first.getContentAsString());
        assertEquals("{\"shiftId\":1}", second.getContentAsString(),
                "the duplicate must be served the original's response");
    }

    @Test
    void aSecondClickAfterTheFirstFinishedIsStillCollapsed() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = countingChain(calls, () -> {});

        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(post("/orders", BODY), first, chain);
        filter.doFilter(post("/orders", BODY), second, chain);

        assertEquals(1, calls.get(), "a click landing after the response still must not re-post");
        assertEquals("replayed", second.getHeader("X-Duplicate-Request"));
    }

    @Test
    void differentBodiesAreNotCollapsed() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = countingChain(calls, () -> {});

        filter.doFilter(post("/orders", "{\"total\":100}"), new MockHttpServletResponse(), chain);
        filter.doFilter(post("/orders", "{\"total\":250}"), new MockHttpServletResponse(), chain);

        assertEquals(2, calls.get(), "genuinely different sales must both go through");
    }

    @Test
    void failedRequestsStayRetryable() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain failing = (req, res) -> {
            calls.incrementAndGet();
            ((HttpServletResponse) res).setStatus(400);
        };

        filter.doFilter(post("/orders", BODY), new MockHttpServletResponse(), failing);
        filter.doFilter(post("/orders", BODY), new MockHttpServletResponse(), failing);

        assertEquals(2, calls.get(), "a failed attempt must not block the retry");
    }

    @Test
    void readsAreNeverCollapsed() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = countingChain(calls, () -> {});

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), chain);
        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), chain);

        assertEquals(2, calls.get());
    }

    @Test
    void theRequestBodyStillReachesTheController() throws Exception {
        DuplicateRequestFilter filter = filter();
        StringBuilder seen = new StringBuilder();
        FilterChain chain = (req, res) -> {
            seen.append(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            ((HttpServletResponse) res).setStatus(200);
        };

        filter.doFilter(post("/orders", BODY), new MockHttpServletResponse(), chain);

        assertEquals(BODY, seen.toString(), "buffering the body must not consume it");
    }

    @Test
    void differentIdempotencyKeysOnTheSameBodyAreNotCollapsed() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = countingChain(calls, () -> {});

        MockHttpServletRequest firstCheckout = post("/orders", BODY);
        firstCheckout.addHeader("Idempotency-Key", "checkout-1");
        MockHttpServletRequest secondCheckout = post("/orders", BODY);
        secondCheckout.addHeader("Idempotency-Key", "checkout-2");

        filter.doFilter(firstCheckout, new MockHttpServletResponse(), chain);
        filter.doFilter(secondCheckout, new MockHttpServletResponse(), chain);

        assertEquals(2, calls.get(), "two deliberate checkouts of the same cart must both go through");
    }

    @Test
    void theSameIdempotencyKeyIsCollapsed() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = countingChain(calls, () -> {});

        MockHttpServletRequest attempt = post("/orders", BODY);
        attempt.addHeader("Idempotency-Key", "checkout-1");
        MockHttpServletRequest retry = post("/orders", BODY);
        retry.addHeader("Idempotency-Key", "checkout-1");

        filter.doFilter(attempt, new MockHttpServletResponse(), chain);
        filter.doFilter(retry, new MockHttpServletResponse(), chain);

        assertEquals(1, calls.get(), "one checkout attempt must create one order");
    }

    @Test
    void loginIsExcluded() throws Exception {
        DuplicateRequestFilter filter = filter();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = countingChain(calls, () -> {});

        filter.doFilter(post("/api/auth/login", BODY), new MockHttpServletResponse(), chain);
        filter.doFilter(post("/api/auth/login", BODY), new MockHttpServletResponse(), chain);

        assertEquals(2, calls.get());
    }

    @Test
    void disablingTheFilterPassesEverythingThrough() throws Exception {
        DuplicateRequestFilter disabled = new DuplicateRequestFilter(false, 5, 600);
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = countingChain(calls, () -> {});

        disabled.doFilter(post("/orders", BODY), new MockHttpServletResponse(), chain);
        disabled.doFilter(post("/orders", BODY), new MockHttpServletResponse(), chain);

        assertEquals(2, calls.get());
    }
}
