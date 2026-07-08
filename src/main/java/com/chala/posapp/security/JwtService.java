package com.chala.posapp.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Set;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration}") long expirationMs,
            Environment environment          // gives the REAL active profiles including defaults
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret is not configured. Set APP_JWT_SECRET environment variable.");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT secret is too short (minimum 32 characters). Set a strong APP_JWT_SECRET.");
        }
        // Reject known weak/dev defaults unless running under dev or test profile
        if (secret.startsWith("dev-only") || secret.equals("secret") || secret.equals("changeme")) {
            // environment.getActiveProfiles() returns explicitly activated profiles.
            // environment.getDefaultProfiles() returns the default profile ("dev" via spring.profiles.default).
            // We check both so "default=dev" is treated the same as "active=dev".
            Set<String> active = Set.of(environment.getActiveProfiles());
            Set<String> defaults = Set.of(environment.getDefaultProfiles());
            boolean isDevOrTest = active.stream().anyMatch(p -> p.contains("dev") || p.contains("test"))
                    || defaults.stream().anyMatch(p -> p.contains("dev") || p.contains("test"));
            if (!isDevOrTest) {
                throw new IllegalStateException(
                        "JWT secret appears to be a dev/test placeholder. Set a secure APP_JWT_SECRET for production.");
            }
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username, String role, String tenantId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("tenantId", tenantId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        Object r = parseClaims(token).get("role");
        return r == null ? null : r.toString();
    }

    public String extractTenantId(String token) {
        Object t = parseClaims(token).get("tenantId");
        return t == null ? null : t.toString();
    }
}
