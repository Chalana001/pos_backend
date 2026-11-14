package com.chala.posapp.rbac.enforcement;

import com.chala.posapp.rbac.shadow.ShadowClassification;
import com.chala.posapp.rbac.shadow.ShadowDecision;
import com.chala.posapp.rbac.shadow.ShadowEvaluationResult;
import com.chala.posapp.rbac.shadow.ShadowEvaluationStatus;
import com.chala.posapp.rbac.shadow.ShadowReason;
import com.chala.posapp.rbac.shadow.TenantRbacShadowEvaluator;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicPermissionAuthorizationManagerTest {
    @Test
    void disabledEnforcementReproducesLegacyRoleBoundary() throws Exception {
        TenantRbacShadowEvaluator evaluator = mock(TenantRbacShadowEvaluator.class);
        DynamicPermissionAuthorizationManager manager = new DynamicPermissionAuthorizationManager(evaluator, false);
        Method method = com.chala.posapp.controller.OrderController.class
                .getDeclaredMethod("create", com.chala.posapp.dto.order.CreateOrderRequest.class);
        MethodInvocation invocation = invocation(method);

        assertTrue(manager.authorize(auth("CASHIER"), invocation).isGranted());
        assertFalse(manager.authorize(auth("AUDITOR"), invocation).isGranted());
        verifyNoInteractions(evaluator);
    }

    @Test
    void disabledPnlPreservesExplicitSuperAdminCompatibilityOnlyForThatMethod() throws Exception {
        TenantRbacShadowEvaluator evaluator = mock(TenantRbacShadowEvaluator.class);
        DynamicPermissionAuthorizationManager manager = new DynamicPermissionAuthorizationManager(evaluator, false);
        Method method = com.chala.posapp.controller.NewReportController.class
                .getDeclaredMethod("profitAndLoss", Long.class, java.time.LocalDate.class, java.time.LocalDate.class);

        assertTrue(manager.authorize(auth("SUPER_ADMIN"), invocation(method)).isGranted());
    }

    @Test
    void migratedReadyTenantUsesDynamicResultAndFailsClosedOnUnready() throws Exception {
        TenantRbacShadowEvaluator evaluator = mock(TenantRbacShadowEvaluator.class);
        DynamicPermissionAuthorizationManager manager = new DynamicPermissionAuthorizationManager(evaluator, true);
        Method method = com.chala.posapp.controller.OrderController.class
                .getDeclaredMethod("create", com.chala.posapp.dto.order.CreateOrderRequest.class);
        when(evaluator.evaluateForEnforcement("orders.create")).thenReturn(result(ShadowDecision.ALLOW, ShadowEvaluationStatus.EVALUATED, ShadowClassification.MAPPED_CAPABILITY));
        assertTrue(manager.authorize(auth("AUDITOR"), invocation(method)).isGranted());

        when(evaluator.evaluateForEnforcement("orders.create")).thenReturn(result(ShadowDecision.UNKNOWN, ShadowEvaluationStatus.NOT_READY, ShadowClassification.MAPPED_CAPABILITY));
        assertFalse(manager.authorize(auth("ADMIN"), invocation(method)).isGranted());
    }

    @Test
    void unknownMethodWithoutMarkerIsDeniedByManagerAndCannotBecomeAllowAll() throws Exception {
        TenantRbacShadowEvaluator evaluator = mock(TenantRbacShadowEvaluator.class);
        DynamicPermissionAuthorizationManager manager = new DynamicPermissionAuthorizationManager(evaluator, false);
        Method method = Object.class.getDeclaredMethod("toString");
        assertFalse(manager.authorize(auth("ADMIN"), invocation(method)).isGranted());
    }

    private static Supplier<Authentication> auth(String role) {
        return () -> new TestingAuthenticationToken("user", "password", "ROLE_" + role);
    }

    private static MethodInvocation invocation(Method method) {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        return invocation;
    }

    private static ShadowEvaluationResult result(ShadowDecision decision, ShadowEvaluationStatus status,
                                                  ShadowClassification classification) {
        return new ShadowEvaluationResult("orders.create", decision, status, classification,
                ShadowReason.ROLE_GRANT, 1L, 2L, 1L, null, "trusted-id", Duration.ZERO);
    }
}
