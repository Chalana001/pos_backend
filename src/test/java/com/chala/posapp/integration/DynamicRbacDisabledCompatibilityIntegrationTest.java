package com.chala.posapp.integration;

import com.chala.posapp.entity.Role;
import com.chala.posapp.service.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@TestPropertySource(properties = {
        "app.tenant.provisioning.enabled=true",
        "app.rbac.enforcement-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class DynamicRbacDisabledCompatibilityIntegrationTest extends ApiIntegrationTestSupport {
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

    private String tenantId;
    private String catalog;
    private long branchId;
    private String cashierToken;
    private String customToken;

    @BeforeEach
    void provisionTenantAndUsers() throws Exception {
        tenantId = uniqueKey("dynamic-disabled");
        catalog = provisioning.provisionTenantDatabase(tenantId);
        seedSubscription(tenantId);
        branchId = insertBranch();

        String cashier = tenantId + "-cashier";
        insertTenantUser(cashier, Role.CASHIER, branchId);
        cashierToken = login(tenantId, cashier, DEFAULT_PASSWORD);

        String custom = tenantId + "-custom";
        insertTenantUser(custom, Role.CASHIER, branchId);
        customToken = login(tenantId, custom, DEFAULT_PASSWORD);
    }

    @Test
    void disabledEnforcementPreservesCashierCreateCompatibility() throws Exception {
        int status = statusForPost("/orders", tenantId, cashierToken, validOrderRequest());
        assertNotEquals(403, status, "disabled enforcement must retain CASHIER legacy access");
    }

    @Test
    void disabledEnforcementDoesNotGrantNonLegacyCustomIdentity() throws Exception {
        jdbc.update("UPDATE `" + catalog + "`.users SET role = 'CASHIER' WHERE username = ?", tenantId + "-custom");
        int status = statusForPost("/orders", tenantId, customToken, validOrderRequest());
        assertNotEquals(403, status, "legacy enum role remains source of compatibility when disabled");
    }

    @Test
    void disabledEnforcementPreservesSuperAdminPnlCompatibility() throws Exception {
        String username = tenantId + "-super";
        insertTenantUser(username, Role.SUPER_ADMIN, null);
        String token = login(tenantId, username, DEFAULT_PASSWORD);
        int status = statusForGet("/api/reports/v2/profit-loss?from=2025-01-01&to=2025-01-31", tenantId, token);
        assertNotEquals(403, status);
    }

    private String validOrderRequest() {
        return "{\"branchId\":" + branchId + ",\"orderType\":\"CASH\",\"items\":[],\"billDiscount\":0,\"paymentMethod\":\"CASH\"}";
    }

    private void seedSubscription(String tenant) {
        com.chala.posapp.tenant.TenantContext.runWith(com.chala.posapp.tenant.CurrentTenantResolver.MASTER_TENANT, () -> {
            var plan = subscriptionPlanRepository.findByName("DYNAMIC_DISABLED_TEST_PLAN")
                    .orElseGet(() -> subscriptionPlanRepository.save(com.chala.posapp.entity.SubscriptionPlan.builder()
                            .name("DYNAMIC_DISABLED_TEST_PLAN")
                            .billingCycle(com.chala.posapp.entity.BillingCycle.MONTHLY)
                            .initialPrice(2000.0)
                            .renewalPrice(2000.0)
                            .maxBranches(2)
                            .build()));
            tenantSubscriptionRepository.save(com.chala.posapp.entity.TenantSubscription.builder()
                    .tenantId(tenant)
                    .shopName("Dynamic Disabled Shop")
                    .adminUsername(tenant + "-cashier")
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
                "DISABLED_MAIN", "Disabled Main", "Test address", "0770000000");
        return jdbc.queryForObject("SELECT id FROM `" + catalog + "`.branches WHERE code = 'DISABLED_MAIN'", Long.class);
    }

    private long insertTenantUser(String username, Role role, Long userBranchId) {
        jdbc.update("INSERT INTO `" + catalog + "`.users (username,password_hash,role,enabled,branch_id) VALUES (?,?,?,?,?)",
                username, passwordEncoder.encode(DEFAULT_PASSWORD), role.name(), true, userBranchId);
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

    private int statusForPost(String path, String tenant, String token, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("X-Tenant-ID", tenant)
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private int statusForGet(String path, String tenant, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("X-Tenant-ID", tenant)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }
}
