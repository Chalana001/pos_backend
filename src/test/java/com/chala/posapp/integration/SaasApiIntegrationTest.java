package com.chala.posapp.integration;

import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.SubscriptionPlan;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class SaasApiIntegrationTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        // root, not the container user: the app provisions tenant databases at
        // runtime and the URL carries createDatabaseIfNotExist, both of which
        // need server-wide rights the per-database app user does not have.
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Test
    void superAdminAndSubscriptionApisWork() throws Exception {
        SubscriptionPlan basicPlan = ensurePlan("MONTHLY_BASIC", BillingCycle.MONTHLY, 2000.0, 2000.0, 1);
        SubscriptionPlan yearlyPlan = ensurePlan("YEARLY_GROWTH", BillingCycle.YEARLY, 20000.0, 18000.0, 3);

        JsonNode health = getJson("/health", null, null);
        assertEquals("POS Backend OK", health.asText());

        JsonNode plans = getJson("/api/saas/plans", null, null);
        assertTrue(plans.isArray());
        assertTrue(plans.size() >= 2);

        String superAdminToken = loginViaApiAlias("MASTER", "super-admin", "super-admin");

        JsonNode registerAdmin = postJson(
                "/auth/register-admin",
                "MASTER",
                superAdminToken,
                """
                {
                  "username": "master-ops-admin",
                  "password": "Pass@123"
                }
                """
        );
        assertEquals("Admin created", registerAdmin.asText());

        JsonNode dashboardBefore = getJson("/api/saas/admin/dashboard", "MASTER", superAdminToken);
        long shopsBefore = dashboardBefore.path("totalShops").asLong();

        String tenantId = uniqueKey("shop");
        JsonNode onboardedShop = postJson(
                "/api/saas/admin/shops",
                "MASTER",
                superAdminToken,
                """
                {
                  "tenantId": "%s",
                  "shopName": "API Test Shop",
                  "adminUsername": "%s-admin",
                  "adminPassword": "Pass@123",
                  "planId": %d,
                  "amountPaid": 2200,
                  "initialBranchName": "HQ",
                  "initialBranchAddress": "Colombo",
                  "initialBranchPhone": "0111111111",
                  "note": "Initial onboard"
                }
                """.formatted(tenantId, tenantId, basicPlan.getId())
        );
        assertEquals(tenantId, onboardedShop.path("tenantId").asText());
        assertEquals("MONTHLY_BASIC", onboardedShop.path("planName").asText());

        JsonNode shopsPage = getJson("/api/saas/admin/shops?page=0&size=10&search=" + tenantId + "&status=all", "MASTER", superAdminToken);
        assertTrue(shopsPage.path("items").isArray());
        assertFalse(shopsPage.path("items").isEmpty());

        JsonNode shopDetails = getJson("/api/saas/admin/shops/" + tenantId, "MASTER", superAdminToken);
        assertEquals("API Test Shop", shopDetails.path("shopName").asText());
        assertEquals("HQ", shopDetails.path("mainBranchName").asText());

        JsonNode paymentsAfterOnboard = getJson("/api/saas/admin/shops/" + tenantId + "/payments", "MASTER", superAdminToken);
        assertEquals(1, paymentsAfterOnboard.size());

        JsonNode blockedShop = patchJson(
                "/api/saas/admin/shops/" + tenantId + "/block",
                "MASTER",
                superAdminToken,
                """
                {
                  "blocked": true,
                  "reason": "Compliance hold"
                }
                """
        );
        assertTrue(blockedShop.path("blocked").asBoolean());

        JsonNode unblockedShop = patchJson(
                "/api/saas/admin/shops/" + tenantId + "/block",
                "MASTER",
                superAdminToken,
                """
                {
                  "blocked": false,
                  "reason": "Cleared"
                }
                """
        );
        assertFalse(unblockedShop.path("blocked").asBoolean());

        JsonNode resetPasswordResponse = patchJson(
                "/api/saas/admin/shops/" + tenantId + "/admin-password",
                "MASTER",
                superAdminToken,
                """
                {
                  "newPassword": "NewPass@123"
                }
                """
        );
        assertEquals(tenantId, resetPasswordResponse.path("tenantId").asText());

        JsonNode renewedShop = postJson(
                "/api/saas/admin/shops/" + tenantId + "/renew",
                "MASTER",
                superAdminToken,
                """
                {
                  "cycles": 2,
                  "amountPaid": 4000,
                  "note": "Manual renewal"
                }
                """
        );
        assertTrue(renewedShop.path("active").asBoolean());

        JsonNode changedPackage = postJson(
                "/api/saas/admin/shops/" + tenantId + "/package",
                "MASTER",
                superAdminToken,
                """
                {
                  "planId": %d,
                  "amountPaid": 18000,
                  "note": "Upgrade package"
                }
                """.formatted(yearlyPlan.getId())
        );
        assertEquals("YEARLY_GROWTH", changedPackage.path("planName").asText());

        JsonNode shopWithExtraBranches = postJson(
                "/api/saas/admin/shops/" + tenantId + "/extra-branches",
                "MASTER",
                superAdminToken,
                """
                {
                  "extraBranches": 2,
                  "amountPaid": 5000,
                  "note": "Extra capacity"
                }
                """
        );
        assertEquals(2, shopWithExtraBranches.path("extraBranches").asInt());

        JsonNode dashboardAfter = getJson("/api/saas/admin/dashboard", "MASTER", superAdminToken);
        assertTrue(dashboardAfter.path("totalShops").asLong() >= shopsBefore + 1);

        JsonNode billingHistory = getJson("/api/saas/admin/shops/" + tenantId + "/payments", "MASTER", superAdminToken);
        assertTrue(billingHistory.size() >= 4);

        String tenantAdminToken = login(tenantId, tenantId + "-admin", "NewPass@123");

        JsonNode mySubscription = getJson("/api/saas/my-subscription", tenantId, tenantAdminToken);
        assertEquals(tenantId, mySubscription.path("tenantId").asText());
        assertEquals(yearlyPlan.getId(), mySubscription.path("plan").path("id").asLong());

        JsonNode tenantBranches = getJson("/branches?activeOnly=true", tenantId, tenantAdminToken);
        assertEquals(1, tenantBranches.size());

        JsonNode masterAdminAuth = getJson("/health", null, null);
        assertEquals("POS Backend OK", masterAdminAuth.asText());

        String masterAdminToken = loginViaApiAlias("MASTER", "master-ops-admin", "Pass@123");
        assertFalse(masterAdminToken.isBlank());

        assertTrue(userRepository.findByUsername("master-ops-admin")
                .map(user -> user.getRole() == Role.ADMIN)
                .orElse(false));
    }
}
