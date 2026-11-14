package com.chala.posapp.rbac.shadow;

import com.chala.posapp.dto.customer.CustomerNoteCreateRequest;
import com.chala.posapp.dto.customer.CustomerNoteUpdateRequest;
import com.chala.posapp.dto.order.CancelOrderRequest;
import com.chala.posapp.dto.order.CreateOrderRequest;
import com.chala.posapp.dto.order.OfflineSaleImportRequest;
import com.chala.posapp.dto.promotion.PromotionRequest;
import com.chala.posapp.dto.report.ReportExportJobRequest;
import com.chala.posapp.dto.report.ReportScheduleRequest;
import com.chala.posapp.dto.user.CreateUserRequest;
import com.chala.posapp.rbac.PermissionCatalog;
import com.chala.posapp.service.OrderService;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable source-of-truth mapping for shadow observations. */
public final class LegacyPermissionMappingCatalog {
    private static final List<LegacyPermissionMapping> ALL = List.of(
            controller("OrderController", "create", new Class<?>[]{CreateOrderRequest.class}, "orders.create", true, "POST", "/orders", "/api/orders"),
            controller("OrderController", "importOfflineSale", new Class<?>[]{OfflineSaleImportRequest.class}, "offline.sales.import", true, "POST", "/orders/offline-import", "/api/orders/offline-import"),
            controller("OrderController", "cancel", new Class<?>[]{String.class, CancelOrderRequest.class}, "orders.cancel", true, "POST", "/orders/{invoiceNo}/cancel", "/api/orders/{invoiceNo}/cancel"),
            controller("OrderController", "get", new Class<?>[]{String.class}, "orders.view", false, "GET", "/orders/{invoiceNo}", "/api/orders/{invoiceNo}"),
            controllerClassified("CustomerNotesController", "list", new Class<?>[]{Long.class, int.class, int.class}, "customers.notes.manage", false, "GET", ShadowClassification.LEGACY_UNCHECKED_AUTHENTICATED, "/customers/{customerId}/notes", "/api/customers/{customerId}/notes"),
            controllerClassified("CustomerNotesController", "create", new Class<?>[]{Long.class, CustomerNoteCreateRequest.class}, "customers.notes.manage", false, "POST", ShadowClassification.LEGACY_UNCHECKED_AUTHENTICATED, "/customers/{customerId}/notes", "/api/customers/{customerId}/notes"),
            controllerClassified("CustomerNotesController", "update", new Class<?>[]{Long.class, CustomerNoteUpdateRequest.class}, "customers.notes.manage", false, "PUT", ShadowClassification.LEGACY_UNCHECKED_AUTHENTICATED, "/customer-notes/{noteId}", "/api/customer-notes/{noteId}"),
            controllerClassified("CustomerNotesController", "delete", new Class<?>[]{Long.class}, "customers.notes.manage", false, "DELETE", ShadowClassification.LEGACY_UNCHECKED_AUTHENTICATED, "/customer-notes/{noteId}", "/api/customer-notes/{noteId}"),
            controllerClassified("CustomerOrdersController", "list", new Class<?>[]{Long.class, String.class, int.class, int.class}, "customers.orders.view", false, "GET", ShadowClassification.LEGACY_UNCHECKED_AUTHENTICATED, "/customers/{customerId}/orders", "/api/customers/{customerId}/orders"),
            controller("NewReportController", "profitAndLoss", new Class<?>[]{Long.class, java.time.LocalDate.class, java.time.LocalDate.class}, "reports.profit_loss.view", true, "GET", "/api/reports/v2/profit-loss"),
            controller("ReportExportJobController", "create", new Class<?>[]{ReportExportJobRequest.class}, "reports.export.create", true, "POST", "/reports/export-jobs", "/api/reports/export-jobs"),
            controller("ReportScheduleController", "create", new Class<?>[]{ReportScheduleRequest.class}, "reports.schedule.manage", true, "POST", "/reports/schedules", "/api/reports/schedules"),
            controller("PromotionController", "create", new Class<?>[]{PromotionRequest.class}, "promotions.manage", true, "POST", "/promotions", "/api/promotions"),
            controller("UserManagementController", "create", new Class<?>[]{CreateUserRequest.class}, "users.manage", true, "POST", "/users", "/api/users"),
            service(OrderService.class, "createOrder", new Class<?>[]{CreateOrderRequest.class}, "orders.create", true),
            service(OrderService.class, "importOfflineSale", new Class<?>[]{OfflineSaleImportRequest.class}, "offline.sales.import", true),
            service(OrderService.class, "cancelOrder", new Class<?>[]{String.class, CancelOrderRequest.class}, "orders.cancel", true),
            service(OrderService.class, "getAllOrders", new Class<?>[]{String.class, int.class, int.class, String.class, String.class, String.class, String.class, String.class, Long.class, String.class, Double.class}, "orders.view", false),
            service(OrderService.class, "list", new Class<?>[]{Long.class, String.class, org.springframework.data.domain.Pageable.class}, "customers.orders.view", false)
    );

