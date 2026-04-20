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
    private static final Set<String> LITE_ONLY_BLOCKED_PREFIXES = Set.of(
            "/reports",
            "/purchases",
            "/suppliers",
            "/grn",
            "/expenses",
            "/cash-drops",
            "/users",
            "/stock-transfers"
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

        Optional<TenantSubscription> subscriptionOpt = subscriptionRepository.findByTenantId(tenantId);

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
        if (isLitePlan(subscription) && isFeatureBlockedForLite(path, request.getMethod())) {
            log.warn("Feature blocked by plan. tenant={}, plan={}, path={}, method={}",
                    tenantId, subscription.getPlan() != null ? subscription.getPlan().getName() : "N/A", path, request.getMethod());

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Your current package does not include this feature. Please upgrade to Pro.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isLitePlan(TenantSubscription subscription) {
        String planName = subscription.getPlan() != null ? subscription.getPlan().getName() : "";
        return "MONTHLY_LITE".equalsIgnoreCase(planName)
                || "YEARLY_LITE".equalsIgnoreCase(planName)
                || "MONTHLY_BASIC".equalsIgnoreCase(planName);
    }

    private boolean isFeatureBlockedForLite(String path, String method) {
        for (String blockedPrefix : LITE_ONLY_BLOCKED_PREFIXES) {
            if (path.startsWith(blockedPrefix)) {
                return true;
            }
        }

        if ("POST".equalsIgnoreCase(method) && path.startsWith("/items/bulk")) {
            return true;
        }

        if (path.startsWith("/items/search-print")) {
            return true;
        }

        if (path.matches("^/orders/[^/]+/cancel$")) {
            return true;
        }

        if (path.startsWith("/shifts/all")) {
            return true;
        }

        if (path.matches("^/shifts/[^/]+/cashdrop$") || path.startsWith("/shifts/cashdrop")) {
            return true;
        }

        return false;
    }
}
