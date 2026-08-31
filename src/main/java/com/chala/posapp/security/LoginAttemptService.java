package com.chala.posapp.security;

import com.chala.posapp.exception.TooManyAttemptsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts consecutive failed logins and locks an account for a cooling-off period.
 *
 * <p>{@code RateLimitFilter} already caps how fast one IP may hit the auth endpoints; this is
 * the other half — it caps how many times one <em>account</em> may be guessed at, no matter how
 * many addresses the guesses come from.
 *
 * <h2>Two trade-offs worth knowing</h2>
 *
 * <p><strong>State is in-memory.</strong> Counters reset when the backend restarts, and a second
 * node would keep its own. That matches {@code RateLimitFilter} and the single-node deployment;
 * moving either to Redis is the same piece of work.
 *
 * <p><strong>Locking is keyed by account, so it can be weaponised.</strong> Someone who knows a
 * shop's admin username can deliberately fail five logins and lock that admin out for the
 * cooling-off period. That is the accepted cost of stopping distributed password guessing, and
 * it is why the window is minutes rather than hours. Set {@code app.security.login.max-attempts}
 * to 0 to switch lockout off entirely and rely on per-IP limiting alone.
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** Stops the map growing without bound if someone sprays random usernames. */
    private static final int MAX_TRACKED_ACCOUNTS = 10_000;

    private final int maxAttempts;
    private final Duration lockoutDuration;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(
            @Value("${app.security.login.max-attempts:5}") int maxAttempts,
            @Value("${app.security.login.lockout-minutes:15}") long lockoutMinutes
    ) {
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = Duration.ofMinutes(lockoutMinutes);
    }

    public boolean isEnabled() {
        return maxAttempts > 0;
    }

    /** How long the cooling-off period lasts, for messages and audit lines. */
    public long lockoutMinutes() {
        return lockoutDuration.toMinutes();
    }

    /**
     * @throws TooManyAttemptsException if this account is inside its cooling-off period.
     */
    public void assertNotLocked(String tenantId, String username) {
        if (!isEnabled()) {
            return;
        }
        Attempts record = attempts.get(key(tenantId, username));
        if (record == null) {
            return;
        }
        Instant lockedUntil = record.lockedUntil;
        if (lockedUntil == null || lockedUntil.isBefore(Instant.now())) {
            return;
        }
        long secondsLeft = Duration.between(Instant.now(), lockedUntil).getSeconds();
        long minutesLeft = Math.max(1, (secondsLeft + 59) / 60);
        throw new TooManyAttemptsException(
                "Too many failed sign-in attempts. Try again in " + minutesLeft + " minute(s).");
    }

    /**
     * @return true if <em>this</em> failure is the one that tripped the lock — the caller uses
     *         that to write a single audit entry per lockout rather than one per attempt, so a
     *         brute-force run cannot turn the audit table into its own denial of service.
     */
    public boolean recordFailure(String tenantId, String username) {
        if (!isEnabled()) {
            return false;
        }
        if (attempts.size() >= MAX_TRACKED_ACCOUNTS) {
            purgeExpired();
        }

        Attempts record = attempts.computeIfAbsent(key(tenantId, username), ignored -> new Attempts());
        int failures = record.count.incrementAndGet();

        if (failures >= maxAttempts && record.lockedUntil == null) {
            record.lockedUntil = Instant.now().plus(lockoutDuration);
            return true;
        }
        return false;
    }

    /** A correct password ends the cooling-off period and clears the count. */
    public void recordSuccess(String tenantId, String username) {
        attempts.remove(key(tenantId, username));
    }

    private String key(String tenantId, String username) {
        String tenant = tenantId == null ? "-" : tenantId;
        String user = username == null ? "-" : username.trim().toLowerCase(Locale.ROOT);
        return tenant + "|" + user;
    }

    /** Drop entries whose lock has expired and which are not mid-attempt any more. */
    private void purgeExpired() {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(entry -> {
            Instant lockedUntil = entry.getValue().lockedUntil;
            return lockedUntil != null && lockedUntil.isBefore(now);
        });
        if (attempts.size() >= MAX_TRACKED_ACCOUNTS) {
            // Still full — the tracker is being sprayed with fresh usernames. Start over
            // rather than grow: losing counters is a smaller problem than losing the heap.
            log.warn("Login attempt tracker is full ({} accounts); clearing it.", attempts.size());
            attempts.clear();
        }
    }

    private static final class Attempts {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant lockedUntil;
    }
}
