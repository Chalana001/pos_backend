package com.chala.posapp.repository;

import com.chala.posapp.entity.ImpersonationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ImpersonationSessionRepository extends JpaRepository<ImpersonationSession, Long> {

    Optional<ImpersonationSession> findByTokenId(String tokenId);

    List<ImpersonationSession> findTop20ByTenantIdOrderByIssuedAtDesc(String tenantId);

    /** Sessions still usable right now — the panel's "someone is inside a shop" indicator. */
    @Query("""
            select s from ImpersonationSession s
            where s.revokedAt is null and s.expiresAt > :now
            order by s.issuedAt desc
            """)
    List<ImpersonationSession> findActive(@Param("now") LocalDateTime now);

    @Query("""
            select count(s) from ImpersonationSession s
            where s.tenantId = :tenantId and s.revokedAt is null and s.expiresAt > :now
            """)
    long countActiveFor(@Param("tenantId") String tenantId, @Param("now") LocalDateTime now);

    /**
     * Bumps usage counters without loading the entity. Called on every impersonated request,
     * so it must stay a single cheap statement.
     */
    @Modifying
    @Query("""
            update ImpersonationSession s
            set s.lastSeenAt = :now, s.requestCount = s.requestCount + 1
            where s.tokenId = :tokenId
            """)
    void touch(@Param("tokenId") String tokenId, @Param("now") LocalDateTime now);
}
