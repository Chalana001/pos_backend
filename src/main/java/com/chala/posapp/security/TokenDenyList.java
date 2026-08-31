package com.chala.posapp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the {@code jti} of tokens that have been logged out, until the moment they would have
 * expired anyway.
 *
 * <p>This is the precise half of revocation: it ends <em>one</em> session. The blunt half is
 * {@code users.token_valid_from}, which ends every session a user has at once and survives a
 * restart — that is what a password reset uses.
 *
 * <p>Entries are held in memory, so a restart forgets them. A forgotten entry means a token
 * someone logged out of becomes usable again for the remainder of its 24 hours, which is why
 * anything that must outlive a restart (password reset, compromise) bumps {@code
 * token_valid_from} instead of relying on this.
 */
@Slf4j
@Component
public class TokenDenyList {

    private static final int PURGE_THRESHOLD = 5_000;

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    /** Revoke a token id until {@code expiresAt}, after which the token is dead on its own. */
    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null || tokenId.isBlank() || expiresAt == null) {
            return;
        }
        if (expiresAt.isBefore(Instant.now())) {
            return;
        }
        if (revoked.size() >= PURGE_THRESHOLD) {
            purgeExpired();
        }
        revoked.put(tokenId, expiresAt);
    }

    public boolean isRevoked(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        Instant expiresAt = revoked.get(tokenId);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt.isBefore(Instant.now())) {
            revoked.remove(tokenId);
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        int before = revoked.size();
        revoked.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        log.debug("Purged {} expired token deny-list entries", before - revoked.size());
    }
}
