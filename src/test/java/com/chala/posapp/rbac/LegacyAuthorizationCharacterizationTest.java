package com.chala.posapp.rbac;

import com.chala.posapp.controller.CustomerController;
import com.chala.posapp.controller.NewReportController;
import com.chala.posapp.controller.OrderController;
import com.chala.posapp.controller.ReportExportJobController;
import com.chala.posapp.controller.ReportScheduleController;
import com.chala.posapp.rbac.enforcement.DynamicPermission;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes the current role boundary before dynamic enforcement is introduced.
 *
 * <p>This intentionally reads annotations only. Service-level branch, ownership, module,
 * subscription, and workflow checks remain independent authorization constraints.</p>
 */
class LegacyAuthorizationCharacterizationTest {

    @Test
    void cashierMayCreateAndReplayOrdersButOrderListingRemainsManagerOrAdminOnly() throws Exception {
        assertThat(expression(OrderController.class, "create")).isEqualTo("hasAnyRole('ADMIN','MANAGER','CASHIER')");
        assertThat(expression(OrderController.class, "importOfflineSale")).isEqualTo("hasAnyRole('ADMIN','MANAGER','CASHIER')");
        assertThat(expression(OrderController.class, "list")).isEqualTo("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')");
    }

    @Test
    void currentOrderCancellationAnnotationAllowsCashierAndMustNotEraseServiceRules() throws Exception {
        assertThat(expression(OrderController.class, "cancel")).isEqualTo("hasAnyRole('ADMIN','MANAGER','CASHIER')");
        assertThat(PermissionCatalog.get("orders.cancel").risk()).isEqualTo(RiskClass.HIGH);
    }

    @Test
    void customerNotesAndHistoryNeedSeparateCharacterizationFromBasicCustomerAccess() throws Exception {
        assertThat(expression(CustomerController.class, "get")).isEqualTo("hasAnyRole('ADMIN','MANAGER','CASHIER')");
        assertThat(expression(CustomerController.class, "update")).isEqualTo("hasAnyRole('ADMIN','MANAGER')");
        assertThat(PermissionCatalog.contains("customers.notes.manage")).isTrue();
        assertThat(PermissionCatalog.contains("customers.orders.view")).isTrue();
    }

    @Test
    void reportAndExportBoundariesAreNotEquivalent() throws Exception {
        assertThat(expression(NewReportController.class, "profitAndLoss")).isEqualTo("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')");
        assertThat(classExpression(ReportExportJobController.class)).isEqualTo("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')");
        assertThat(classExpression(ReportScheduleController.class)).isEqualTo("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')");
        assertThat(PermissionCatalog.get("reports.profit_loss.view").risk()).isEqualTo(RiskClass.HIGH);
        assertThat(PermissionCatalog.get("reports.export.manage").risk()).isEqualTo(RiskClass.HIGH);
    }

    @Test
    void catalogDoesNotPretendToRepresentIndependentSecurityBoundaries() {
        assertThat(PermissionCatalog.contains("subscription.manage")).isFalse();
        assertThat(PermissionCatalog.contains("module.bypass")).isFalse();
        assertThat(PermissionCatalog.contains("branch.scope.bypass")).isFalse();
        assertThat(PermissionCatalog.contains("support.impersonate")).isFalse();
    }

    private static String classExpression(Class<?> controller) {
        return controller.getAnnotation(PreAuthorize.class).value();
    }

    private static String expression(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        if (annotation != null) {
            return annotation.value();
        }
        DynamicPermission dynamic = method.getAnnotation(DynamicPermission.class);
        if (dynamic != null) {
            String roles = Arrays.stream(dynamic.legacyRoles())
                    .map(role -> "'" + role + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            return "hasAnyRole(" + roles + ")";
        }
        throw new AssertionError("Missing legacy authorization metadata on " + controller.getSimpleName() + "." + methodName);
    }
}
