package com.chala.posapp.service;

import com.chala.posapp.dto.saas.ChangePackageRequest;
import com.chala.posapp.dto.saas.ManualRenewRequest;
import com.chala.posapp.dto.saas.ShopBlockRequest;
import com.chala.posapp.dto.saas.module.ModuleToggleRequest;
import com.chala.posapp.dto.saas.support.SupportDtos.BulkActionRequest;
import com.chala.posapp.dto.saas.support.SupportDtos.BulkActionResponse;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies one action across many shops.
 *
 * <p>Each shop runs in <strong>its own transaction</strong>. That is the whole design: a bulk
 * renew over forty shops must not roll all forty back because the thirty-seventh has no admin
 * user. Failures are collected per shop and returned, so the operator can see exactly which
 * ones need attention and retry just those.
 *
 * <p>The work itself is delegated to the same services the single-shop screens use, so a bulk
 * renew and a manual renew produce identical billing records and audit entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkShopActionService {

    /** Guards against a mis-click selecting the entire estate. */
    private static final int MAX_TARGETS = 200;

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SuperAdminSaasService saasService;
    private final SuperAdminModuleService moduleService;
    private final SuperAdminAuditService auditService;
    private final AuthService authService;

    /**
     * Deliberately NOT transactional: each shop opens its own below, so one failure cannot
     * poison the rest.
     */
    public BulkActionResponse apply(BulkActionRequest request) {
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        List<String> targets = request.tenantIds().stream().distinct().toList();

        if (targets.size() > MAX_TARGETS) {
            throw new BadRequestException(
                    "Too many shops selected (" + targets.size() + "). The limit is " + MAX_TARGETS + ".");
        }

        List<BulkActionResponse.Outcome> results = new ArrayList<>();
        for (String tenantId : targets) {
            String shopName = tenantSubscriptionRepository.findByTenantId(tenantId)
                    .map(TenantSubscription::getShopName)
                    .orElse(tenantId);
            try {
                String message = applyOne(action, tenantId, request);
                results.add(new BulkActionResponse.Outcome(tenantId, shopName, true, message));
            } catch (Exception exception) {
                log.warn("Bulk {} failed for {}: {}", action, tenantId, exception.getMessage());
                results.add(new BulkActionResponse.Outcome(tenantId, shopName, false, readable(exception)));
            }
        }

        int succeeded = (int) results.stream().filter(BulkActionResponse.Outcome::ok).count();

        // One audit line for the run as a whole; the individual actions have already written
        // their own, so this is the "why are there 40 renewals at 14:02" entry.
        auditService.record(currentActor(), "BULK_" + action, SuperAdminAuditService.TARGET_SYSTEM, null,
                String.format("Bulk %s over %d shop(s): %d succeeded, %d failed%s",
                        action, targets.size(), succeeded, targets.size() - succeeded,
                        request.reason() != null && !request.reason().isBlank() ? " — " + request.reason() : ""));

        return new BulkActionResponse(action, targets.size(), succeeded, targets.size() - succeeded, results);
    }

    /**
     * Per-shop isolation comes from the delegates, not from an annotation here: every service
     * called below is @Transactional on its own bean, and {@link #apply} deliberately is not,
     * so each shop's work commits or rolls back on its own. Annotating this method would be
     * worse than useless — a self-invoked @Transactional is not proxied at all, so it would
     * read as isolation that isn't there.
     */
    private String applyOne(String action, String tenantId, BulkActionRequest request) {
        return switch (action) {
            case "BLOCK" -> {
                ShopBlockRequest block = new ShopBlockRequest();
                block.setBlocked(true);
                block.setReason(request.reason());
                saasService.updateShopBlockStatus(tenantId, block);
                yield "Blocked";
            }
            case "UNBLOCK" -> {
                ShopBlockRequest unblock = new ShopBlockRequest();
                unblock.setBlocked(false);
                unblock.setReason(request.reason());
                saasService.updateShopBlockStatus(tenantId, unblock);
                yield "Unblocked";
            }
            case "RENEW" -> {
                ManualRenewRequest renew = new ManualRenewRequest();
                renew.setCycles(request.cycles() == null ? 1 : request.cycles());
                renew.setAmountPaid(request.amountPaid());
                renew.setNote(request.reason());
                var result = saasService.renewSubscription(tenantId, renew);
                yield "Renewed until " + result.getValidUntil().toLocalDate();
            }
            case "CHANGE_PLAN" -> {
                if (request.planId() == null) {
                    throw new BadRequestException("planId is required for CHANGE_PLAN");
                }
                ChangePackageRequest change = new ChangePackageRequest();
                change.setPlanId(request.planId());
                change.setAmountPaid(request.amountPaid());
                change.setNote(request.reason());
                var result = saasService.changePackage(tenantId, change);
                yield "Moved to " + result.getPlanName();
            }
            case "SET_MODULES" -> {
                if (request.moduleChanges() == null || request.moduleChanges().isEmpty()) {
                    throw new BadRequestException("moduleChanges is required for SET_MODULES");
                }
                List<ModuleToggleRequest.Change> changes = request.moduleChanges().stream()
                        .map(change -> new ModuleToggleRequest.Change(change.moduleKey(), change.enabled()))
                        .toList();
                var result = moduleService.updateTenantModules(tenantId,
                        new ModuleToggleRequest(changes, request.reason()));
                yield result.enabledCount() + "/" + result.totalCount() + " modules on";
            }
            case "RESET_MODULES" -> {
                var result = moduleService.resetTenantToPlan(tenantId);
                yield "Back to the " + result.planName() + " plan";
            }
            default -> throw new BadRequestException("Unsupported bulk action: " + action);
        };
    }

    private String readable(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private String currentActor() {
        try {
            return authService.getLoggedUser().getUsername();
        } catch (Exception exception) {
            return "system";
        }
    }
}
