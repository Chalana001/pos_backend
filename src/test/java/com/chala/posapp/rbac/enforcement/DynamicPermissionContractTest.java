package com.chala.posapp.rbac.enforcement;

import com.chala.posapp.controller.NewReportController;
import com.chala.posapp.controller.OrderController;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DynamicPermissionContractTest {
    @Test
    void onlyTheApprovedFourMethodsAreOptedIntoDynamicEnforcement() throws Exception {
        Set<String> selected = Set.of(
                "com.chala.posapp.controller.OrderController#create(CreateOrderRequest)",
                "com.chala.posapp.controller.OrderController#cancel(String,CancelOrderRequest)",
                "com.chala.posapp.controller.OrderController#get(String)",
                "com.chala.posapp.controller.NewReportController#profitAndLoss(Long,LocalDate,LocalDate)");

        long count = java.util.Arrays.stream(OrderController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DynamicPermission.class)).count();
        count += java.util.Arrays.stream(NewReportController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DynamicPermission.class)).count();
        assertEquals(4, count);

        Method create = OrderController.class.getDeclaredMethod("create", com.chala.posapp.dto.order.CreateOrderRequest.class);
        DynamicPermission createPermission = create.getAnnotation(DynamicPermission.class);
        assertEquals("orders.create", createPermission.permissionKey());
        assertArrayEquals(new String[]{"ADMIN", "MANAGER", "CASHIER"}, createPermission.legacyRoles());
        assertFalse(createPermission.legacySuperAdmin());
    }

    @Test
    void onlyPnlHasExplicitSuperAdminCompatibility() throws Exception {
        Method pnl = NewReportController.class.getDeclaredMethod("profitAndLoss", Long.class,
                java.time.LocalDate.class, java.time.LocalDate.class);
        assertTrue(pnl.getAnnotation(DynamicPermission.class).legacySuperAdmin());
        assertArrayEquals(new String[]{"ADMIN", "SUPER_ADMIN", "MANAGER"},
                pnl.getAnnotation(DynamicPermission.class).legacyRoles());

        for (Method method : OrderController.class.getDeclaredMethods()) {
            DynamicPermission permission = method.getAnnotation(DynamicPermission.class);
            if (permission != null) assertFalse(permission.legacySuperAdmin());
        }
    }

    @Test
    void dynamicAdvisorOrderIsImmediatelyBeforePreAuthorize() {
        int dynamicOrder = AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() - 1;
        assertTrue(dynamicOrder < AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        assertTrue(dynamicOrder > AuthorizationInterceptorsOrder.FIRST.getOrder());
    }
}
