package com.chala.posapp.rbac;

import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class TenantReauthenticationService {
    public static final String OWNER_TRANSFER_PURPOSE = "OWNER_TRANSFER";
    private static final long TTL_SECONDS = 300;

    private final TenantRbacJdbcService jdbcService;
    private final SecurityUtils securityUtils;
    private final PasswordEncoder passwordEncoder;

    public ReauthChallenge issueOwnerTransferChallenge(String password) {
        User user = securityUtils.getCurrentUser();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadRequestException("Recent authentication required");
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String digest = digest(challenge);
        jdbcService.withTenantJdbc(jdbc -> {
            jdbc.update("""
                    INSERT INTO tenant_reauth_challenges (user_id, purpose, challenge_digest, expires_at)
                    VALUES (?, ?, ?, ?)
                    """, user.getId(), OWNER_TRANSFER_PURPOSE, digest,
                    LocalDateTime.now().plusSeconds(TTL_SECONDS));
            return null;
        });
        return new ReauthChallenge(challenge, TTL_SECONDS);
    }

    public void consumeOwnerTransferChallenge(String challenge) {
        User user = securityUtils.getCurrentUser();
        if (challenge == null || challenge.isBlank()) throw new BadRequestException("Recent authentication required");
        String digest = digest(challenge);
        jdbcService.withTenantJdbc(jdbc -> {
            int consumed = jdbc.update("""
                    UPDATE tenant_reauth_challenges
                    SET consumed_at = CURRENT_TIMESTAMP(6)
                    WHERE user_id = ? AND purpose = ? AND challenge_digest = ?
                      AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                    """, user.getId(), OWNER_TRANSFER_PURPOSE, digest);
            if (consumed != 1) throw new BadRequestException("Recent authentication challenge is invalid or expired");
            return null;
        });
    }

    private String digest(String value) {
        try {
            return HexFormatHolder.format(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not hash authentication challenge", ex);
        }
    }

    public record ReauthChallenge(String challenge, long expiresInSeconds) {}

    private static final class HexFormatHolder {
        private static String format(byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        }
    }
}
