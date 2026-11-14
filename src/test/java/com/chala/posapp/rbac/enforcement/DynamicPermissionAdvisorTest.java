package com.chala.posapp.rbac.enforcement;

import com.chala.posapp.controller.NewReportController;
import com.chala.posapp.controller.OrderController;
import com.chala.posapp.rbac.shadow.AuthorizationEventPublisherDecorator;
import com.chala.posapp.rbac.shadow.TenantRbacShadowEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Pointcut;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DynamicPermissionAdvisorTest {
    @Test
    void advisorMatchesOnlyDynamicMethodsAndUsesPreAuthorizeAdjacentOrder() throws Exception {
        DynamicPermissionAuthorizationManager manager = mock(DynamicPermissionAuthorizationManager.class);
        AuthorizationEventPublisherDecorator publisher = mock(AuthorizationEventPublisherDecorator.class);
        AuthorizationManagerBeforeMethodInterceptor advisor =
                new DynamicPermissionAdvisorConfiguration().dynamicPermissionAdvisor(manager, publisher);
        Pointcut pointcut = advisor.getPointcut();

        Method create = OrderController.class.getDeclaredMethod("create", com.chala.posapp.dto.order.CreateOrderRequest.class);
        Method cancel = OrderController.class.getDeclaredMethod("cancel", String.class, com.chala.posapp.dto.order.CancelOrderRequest.class);
        Method list = OrderController.class.getDeclaredMethod("list", String.class, int.class, int.class, String.class,
                String.class, String.class, String.class, String.class, Long.class, String.class, Double.class);
        Method pnl = NewReportController.class.getDeclaredMethod("profitAndLoss", Long.class,
                java.time.LocalDate.class, java.time.LocalDate.class);

        assertTrue(pointcut.getMethodMatcher().matches(create, OrderController.class));
        assertTrue(pointcut.getMethodMatcher().matches(cancel, OrderController.class));
        assertTrue(pointcut.getMethodMatcher().matches(pnl, NewReportController.class));
        assertFalse(pointcut.getMethodMatcher().matches(list, OrderController.class));
        assertEquals(org.springframework.security.authorization.method.AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() - 1,
                advisor.getOrder());
    }
}
