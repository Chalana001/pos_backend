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
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionFilter extends OncePerRequestFilter {

    private final TenantSubscriptionRepository subscriptionRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String tenantId = TenantContext.getTenant();
        String path = request.getRequestURI();

        if (tenantId == null ||
                tenantId.equals("MASTER") ||
                path.startsWith("/auth/") ||
                path.startsWith("/api/saas/")) {

            filterChain.doFilter(request, response);
            return;
        }

        Optional<TenantSubscription> subscriptionOpt = subscriptionRepository.findByTenantId(tenantId);

        if (subscriptionOpt.isEmpty() ||
                !subscriptionOpt.get().isActive() ||
                subscriptionOpt.get().getValidUntil().isBefore(LocalDateTime.now())) {

            log.warn("Subscription blocked for tenant: {}", tenantId);

            response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Subscription expired or inactive. Please contact support or renew your plan.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}