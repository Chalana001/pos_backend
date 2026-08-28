package com.chala.posapp.dto.saas.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request and response shapes for the support toolkit.
 *
 * <p>Grouped in one file because they are small, always used together, and splitting eight
 * three-field records across eight files buys nothing.
 */
public final class SupportDtos {

    private SupportDtos() {
    }

    // ------------------------------------------------------------------ notes

    public record NoteRequest(
            @NotBlank(message = "Note body is required")
            @Size(max = 10_000, message = "Note is too long")
            String body,
            String category,
            Boolean pinned
    ) {
    }

    public record NoteResponse(
            Long id,
            String tenantId,
            String body,
            String category,
            boolean pinned,
            String author,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    // ---------------------------------------------------------- impersonation

    public record OpenSessionRequest(
            /** Read-only unless explicitly set false — writes must be asked for. */
            Boolean readOnly,
            Integer minutes,
            @NotBlank(message = "A reason is required to open a support session")
            @Size(max = 255)
            String reason
    ) {
    }

    /**
     * @param token    the support-session JWT; the panel hands it straight to the POS app and
     *                 never stores it
     * @param posUrl   where to open it, when the platform knows the shop's POS address
     */
    public record OpenSessionResponse(
            String token,
            String tokenId,
            String tenantId,
            String targetUsername,
            boolean readOnly,
            LocalDateTime expiresAt,
            int ttlMinutes,
            String posUrl
    ) {
    }

    public record SessionResponse(
            Long id,
            String tenantId,
            String shopName,
            String actor,
            String targetUsername,
            boolean readOnly,
            String reason,
            String status,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt,
            String revokedBy,
            LocalDateTime lastSeenAt,
            int requestCount
    ) {
    }

    // ------------------------------------------------------------------ bulk

    /**
     * One action applied to many shops.
     *
     * <p>{@code action} is one of BLOCK, UNBLOCK, RENEW, CHANGE_PLAN, SET_MODULES,
     * RESET_MODULES, DEACTIVATE, ACTIVATE. The extra fields are only read by the actions
     * that need them, which keeps this a single endpoint instead of eight near-identical ones.
     */
    public record BulkActionRequest(
            @NotBlank(message = "action is required") String action,
            @NotEmpty(message = "Select at least one shop") List<String> tenantIds,
            String reason,
            Integer cycles,
            Long planId,
            Double amountPaid,
            List<ModuleChange> moduleChanges
    ) {
        public record ModuleChange(String moduleKey, Boolean enabled) {
        }
    }

    /**
     * Per-shop outcome. A bulk run never aborts halfway: each shop is attempted independently
     * and reported, so one bad tenant does not hide the other forty-nine that worked.
     */
    public record BulkActionResponse(
            String action,
            int requested,
            int succeeded,
            int failed,
            List<Outcome> results
    ) {
        public record Outcome(String tenantId, String shopName, boolean ok, String message) {
        }
    }
}