    private static final Map<SecuredMethodIdentity, LegacyPermissionMapping> BY_METHOD = index(ALL);

    private LegacyPermissionMappingCatalog() {
    }

    public static List<LegacyPermissionMapping> all() {
        return ALL;
    }

    public static Optional<LegacyPermissionMapping> find(SecuredMethodIdentity identity) {
        return Optional.ofNullable(BY_METHOD.get(identity));
    }

    private static LegacyPermissionMapping controller(String simpleName, String method, Class<?>[] params,
                                                       String key, boolean selected, String httpMethod,
                                                       String... routes) {
        return mapping(load("com.chala.posapp.controller." + simpleName), method, params, key, true,
                selected, List.of(httpMethod), List.of(routes), ShadowClassification.MAPPED_CAPABILITY);
    }

    private static LegacyPermissionMapping controllerClassified(String simpleName, String method, Class<?>[] params,
                                                                 String key, boolean selected, String httpMethod,
                                                                 ShadowClassification classification, String... routes) {
        return mapping(load("com.chala.posapp.controller." + simpleName), method, params, key, true,
                selected, List.of(httpMethod), List.of(routes), classification);
    }

    private static LegacyPermissionMapping service(Class<?> type, String method, Class<?>[] params,
                                                    String key, boolean selected) {
        return mapping(type, method, params, key, false, selected, List.of(), List.of(), ShadowClassification.MAPPED_CAPABILITY);
    }

    private static LegacyPermissionMapping mapping(Class<?> type, String method, Class<?>[] params,
                                                    String key, boolean controller, boolean selected,
                                                    List<String> httpMethods, List<String> routes,
                                                    ShadowClassification classification) {
        try {
            Method reflected = type.getDeclaredMethod(method, params);
            return new LegacyPermissionMapping(SecuredMethodIdentity.of(reflected), controller,
                    httpMethods, routes, key, classification,
                    classification == ShadowClassification.MAPPED_CAPABILITY
                            ? "Independent branch/ownership/workflow rules remain outside capability."
                            : "Route has no method-security annotation.", selected);
        } catch (NoSuchMethodException ex) {
            throw new ExceptionInInitializerError(type.getName() + "#" + method + " has no exact mapped signature");
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Map<SecuredMethodIdentity, LegacyPermissionMapping> index(List<LegacyPermissionMapping> mappings) {
        Map<SecuredMethodIdentity, LegacyPermissionMapping> result = new LinkedHashMap<>();
        for (LegacyPermissionMapping mapping : mappings) {
            if (mapping.classification() == ShadowClassification.MAPPED_CAPABILITY) {
                var definition = PermissionCatalog.get(mapping.permissionKey());
                if (!definition.tenantGrantable() || definition.protectedPermission()) {
                    throw new IllegalStateException("Invalid ordinary mapping: " + mapping.permissionKey());
                }
            }
            if (result.put(mapping.method(), mapping) != null) {
                throw new IllegalStateException("Duplicate secured method mapping: " + mapping.method().compact());
            }
        }
        return Map.copyOf(result);
    }
}
