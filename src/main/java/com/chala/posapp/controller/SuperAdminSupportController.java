package com.chala.posapp.controller;

import com.chala.posapp.dto.saas.support.SupportDtos.*;
import com.chala.posapp.entity.ImpersonationSession;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.service.BulkShopActionService;
import com.chala.posapp.service.ImpersonationService;
import com.chala.posapp.service.ShopNoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The support desk: opening a session inside a shop, notes about a shop, and actions applied
 * to many shops at once.
 */
@RestController
@RequestMapping("/api/saas/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminSupportController {

    private final ImpersonationService impersonationService;
    private final ShopNoteService noteService;
    private final BulkShopActionService bulkService;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;

    /**
     * Where the POS app lives, used to build the "open this shop" link. A shop's own subdomain
     * is substituted for {tenant} when the pattern contains it.
     */
    @Value("${app.pos.url-pattern:}")
    private String posUrlPattern;

    // ------------------------------------------------------------ impersonation

    /** Opens a short-lived, revocable session inside a shop. Read-only unless asked otherwise. */
    @PostMapping("/shops/{tenantId}/support-session")
    public ResponseEntity<OpenSessionResponse> openSession(
            @PathVariable String tenantId,
            @Valid @RequestBody OpenSessionRequest request
    ) {
        // Read-only is the default: a caller has to say readOnly=false to get write access.
        boolean readOnly = request.readOnly() == null || request.readOnly();
        var issued = impersonationService.open(tenantId, readOnly, request.minutes(), request.reason());

        return ResponseEntity.ok(new OpenSessionResponse(
                issued.token(), issued.tokenId(), issued.tenantId(), issued.targetUsername(),
                issued.readOnly(), issued.expiresAt(), issued.ttlMinutes(), buildPosUrl(tenantId)));
    }

    @GetMapping("/shops/{tenantId}/support-sessions")
    public ResponseEntity<List<SessionResponse>> sessionHistory(@PathVariable String tenantId) {
        return ResponseEntity.ok(impersonationService.historyFor(tenantId).stream()
                .map(this::toSessionResponse)
                .toList());
    }

    /** Everyone currently inside a shop, across the estate. */
    @GetMapping("/support-sessions/active")
    public ResponseEntity<List<SessionResponse>> activeSessions() {
        return ResponseEntity.ok(impersonationService.activeSessions().stream()
                .map(this::toSessionResponse)
                .toList());
    }

    @PostMapping("/support-sessions/{sessionId}/revoke")
    public ResponseEntity<Void> revokeSession(@PathVariable Long sessionId) {
        impersonationService.revoke(sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/shops/{tenantId}/support-sessions/revoke-all")
    public ResponseEntity<Map<String, Integer>> revokeAll(@PathVariable String tenantId) {
        return ResponseEntity.ok(Map.of("revoked", impersonationService.revokeAllFor(tenantId)));
    }

    // ------------------------------------------------------------------- notes

    @GetMapping("/shops/{tenantId}/notes")
    public ResponseEntity<List<NoteResponse>> notes(@PathVariable String tenantId) {
        return ResponseEntity.ok(noteService.list(tenantId));
    }

    @PostMapping("/shops/{tenantId}/notes")
    public ResponseEntity<NoteResponse> addNote(@PathVariable String tenantId,
                                                @Valid @RequestBody NoteRequest request) {
        return ResponseEntity.ok(noteService.create(tenantId, request));
    }

    @PutMapping("/shops/{tenantId}/notes/{noteId}")
    public ResponseEntity<NoteResponse> updateNote(@PathVariable String tenantId,
                                                   @PathVariable Long noteId,
                                                   @Valid @RequestBody NoteRequest request) {
        return ResponseEntity.ok(noteService.update(tenantId, noteId, request));
    }

    @DeleteMapping("/shops/{tenantId}/notes/{noteId}")
    public ResponseEntity<Void> deleteNote(@PathVariable String tenantId, @PathVariable Long noteId) {
        noteService.delete(tenantId, noteId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------- bulk

    /**
     * One action across many shops. Never aborts halfway — every shop is attempted and
     * reported independently.
     */
    @PostMapping("/shops/bulk")
    public ResponseEntity<BulkActionResponse> bulk(@Valid @RequestBody BulkActionRequest request) {
        return ResponseEntity.ok(bulkService.apply(request));
    }

    // --------------------------------------------------------------- internals

    private String buildPosUrl(String tenantId) {
        if (posUrlPattern == null || posUrlPattern.isBlank()) {
            return null;
        }
        return posUrlPattern.replace("{tenant}", tenantId);
    }

    private SessionResponse toSessionResponse(ImpersonationSession session) {
        String shopName = tenantSubscriptionRepository.findByTenantId(session.getTenantId())
                .map(subscription -> subscription.getShopName())
                .orElse(session.getTenantId());

        return new SessionResponse(
                session.getId(), session.getTenantId(), shopName, session.getActor(),
                session.getTargetUsername(), session.isReadOnly(), session.getReason(),
                session.getStatus(), session.getIssuedAt(), session.getExpiresAt(),
                session.getRevokedAt(), session.getRevokedBy(), session.getLastSeenAt(),
                session.getRequestCount());
    }
}
