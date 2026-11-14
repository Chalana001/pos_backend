package com.chala.posapp.integration;

import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.rbac.shadow.ShadowDecision;
import com.chala.posapp.rbac.shadow.TenantRbacShadowEvaluator;
import com.chala.posapp.service.TenantProvisioningService;
import com.chala.posapp.tenant.CurrentTenantResolver;
import com.chala.posapp.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@TestPropertySource(properties = {
        "app.tenant.provisioning.enabled=true",
        "app.rbac.enforcement-enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class DynamicRbacIntegrationVerificationTest extends ApiIntegrationTestSupport {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                        + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private TenantProvisioningService provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantRbacShadowEvaluator evaluator;

    private String tenantId;
    private String catalog;
    private String customUsername;
    private String customToken;
    private String adminToken;
    private String fixtureAdminToken;
    private long customUserId;
    private long customRoleId;
    private long adminUserId;
    private long branchId;
    private long itemId;
    private long batchId;

    @BeforeEach
    void provisionMigratedTenantAndSeedCustomRole() throws Exception {
        tenantId = uniqueKey("dynamic-rbac");
        catalog = provisioning.provisionTenantDatabase(tenantId);
        seedSubscription(tenantId);
        branchId = insertBranch();
        String fixtureAdmin = tenantId + "-fixture-admin";
        insertTenantUser(fixtureAdmin, Role.ADMIN, null);
        fixtureAdminToken = login(tenantId, fixtureAdmin, DEFAULT_PASSWORD);
        seedSellableStock();

        customUsername = tenantId + "-custom";
        customUserId = insertTenantUser(customUsername, Role.CASHIER, branchId);
        customRoleId = insertCustomRole("CUSTOM_" + tenantId.replace('-', '_').toUpperCase(Locale.ROOT));
        jdbc.update("UPDATE `" + catalog + "`.users SET role_id = ? WHERE id = ?", customRoleId, customUserId);
        grant("orders.create");
        customToken = login(tenantId, customUsername, DEFAULT_PASSWORD);

        String adminUsername = tenantId + "-admin";
        adminUserId = insertTenantUser(adminUsername, Role.ADMIN, null);
        adminToken = login(tenantId, adminUsername, DEFAULT_PASSWORD);
    }

    @Test
    void customRoleGrantAuthorizesActualOrderCreate() throws Exception {
        String body = """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [{"itemId": %d, "batchId": %d, "qty": 1, "unitPrice": 100, "discountType": "NONE"}],
                  "billDiscount": 0,
                  "paymentMethod": "CASH",
                  "tenderedAmount": 100
                }
                """.formatted(branchId, itemId, batchId);

        int status = statusForPost("/orders", tenantId, customToken, body);

        assertTrue(status != 403, "custom role grant must pass dynamic authorization; business validation may reject fixture");
    }

    @Test
    void customRoleGrantAuthorizesOrderViewAndCancelIndependently() throws Exception {
        grant("orders.view");
        grant("orders.cancel");
        grant("orders.create");
        int created = statusForPostWithBody("/orders", tenantId, customToken, orderRequestBody());
        assertTrue(created != 403, "orders.create grant must pass dynamic authorization");

        long orderId = jdbc.queryForObject("SELECT id FROM `" + catalog + "`.orders ORDER BY id DESC LIMIT 1", Long.class);
        String invoiceNo = jdbc.queryForObject("SELECT invoice_no FROM `" + catalog + "`.orders WHERE id = ?", String.class, orderId);
        assertTrue(statusForGet("/orders/" + invoiceNo, tenantId, customToken) != 403,
                "orders.view grant must pass dynamic authorization");
        assertTrue(statusForPostWithBody("/orders/" + invoiceNo + "/cancel", tenantId, customToken,
                "{\"reason\":\"integration verification\"}") != 403,
                "orders.cancel grant must pass dynamic authorization");
    }

    @Test
    void customRoleGrantAuthorizesProfitAndLossIndependently() throws Exception {
        grant("reports.profit_loss.view");
        int status = statusForGet("/api/reports/v2/profit-loss?from=2025-01-01&to=2025-01-31", tenantId, customToken);
        assertTrue(status != 403, "reports.profit_loss.view grant must pass dynamic authorization");
    }

    @Test
    void protectedPermissionFloorIgnoresIndividualDenyAndUnsupportedAllow() {
        overrideFor(customUserId, "users.admin.floor", "DENY");
        asUser(customUsername);
        var deniedOverride = TenantContext.callWith(tenantId, () -> evaluator.evaluateForEnforcement("users.admin.floor"));
        assertEquals(ShadowDecision.DENY, deniedOverride.decision(), "non-admin custom role remains denied");

        overrideFor(customUserId, "users.admin.floor", "ALLOW");
        var unsupportedAllow = TenantContext.callWith(tenantId, () -> evaluator.evaluateForEnforcement("users.admin.floor"));
        assertEquals(ShadowDecision.DENY, unsupportedAllow.decision(), "protected permission ALLOW must be ignored");

        jdbc.update("UPDATE `" + catalog + "`.users SET role_id = (SELECT id FROM `" + catalog + "`.roles WHERE role_key = 'DEFAULT_ADMIN') WHERE id = ?", adminUserId);
        overrideFor(adminUserId, "users.admin.floor", "DENY");
        asUser(tenantId + "-admin");
        var adminFloor = TenantContext.callWith(tenantId, () -> evaluator.evaluateForEnforcement("users.admin.floor"));
        assertEquals(ShadowDecision.ALLOW, adminFloor.decision(), "protected floor must remain allowed for ADMIN despite DENY");
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyEnumDoesNotBypassDynamicDeny() throws Exception {
        revoke("orders.create");

        int status = statusForPost("/orders", tenantId, customToken, orderRequestBody());

        assertEquals(403, status);
    }

    @Test
    void individualDenyOverridesRoleGrantAndAllowWorksWithoutRoleGrant() throws Exception {
        grant("orders.create");
        override("orders.create", "DENY");
        assertEquals(403, statusForPost("/orders", tenantId, customToken, "{\"branchId\":1,\"orderType\":\"CASH\",\"items\":[],\"billDiscount\":0,\"paymentMethod\":\"CASH\"}"));

        override("orders.create", "ALLOW");
        revoke("orders.create");
        assertTrue(statusForPost("/orders", tenantId, customToken, "{\"branchId\":1,\"orderType\":\"CASH\",\"items\":[],\"billDiscount\":0,\"paymentMethod\":\"CASH\"}") != 403);

        long replacementRole = insertCustomRole("REASSIGNED_" + tenantId.replace('-', '_').toUpperCase(Locale.ROOT));
        jdbc.update("UPDATE `" + catalog + "`.users SET role_id = ? WHERE id = ?", replacementRole, customUserId);
        assertTrue(statusForPost("/orders", tenantId, customToken, orderRequestBody()) != 403);
    }

    @Test
    void superAdminCompatibilityIsPerMethod() throws Exception {
        String username = tenantId + "-super";
        insertTenantUser(username, Role.SUPER_ADMIN, null);
        String token = login(tenantId, username, DEFAULT_PASSWORD);

        int pnl = statusForGet("/api/reports/v2/profit-loss?from=2025-01-01&to=2025-01-31", tenantId, token);
        int create = statusForPost("/orders", tenantId, token, "{\"branchId\":1,\"orderType\":\"CASH\",\"items\":[],\"billDiscount\":0,\"paymentMethod\":\"CASH\"}");

        assertTrue(pnl != 403, "P&L has explicit SUPER_ADMIN compatibility; downstream report rules may reject fixture");
        assertEquals(403, create);
    }


    private void asUser(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null));
    }

    private String orderRequestBody() {
        return "{\"branchId\":" + branchId + ",\"orderType\":\"CASH\",\"items\":[{\"itemId\":" + itemId + ",\"batchId\":" + batchId + ",\"qty\":1,\"unitPrice\":100,\"discountType\":\"NONE\"}],\"billDiscount\":0,\"paymentMethod\":\"CASH\",\"tenderedAmount\":100}";
    }

    private void seedSellableStock() {
        Category category = new Category();
        category.setName("Dynamic category " + tenantId);
        Category savedCategory = category;
        category = TenantContext.callWith(tenantId, () -> categoryRepository.save(savedCategory));
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Dynamic subcategory " + tenantId);
        subCategory.setCategory(category);
        SubCategory savedSubCategory = subCategory;
        subCategory = TenantContext.callWith(tenantId, () -> subCategoryRepository.save(savedSubCategory));
        Supplier supplier = new Supplier();
        supplier.setName("Dynamic supplier " + tenantId);
        supplier.setPhone("0770000000");
        supplier.setAddress("Test address");
        supplier.setActive(true);
        Supplier savedSupplier = supplier;
        supplier = TenantContext.callWith(tenantId, () -> supplierRepository.save(savedSupplier));
        com.fasterxml.jackson.databind.JsonNode item = null;
        try {
            item = postJson("/items", tenantId, fixtureAdminToken, "{\"barcode\":\"DYN-" + tenantId + "\",\"name\":\"Dynamic item\",\"subCategoryId\":" + subCategory.getId() + ",\"costPrice\":50,\"sellingPrice\":100,\"reorderLevel\":1}");
            itemId = item.path("id").asLong();
            com.fasterxml.jackson.databind.JsonNode purchase = postJson("/purchases", tenantId, fixtureAdminToken, "{\"supplierId\":" + supplier.getId() + ",\"invoiceNo\":\"DYN-INV-" + tenantId + "\",\"branches\":[{\"branchId\":" + branchId + ",\"items\":[{\"itemId\":" + itemId + ",\"qty\":10,\"costPrice\":50,\"sellingPrice\":100}]}]}");
            if (purchase == null || purchase.isMissingNode()) {
                throw new IllegalStateException("Dynamic purchase fixture returned no response");
            }
            batchId = jdbc.queryForObject("SELECT id FROM `" + catalog + "`.stock_batches WHERE branch_id = ? AND item_id = ? ORDER BY id LIMIT 1", Long.class, branchId, itemId);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to seed dynamic RBAC stock fixture", ex);
        }
    }

    private String loginAsAdminForFixture() throws Exception {
        String username = tenantId + "-fixture-admin";
        insertTenantUser(username, Role.ADMIN, null);
        return login(tenantId, username, DEFAULT_PASSWORD);
    }

    private void seedSubscription(String tenant) {
        TenantContext.runWith(CurrentTenantResolver.MASTER_TENANT, () -> {
            SubscriptionPlan plan = subscriptionPlanRepository.findByName("DYNAMIC_TEST_PLAN")
                    .orElseGet(() -> subscriptionPlanRepository.save(SubscriptionPlan.builder()
                            .name("DYNAMIC_TEST_PLAN")
                            .billingCycle(BillingCycle.MONTHLY)
                            .initialPrice(2000.0)
                            .renewalPrice(2000.0)
                            .maxBranches(2)
                            .build()));
            tenantSubscriptionRepository.save(TenantSubscription.builder()
                    .tenantId(tenant)
                    .shopName("Dynamic RBAC Shop")
                    .adminUsername(tenant + "-admin")
                    .plan(plan)
                    .isActive(true)
                    .blocked(false)
                    .extraBranches(0)
                    .validUntil(java.time.LocalDateTime.now().plusDays(30))
                    .build());
        });
    }

    private long insertBranch() {
        jdbc.update("INSERT INTO `" + catalog + "`.branches (code,name,address,phone,active) VALUES (?,?,?,?,1)",
                "DYNAMIC_MAIN", "Dynamic Main", "Test address", "0770000000");
        return jdbc.queryForObject("SELECT id FROM `" + catalog + "`.branches WHERE code = 'DYNAMIC_MAIN'", Long.class);
    }

    private long insertTenantUser(String username, Role role, Long branchId) {
        jdbc.update("INSERT INTO `" + catalog + "`.users (username,password_hash,role,enabled,branch_id) VALUES (?,?,?,?,?)",
                username, passwordEncoder.encode(DEFAULT_PASSWORD), role.name(), true, branchId);
        long userId = jdbc.queryForObject("SELECT id FROM `" + catalog + "`.users WHERE username = ?", Long.class, username);
        if (role != Role.SUPER_ADMIN) {
            jdbc.update("UPDATE `" + catalog + "`.users u "
                    + "JOIN `" + catalog + "`.roles r "
                    + "ON r.legacy_compatibility = ? AND r.is_system = TRUE AND r.is_active = TRUE "
                    + "SET u.role_id = r.id, u.rbac_version = 1 WHERE u.id = ?",
                    role.name(), userId);
        }
        return userId;
    }

    private long insertCustomRole(String roleKey) {
        jdbc.update("INSERT INTO `" + catalog + "`.roles (role_key,display_name,is_system,is_protected,is_active) VALUES (?,?,0,0,1)",
                roleKey, roleKey);
        return jdbc.queryForObject("SELECT id FROM `" + catalog + "`.roles WHERE role_key = ?", Long.class, roleKey);
    }

    private void grant(String permission) {
        jdbc.update("INSERT IGNORE INTO `" + catalog + "`.role_permissions (role_id,permission_key) VALUES (?,?)", customRoleId, permission);
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM `" + catalog + "`.role_permissions WHERE role_id = ? AND permission_key = ?", customRoleId, permission);
    }

    private void override(String permission, String state) {
        overrideFor(customUserId, permission, state);
    }

    private void overrideFor(long userId, String permission, String state) {
        jdbc.update("INSERT INTO `" + catalog + "`.user_permission_overrides (user_id,permission_key,override_state,updated_by_user_id) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE override_state = ?, updated_by_user_id = ?",
                userId, permission, state, adminUserId, state, adminUserId);
    }

    private int statusForPost(String path, String tenant, String token, String body) throws Exception {
        return statusForPostWithBody(path, tenant, token, body);
    }

    private int statusForPostWithBody(String path, String tenant, String token, String body) throws Exception {
        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("X-Tenant-ID", tenant)
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
        return response.getStatus();
    }

    private int statusForGet(String path, String tenant, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("X-Tenant-ID", tenant)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }
}
