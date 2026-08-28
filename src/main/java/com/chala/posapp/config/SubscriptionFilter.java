package com.chala.posapp.config;

import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.module.ModuleDefinition;
import com.chala.posapp.module.ModuleRouteResolver;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.service.ModuleAccessService;
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
import java.util.Optional;

/**
 * Gates a request on the shop's subscription state and its enabled modules.
 *
 * <p>Two checks, in order:
 * <ol>
 *   <li><strong>Subscription</strong> — inactive, blocked or expired returns 402.</li>
 *   <li><strong>Module</strong> — the module owning this route is switched off, returns 403.</li>
 * </ol>
 *
 * <p>The module check used to be two hardcoded {@code Set<String>} prefix lists keyed on the
 * plan <em>name</em>, which meant a per-shop exception was impossible and every new controller
 * was ungated by default. It now resolves against the module registry
 * ({@link ModuleCatalog} → {@code plan_modules} → {@code tenant_modules}), so what the super
 * admin panel shows and what the API enforces cannot drift apart.
 *
 * <p>Both failures return a machine-readable {@code code} so the POS app can tell "renew your
 * subscription" apart from "this module is not on your package" without string-matching the
 * message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionFilter extends OncePerRequestFilter {

    private final TenantSubscriptionRepository subscriptionRepository;
    private final PlatformTransactionManager transactionManager;
    private final ModuleRouteResolver routeResolver;
    private final ModuleAccessService moduleAccessService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String tenantId = TenantContext.getTenant();
        String path = request.getRequestURI();
        String method = request.getMethod();
        String normalizedPath = routeResolver.normalize(path);

        if (tenantId == null
                || tenantId.equals("MASTER")
                || routeResolver.isSubscriptionExempt(normalizedPath, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<TenantSubscription> subscriptionOpt = findSubscriptionInMaster(tenantId);

        // Maintenance is checked before the paywall on purpose: telling a shop to renew
        // while its data is mid-migration would be both wrong and alarming. Different
        // status too — 503 says "come back shortly", 402 says "you have not paid".
        if (subscriptionOpt.isPresent() && subscriptionOpt.get().isMaintenanceMode()) {
            String maintenanceMessage = subscriptionOpt.get().getMaintenanceMessage();
            log.info("Maintenance mode active for tenant: {}", tenantId);
            writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MAINTENANCE_MODE",
                    maintenanceMessage == null || maintenanceMessage.isBlank()
                            ? "This shop is temporarily unavailable while we carry out maintenance."
                            : maintenanceMessage,
                    null);
            return;
        }

        // getAccessEndsAt() is validUntil plus any grace days, so a shop that lapsed on a
        // Friday evening keeps trading through a configured grace window instead of stopping
        // dead before anyone can be reached.
        if (subscriptionOpt.isEmpty()
                || !subscriptionOpt.get().isActive()
                || subscriptionOpt.get().isBlocked()
                || subscriptionOpt.get().getAccessEndsAt().isBefore(LocalDateTime.now())) {

            log.warn("Subscription blocked for tenant: {}", tenantId);
            writeJson(response, HttpServletResponse.SC_PAYMENT_REQUIRED,
                    "SUBSCRIPTION_INACTIVE",
                    "Subscription expired or inactive. Please contact support or renew your plan.",
                    null);
            return;
        }

        // Inside the grace window the shop still works, but every response says so, so the
        // POS app can nag without the operator having to remember to warn them.
        if (subscriptionOpt.get().isWithinGrace()) {
            response.setHeader("X-Subscription-Grace", "true");
            response.setHeader("X-Subscription-Access-Ends",
                    subscriptionOpt.get().getAccessEndsAt().toString());
        }

        // The boot reads (branch list, configuration, categories) are exempt from the module
        // check but NOT from the subscription check above — an expired shop is still stopped.
        if (routeResolver.isModuleExempt(normalizedPath, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<String> owningModule = routeResolver.resolve(normalizedPath, method);
        if (owningModule.isPresent() && !moduleAccessService.isEnabled(tenantId, owningModule.get())) {
            String moduleKey = owningModule.get();
            ModuleDefinition definition = ModuleCatalog.byKey(moduleKey);
            String label = definition != null ? definition.name() : moduleKey;

            log.warn("Module disabled. tenant={}, module={}, path={} {}", tenantId, moduleKey, method, path);
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    "MODULE_DISABLED",
                    label + " is not included in your package. Please contact support to enable it.",
                    moduleKey);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Optional<TenantSubscription> findSubscriptionInMaster(String tenantId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return TenantContext.callWith("MASTER",
                () -> tx.execute(status -> subscriptionRepository.findByTenantId(tenantId)));
    }

    private void writeJson(HttpServletResponse response, int status, String code, String message, String moduleKey)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        StringBuilder body = new StringBuilder()
                .append("{\"error\":\"").append(escape(message)).append('"')
                .append(",\"code\":\"").append(code).append('"');
        if (moduleKey != null) {
            body.append(",\"module\":\"").append(escape(moduleKey)).append('"');
        }
        body.append('}');
        response.getWriter().write(body.toString());
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
