package com.chala.posapp.service;

import com.chala.posapp.entity.ImpersonationSession;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ImpersonationSessionRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.security.JwtService;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Issues and polices support sessions — an operator opening a shop's own POS without the
 * owner's password.
 *
 * <p>Three things keep this a support tool rather than a back door:
 * <ul>
 *   <li><strong>Short lifetime.</strong> {@link #MAX_TTL_MINUTES} caps it; the default is 30
 *       minutes, not the 24 hours a normal login gets.</li>
 *   <li><strong>Revocable.</strong> The token's {@code jti} is checked against a database row
 *       on every request, so revoking ends the session immediately.</li>
 *   <li><strong>Read-only by default.</strong> Writes have to be asked for explicitly, and are
 *       rejected at the filter, not merely hidden in the UI.</li>
 * </ul>
 *
 * <p>Every issue and revoke is written to the audit trail with the reason given.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpersonationService {

    /** Nothing longer than this can be issued, whatever the caller asks for. */
    public static final int MAX_TTL_MINUTES = 120;
    public static final int DEFAULT_TTL_MINUTES = 30;

    /**
     * How long a validated jti stays trusted without re-reading the row. Short, because the
     * whole point of revocation is that it takes effect promptly — 10 seconds bounds the
     * window while still collapsing a burst of requests into one query.
     */
    private static final long VALIDATION_CACHE_MILLIS = 10_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ImpersonationSessionRepository sessionRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final SuperAdminAuditService auditService;
    private final PlatformTransactionManager transactionManager;

    private final Map<String, CachedValidation> validationCache = new ConcurrentHashMap<>();

    /** What the panel gets back when a session is opened. */
    public record IssuedSession(
            String token,
            String tokenId,
            String tenantId,
            String targetUsername,
            boolean readOnly,
            LocalDateTime expiresAt,
            int ttlMinutes
    ) {
    }

    @Transactional
    public IssuedSession open(String tenantId, boolean readOnly, Integer requestedMinutes, String reason) {
        String actor = currentActor();

        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + tenantId));

        if (reason == null || reason.isBlank()) {
            // A support session without a stated reason is exactly the kind of access that is
            // impossible to review later, so it is refused rather than defaulted.
            throw new BadRequestException("A reason is required to open a support session.");
        }

        int ttlMinutes = Math.min(
                requestedMinutes == null || requestedMinutes < 1 ? DEFAULT_TTL_MINUTES : requestedMinutes,
                MAX_TTL_MINUTES);

        // Borrow the shop's own admin identity so role checks inside the tenant behave normally.
        User target = inTenant(tenantId, () -> userRepository.findByUsername(subscription.getAdminUsername())
                .or(userRepository::findFirstAdminNative)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "This shop has no admin user to open a session as.")));

        if (target.getRole() == Role.SUPER_ADMIN) {
            throw new BadRequestException("Refusing to impersonate a super admin account.");
        }

        String tokenId = newTokenId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(ttlMinutes);

        String token = jwtService.generateImpersonationToken(
                target.getUsername(), target.getRole().name(), tenantId,
                tokenId, actor, readOnly, ttlMinutes * 60_000L);

        sessionRepository.save(ImpersonationSession.builder()
                .tokenId(tokenId)
                .tenantId(tenantId)
                .actor(actor)
                .targetUsername(target.getUsername())
                .readOnly(readOnly)
                .reason(reason.trim())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .requestCount(0)
                .build());

        auditService.record(actor, "SUPPORT_SESSION_OPENED", SuperAdminAuditService.TARGET_SHOP, tenantId,
                String.format("Opened a %s support session on %s as %s for %d min — %s",
                        readOnly ? "read-only" : "READ-WRITE", subscription.getShopName(),
                        target.getUsername(), ttlMinutes, reason.trim()));

        log.info("Support session opened. tenant={}, actor={}, readOnly={}, ttl={}min",
                tenantId, actor, readOnly, ttlMinutes);

        return new IssuedSession(token, tokenId, tenantId, target.getUsername(), readOnly, expiresAt, ttlMinutes);
    }

    @Transactional
    public void revoke(Long sessionId) {
        ImpersonationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Support session not found"));

        if (session.getRevokedAt() != null) {
            return;
        }
        String actor = currentActor();
        session.setRevokedAt(LocalDateTime.now());
        session.setRevokedBy(actor);
        sessionRepository.save(session);
        validationCache.remove(session.getTokenId());

        auditService.record(actor, "SUPPORT_SESSION_REVOKED", SuperAdminAuditService.TARGET_SHOP,
                session.getTenantId(),
                "Revoked the support session opened by " + session.getActor()
                        + " (" + session.getRequestCount() + " request(s) made)");
    }

    /** Ends every live session on a shop — the "get everyone out" button. */
    @Transactional
    public int revokeAllFor(String tenantId) {
        String actor = currentActor();
        LocalDateTime now = LocalDateTime.now();
        List<ImpersonationSession> active = sessionRepository.findActive(now).stream()
                .filter(session -> session.getTenantId().equals(tenantId))
                .toList();

        active.forEach(session -> {
            session.setRevokedAt(now);
            session.setRevokedBy(actor);
            sessionRepository.save(session);
            validationCache.remove(session.getTokenId());
        });

        if (!active.isEmpty()) {
            auditService.record(actor, "SUPPORT_SESSION_REVOKED", SuperAdminAuditService.TARGET_SHOP, tenantId,
                    "Revoked " + active.size() + " live support session(s)");
        }
        return active.size();
    }

    @Transactional(readOnly = true)
    public List<ImpersonationSession> historyFor(String tenantId) {
        return sessionRepository.findTop20ByTenantIdOrderByIssuedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public List<ImpersonationSession> activeSessions() {
        return sessionRepository.findActive(LocalDateTime.now());
    }

    /**
     * Called by {@code ImpersonationFilter} on every request carrying a support token.
     *
     * @return the session when it is still usable, empty when it was revoked or has expired
     */
    public Optional<ImpersonationSession> validate(String tokenId) {
        CachedValidation cached = validationCache.get(tokenId);
        if (cached != null && !cached.isStale()) {
            return Optional.ofNullable(cached.session());
        }

        Optional<ImpersonationSession> found = inMaster(() -> sessionRepository.findByTokenId(tokenId))
                .filter(ImpersonationSession::isActive);

        validationCache.put(tokenId, new CachedValidation(found.orElse(null), System.currentTimeMillis()));
        return found;
    }

    /** Fire-and-forget usage counter; never allowed to fail the request it is counting. */
    public void touch(String tokenId) {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TenantContext.runWith("MASTER",
                    () -> tx.executeWithoutResult(status -> sessionRepository.touch(tokenId, LocalDateTime.now())));
        } catch (Exception exception) {
            log.debug("Could not update support session usage for {}: {}", tokenId, exception.getMessage());
        }
    }

    private String newTokenId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Read straight from the security context rather than through AuthService.
     *
     * AuthService needs the PasswordEncoder bean, which SecurityConfig declares — and
     * SecurityConfig injects ImpersonationFilter, which needs this service. Going through
     * AuthService for nothing more than a username closes that loop and the context refuses
     * to start. The authenticated principal's name is the same answer, one hop earlier.
     */
    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return "system";
        }
        return authentication.getName();
    }

    private <T> T inMaster(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return TenantContext.callWith("MASTER", () -> tx.execute(status -> work.get()));
    }

    private <T> T inTenant(String tenantId, Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return TenantContext.callWith(tenantId, () -> tx.execute(status -> work.get()));
    }

    private record CachedValidation(ImpersonationSession session, long checkedAt) {
        boolean isStale() {
            return System.currentTimeMillis() - checkedAt > VALIDATION_CACHE_MILLIS;
        }
    }
}
