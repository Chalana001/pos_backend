package com.chala.posapp.config;

import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionFilter extends OncePerRequestFilter {

    private final TenantSubscriptionRepository subscriptionRepository;
    private final PlatformTransactionManager transactionManager;
    private static final Set<String> STANDARD_BLOCKED_PREFIXES = Set.of(
            "/reports",
            "/purchases",
            "/suppliers",
            "/grn",
            "/stock-transfers"
    );
    private static final Set<String> FREE_BLOCKED_PREFIXES = Set.of(
            "/reports",
            "/purchases",
            "/suppliers",
            "/grn",
            "/expenses",
            "/cash-drops",
            "/users",
            "/stock",
            "/stock-adjustments",
            "/stock-transfers",
            "/dining-tables",
            "/pending-orders",
            "/credit"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String tenantId = TenantContext.getTenant();
        String path = request.getRequestURI();

        if (tenantId == null ||
                tenantId.equals("MASTER") ||
                path.startsWith("/auth/") ||
                path.startsWith("/api/auth/") ||
                path.startsWith("/api/saas/")) {

            filterChain.doFilter(request, response);
            return;
        }

        Optional<TenantSubscription> subscriptionOpt = findSubscriptionInMaster(tenantId);

        if (subscriptionOpt.isEmpty() ||
                !subscriptionOpt.get().isActive() ||
                subscriptionOpt.get().isBlocked() ||
                subscriptionOpt.get().getValidUntil().isBefore(LocalDateTime.now())) {

            log.warn("Subscription blocked for tenant: {}", tenantId);

            response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Subscription expired or inactive. Please contact support or renew your plan.\"}");
            return;
        }

        TenantSubscription subscription = subscriptionOpt.get();
        String featurePath = normalizeFeaturePath(path);
        if (isFeatureBlockedForPlan(subscription, featurePath, request.getMethod())) {
            log.warn("Feature blocked by plan. tenant={}, plan={}, path={}, method={}",
                    tenantId, subscription.getPlan() != null ? subscription.getPlan().getName() : "N/A", path, request.getMethod());

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Your current package does not include this feature. Please upgrade to Pro.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isFeatureBlockedForPlan(TenantSubscription subscription, String path, String method) {
        String planName = subscription.getPlan() != null ? subscription.getPlan().getName() : "";
        if (isFreePlan(planName)) {
            return isBlockedByPrefixes(path, FREE_BLOCKED_PREFIXES)
                    || isFreeOnlyBlock(path, method)
                    || isSharedLowerTierBlock(path, method);
        }
        if (isStandardPlan(planName)) {
            return isBlockedByPrefixes(path, STANDARD_BLOCKED_PREFIXES)
                    || isSharedLowerTierBlock(path, method);
        }
        return false;
    }

    private Optional<TenantSubscription> findSubscriptionInMaster(String tenantId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return TenantContext.callWith("MASTER",
                () -> tx.execute(status -> subscriptionRepository.findByTenantId(tenantId)));
    }

    private String normalizeFeaturePath(String path) {
        return path != null && path.startsWith("/api/") ? path.substring(4) : path;
    }

    private boolean isFreePlan(String planName) {
        return "FREE".equalsIgnoreCase(planName) || "MONTHLY_DEMO".equalsIgnoreCase(planName);
    }

    private boolean isStandardPlan(String planName) {
        return "STANDARD".equalsIgnoreCase(planName)
                || "MONTHLY_LITE".equalsIgnoreCase(planName)
                || "YEARLY_LITE".equalsIgnoreCase(planName)
                || "MONTHLY_BASIC".equalsIgnoreCase(planName);
    }

    private boolean isBlockedByPrefixes(String path, Set<String> blockedPrefixes) {
        for (String blockedPrefix : blockedPrefixes) {
            if (path.startsWith(blockedPrefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFreeOnlyBlock(String path, String method) {
        if (path.startsWith("/cash-drops") || path.startsWith("/expenses")) {
            return true;
        }

        if ("POST".equalsIgnoreCase(method) && path.startsWith("/orders")) {
            return true;
        }

        if (path.startsWith("/orders/offline-import")) {
            return true;
        }

        if ("POST".equalsIgnoreCase(method) && path.startsWith("/items/bulk")) {
            return true;
        }

        if (path.startsWith("/items/search-print")) {
            return true;
        }

        return false;
    }

    private boolean isSharedLowerTierBlock(String path, String method) {
        if (path.matches("^/orders/[^/]+/cancel$")) {
            return true;
        }

        if (path.startsWith("/shifts/all")) {
            return true;
        }

        return false;
    }
}
