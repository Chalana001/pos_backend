package com.chala.posapp.rbac.enforcement;

import com.chala.posapp.rbac.shadow.ShadowDecision;
import com.chala.posapp.rbac.shadow.ShadowEvaluationResult;
import com.chala.posapp.rbac.shadow.ShadowEvaluationStatus;
import com.chala.posapp.rbac.shadow.TenantRbacShadowEvaluator;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Opt-in dynamic capability manager. Legacy role metadata is retained for disabled/fallback mode,
 * while migrated ready tenants use the tenant-local evaluator and fail closed on evaluator errors.
 */
@Component
public final class DynamicPermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {
    private final TenantRbacShadowEvaluator evaluator;
    private final boolean enforcementEnabled;

    public DynamicPermissionAuthorizationManager(TenantRbacShadowEvaluator evaluator,
                                                  @Value("${app.rbac.enforcement-enabled:false}") boolean enforcementEnabled) {
        this.evaluator = evaluator;
        this.enforcementEnabled = enforcementEnabled;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         MethodInvocation invocation) {
        DynamicPermission permission = findPermission(invocation.getMethod());
        if (permission == null) {
            return new AuthorizationDecision(false);
        }
        if (!enforcementEnabled) {
            return legacyDecision(authentication, permission);
        }

        ShadowEvaluationResult result = evaluator.evaluateForEnforcement(permission.permissionKey());
        if (result.classification() == com.chala.posapp.rbac.shadow.ShadowClassification.LEGACY_ONLY
                || result.classification() == com.chala.posapp.rbac.shadow.ShadowClassification.PLATFORM_ONLY) {
            return legacyDecision(authentication, permission);
        }
        if (result.status() != ShadowEvaluationStatus.EVALUATED) {
            return new AuthorizationDecision(false);
        }
        if (result.decision() == ShadowDecision.ALLOW) {
            if (result.legacyRole() != null && result.legacyRole().name().equals("SUPER_ADMIN")
                    && !permission.legacySuperAdmin()) {
                return new AuthorizationDecision(false);
            }
            return new AuthorizationDecision(true);
        }
        return new AuthorizationDecision(false);
    }

    private AuthorizationResult legacyDecision(Supplier<? extends Authentication> authentication,
                                                DynamicPermission permission) {
        Authentication current = authentication == null ? null : authentication.get();
        if (current == null || !current.isAuthenticated()) return new AuthorizationDecision(false);
        boolean allowed = current.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(authority -> {
                    String role = authority.startsWith("ROLE_") ? authority.substring(5) : authority;
                    return java.util.Arrays.asList(permission.legacyRoles()).contains(role)
                            || (permission.legacySuperAdmin() && "SUPER_ADMIN".equals(role));
                });
        return new AuthorizationDecision(allowed);
    }

    private DynamicPermission findPermission(Method method) {
        DynamicPermission direct = method.getAnnotation(DynamicPermission.class);
        if (direct != null) return direct;
        try {
            Method mostSpecific = method.getDeclaringClass().getMethod(method.getName(), method.getParameterTypes());
            return mostSpecific.getAnnotation(DynamicPermission.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
