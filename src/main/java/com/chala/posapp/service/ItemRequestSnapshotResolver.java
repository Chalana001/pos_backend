package com.chala.posapp.service;

import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds {@link ItemRequestSnapshot} instances for item retrieval and mapping
 * requests (the paths behind {@code searchForPos}, {@code searchForPurchase}
 * and {@code mapToResponse}).
 *
 * <p>Stateless singleton: it holds only its service dependency and never
 * retains tenant, branch or configuration values between calls, so two
 * requests — even for different tenants or branches — cannot observe each
 * other's snapshot. The snapshot itself is created explicitly per request;
 * nothing here is cached or shared.
 */
@Service
@RequiredArgsConstructor
public class ItemRequestSnapshotResolver {

    private final AppConfigurationService appConfigurationService;

    /**
     * Resolves the configuration and tenant-plan values for one request in a
     * single pass: one configuration resolution and one master-database plan
     * lookup, instead of one of each per item.
     *
     * @param branchId the branch scope exactly as the request supplied it — a
     *                 concrete id, {@code 0} (all-branch item scope) or
     *                 {@code null} (no item stock scope). The value is kept
     *                 verbatim because the three are not interchangeable.
     *                 Configuration resolution follows the existing
     *                 semantics: a concrete id looks up the branch row and
     *                 falls back to the global row, while {@code 0} and
     *                 {@code null} both normalize to the global row.
     */
    public ItemRequestSnapshot resolve(Long branchId) {
        return new ItemRequestSnapshot(
                TenantContext.getTenant(),
                branchId,
                appConfigurationService.resolveConfigurationSnapshot(branchId),
                appConfigurationService.resolveTenantPlanSnapshot());
    }
}
