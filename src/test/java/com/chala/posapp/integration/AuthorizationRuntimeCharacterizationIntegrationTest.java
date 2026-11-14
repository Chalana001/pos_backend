package com.chala.posapp.integration;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.PromotionSettings;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.TenantModule;
import com.chala.posapp.repository.PromotionSettingsRepository;
import com.chala.posapp.repository.ReportExportJobRepository;
import com.chala.posapp.repository.TenantModuleRepository;
import com.chala.posapp.service.ReportExportJobScheduler;
import com.chala.posapp.tenant.CurrentTenantResolver;
import com.chala.posapp.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime characterization of the current role and ownership boundaries.
 *
 * <p>This class deliberately records the existing behavior. It does not introduce dynamic
 * permissions or reinterpret service-level branch, ownership, module, subscription, or workflow
 * checks as role permissions.</p>
 */
class AuthorizationRuntimeCharacterizationIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private ReportExportJobRepository reportExportJobRepository;

    @Autowired
    private ReportExportJobScheduler reportExportJobScheduler;

    @Autowired
    private PromotionSettingsRepository promotionSettingsRepository;

    @Autowired
    private TenantModuleRepository tenantModuleRepository;

    @Test
    void superAdminUsesMasterPlatformContextAndTenantJwtCannotBeReusedAcrossShops() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-super"), 1);
        String tenantId = fixture.tenantId();
        String superAdminToken = loginViaApiAlias("MASTER", "super-admin", "super-admin");

        getJsonExpectingStatus("/api/saas/admin/dashboard", "MASTER", superAdminToken, 200);
        getJsonExpectingStatus("/api/saas/admin/shops", "MASTER", superAdminToken, 200);

        String tenantAdminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        JsonNode mismatch = getJsonExpectingStatus("/api/saas/admin/dashboard", tenantId, tenantAdminToken, 403);
        assertTrue(mismatch.toString().contains("FORBIDDEN") || mismatch.toString().contains("Forbidden"));
    }

    @Test
    void orderListingIsManagerOrAdminOnlyWhileCashierCanUseOfflineReplayEndpoint() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-orders"), 1);
        String tenantId = fixture.tenantId();
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        getJsonExpectingStatus("/orders?page=0&size=1", tenantId, managerToken, 200);
        getJsonExpectingStatus("/orders?page=0&size=1", tenantId, adminToken, 200);
        getJsonExpectingStatus("/orders?page=0&size=1", tenantId, cashierToken, 403);

        JsonNode invalidOfflineReplay = jsonRequest(
                HttpMethod.POST,
                "/orders/offline-import",
                tenantId,
                cashierToken,
                "{}",
                400);
        assertNotNull(invalidOfflineReplay);
        assertTrue(invalidOfflineReplay.toString().contains("required")
                        || invalidOfflineReplay.toString().contains("must"),
                "Cashier reached offline-import validation; it was not rejected by method security");
    }

    @Test
    void cashierCanReachOrderCancellationButServiceStillAppliesBranchAndOrderStateRules() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-cancel"), 1);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        JsonNode missingOrder = jsonRequest(
                HttpMethod.POST,
                "/orders/does-not-exist/cancel",
                tenantId,
                cashierToken,
                "{\"reason\":\"runtime characterization\"}",
                404);
        assertNotNull(missingOrder);
        assertTrue(missingOrder.toString().contains("Order not found"));
    }

    @Test
    void cashierOrderCreationIsBranchScopedEvenThoughControllerRoleAllowsIt() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-branch"), 2);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Branch otherBranch = Branch.builder()
                .code(uniqueKey("OTHER").toUpperCase())
                .name("Other Branch " + uniqueKey("branch"))
                .address("Other address")
                .phone("0770000001")
                .active(true)
                .build();
        otherBranch = branchRepository.save(otherBranch);

        JsonNode response = jsonRequest(
                HttpMethod.POST,
                "/orders",
                tenantId,
                cashierToken,
                "{\"branchId\":" + otherBranch.getId()
                        + ",\"orderType\":\"CASH\",\"saleMode\":\"TAKEAWAY\",\"items\":[]}",
                400);
        assertNotNull(response);
        assertTrue(response.toString().contains("another branch"));
    }

    @Test
    void managerBranchScopedShiftEndpointRejectsOtherBranchWhileAdminIsNotBranchRestricted() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-branches"), 2);
        String tenantId = fixture.tenantId();
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Branch otherBranch = Branch.builder()
                .code(uniqueKey("BRANCH").toUpperCase())
                .name("Second Branch " + uniqueKey("branch"))
                .address("Second address")
                .phone("0770000002")
                .active(true)
                .build();
        otherBranch = branchRepository.save(otherBranch);

        getJsonExpectingStatus("/shifts/admin-current?branchId=" + otherBranch.getId(), tenantId, managerToken, 400);
        JsonNode adminResponse = jsonRequest(
                HttpMethod.GET,
                "/shifts/admin-current?branchId=" + otherBranch.getId(),
                tenantId,
                adminToken,
                null,
                404);
        assertTrue(adminResponse.toString().contains("No active shift")
                        || adminResponse.toString().contains("No active"));
    }

    @Test
    void customerReadAndCreateAllowCashierButUpdateIsManagerOrAdminOnly() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-customers"), 1);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        JsonNode created = postJson(
                "/customers",
                tenantId,
                cashierToken,
                "{\"name\":\"Runtime Customer\",\"phone\":\"0771234567\",\"address\":\"Test lane\"}");
        long customerId = created.path("id").asLong();
        assertTrue(customerId > 0);

        JsonNode read = getJson("/customers/" + customerId, tenantId, cashierToken);
        assertEquals(customerId, read.path("id").asLong());

        JsonNode cashierUpdate = jsonRequest(
                HttpMethod.PUT,
                "/customers/" + customerId,
                tenantId,
                cashierToken,
                "{\"name\":\"Cashier Update\"}",
                403);
        assertNotNull(cashierUpdate);

        JsonNode managerUpdate = putJson(
                "/customers/" + customerId,
                tenantId,
                managerToken,
                "{\"name\":\"Manager Update\"}");
        assertEquals("Manager Update", managerUpdate.path("name").asText());
    }

    @Test
    void unannotatedCustomerNotesAndHistoryRemainServiceLevelAuthenticatedSurfaces() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-customer-detail"), 1);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        JsonNode customer = postJson(
                "/customers",
                tenantId,
                cashierToken,
                "{\"name\":\"Detail Customer\",\"phone\":\"0771234568\"}");
        long customerId = customer.path("id").asLong();

        JsonNode note = postJson(
                "/customers/" + customerId + "/notes",
                tenantId,
                cashierToken,
                "{\"note\":\"Cashier note\"}");
        assertTrue(note.path("id").asLong() > 0);

        JsonNode notes = getJson("/customers/" + customerId + "/notes", tenantId, cashierToken);
        assertEquals(1, notes.path("totalElements").asInt());
        long noteId = note.path("id").asLong();

        JsonNode updated = putJson(
                "/customer-notes/" + noteId,
                tenantId,
                cashierToken,
                "{\"note\":\"Cashier updated note\"}");
        assertEquals("Cashier updated note", updated.path("note").asText());
        deleteExpectingStatus("/customer-notes/" + noteId, tenantId, cashierToken, 204);

        JsonNode history = getJson("/customers/" + customerId + "/orders", tenantId, cashierToken);
        assertEquals(0, history.path("totalElements").asInt());
    }

    @Test
    void promotionApprovalRequiresAdminAndDifferentSubmitter() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-promotions"), 1);
        String tenantId = fixture.tenantId();
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        var secondAdmin = createUser(tenantId, tenantId + "-admin-2", Role.ADMIN, null, DEFAULT_PASSWORD, true);
        String secondAdminToken = login(tenantId, secondAdmin.getUsername(), DEFAULT_PASSWORD);

        TenantContext.runWith(tenantId, () -> promotionSettingsRepository.save(PromotionSettings.builder()
                .approvalRequired(true)
                .approvalThresholdPercent(java.math.BigDecimal.valueOf(10))
                .approvalThresholdAmount(null)
                .cashierManualStacking(true)
                .allowBelowCostPromotions(false)
                .build()));

        String request = "{"
                + "\"name\":\"Runtime approval promotion\","
                + "\"scope\":\"BILL\","
                + "\"discountType\":\"PERCENT\","
                + "\"discountValue\":20,"
                + "\"startAt\":\"2026-01-01T00:00:00\","
                + "\"endAt\":\"2027-01-01T00:00:00\","
                + "\"branchId\":" + fixture.mainBranch().getId() + ","
                + "\"active\":true"
                + "}";
        JsonNode created = postJson("/promotions", tenantId, adminToken, request);
        long promotionId = created.path("id").asLong();
        assertEquals("PENDING_APPROVAL", created.path("status").asText());

        JsonNode managerApproval = jsonRequest(
                HttpMethod.POST,
                "/promotions/" + promotionId + "/approve",
                tenantId,
                managerToken,
                "{}",
                403);
        assertNotNull(managerApproval);

        JsonNode selfApproval = jsonRequest(
                HttpMethod.POST,
                "/promotions/" + promotionId + "/approve",
                tenantId,
                adminToken,
                "{}",
                400);
        assertTrue(selfApproval.toString().contains("other than the person who submitted"));

        JsonNode approved = postJson("/promotions/" + promotionId + "/approve", tenantId, secondAdminToken, "{}");
        assertEquals("ACTIVE", approved.path("status").asText());
        assertEquals(secondAdmin.getId(), approved.path("approvedBy").asLong());
    }

    @Test
    void subscriptionPaywallPrecedesModuleDenialForSameRequest() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-gates"), 1);
        String tenantId = fixture.tenantId();
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        TenantContext.runWith(CurrentTenantResolver.MASTER_TENANT, () -> tenantModuleRepository.save(TenantModule.builder()
                .tenantId(tenantId)
                .moduleKey("REPORTS")
                .enabled(false)
                .note("runtime characterization")
                .updatedBy("test")
                .build()));
        getJsonExpectingStatus("/reports/export-jobs?page=0&size=1", tenantId, managerToken, 403);

        TenantContext.runWith(CurrentTenantResolver.MASTER_TENANT, () -> {
            var subscription = tenantSubscriptionRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new IllegalStateException("Missing subscription"));
            subscription.setActive(false);
            tenantSubscriptionRepository.save(subscription);
        });
        JsonNode paywall = getJsonExpectingStatus("/reports/export-jobs?page=0&size=1", tenantId, managerToken, 402);
        assertTrue(paywall.toString().contains("SUBSCRIPTION_INACTIVE"));
    }

    @Test
    void profitAndLossAndExportControllersRejectCashierAtMethodSecurityBoundary() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-reports"), 1);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        String range = "from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now();
        getJsonExpectingStatus("/api/reports/v2/profit-loss?" + range, tenantId, cashierToken, 403);
        getJsonExpectingStatus("/api/reports/v2/profit-loss?" + range, tenantId, managerToken, 200);

        getJsonExpectingStatus("/reports/export-jobs?page=0&size=1", tenantId, cashierToken, 403);
        getJsonExpectingStatus("/reports/schedules", tenantId, cashierToken, 403);
    }

    @Test
    void interactiveExportJobsAreOwnedByRequesterAndWorkerRunsAsRequester() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-export"), 1);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        JsonNode queued = jsonRequest(
                HttpMethod.POST,
                "/reports/export-jobs",
                tenantId,
                adminToken,
                "{\"reportType\":\"SALES\",\"branchId\":" + fixture.mainBranch().getId()
                        + ",\"sortBy\":\"DATE\",\"sortDirection\":\"DESC\"}",
                202);
        long jobId = queued.path("id").asLong();
        assertEquals("QUEUED", queued.path("status").asText());

        getJsonExpectingStatus("/reports/export-jobs/" + jobId, tenantId, managerToken, 404);
        getJsonExpectingStatus("/reports/export-jobs/" + jobId + "/download", tenantId, managerToken, 404);

        reportExportJobScheduler.processTenantNow(tenantId);
        JsonNode completed = getJson("/reports/export-jobs/" + jobId, tenantId, adminToken);
        assertEquals("COMPLETED", completed.path("status").asText(), completed.path("errorMessage").asText());
        assertTrue(completed.path("downloadable").asBoolean());

        var persisted = reportExportJobRepository.findById(jobId).orElseThrow();
        assertEquals(fixture.admin().getId(), persisted.getRequestedByUserId());
        assertEquals(fixture.admin().getUsername(), persisted.getRequestedByUsername());
    }

    @Test
    void exportWorkerUsesCurrentRequesterRecordButReportGenerationDoesNotRecheckDemotionOrDisablement() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-export-recheck"), 1);
        String tenantId = fixture.tenantId();
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        JsonNode queued = jsonRequest(
                HttpMethod.POST,
                "/reports/export-jobs",
                tenantId,
                managerToken,
                "{\"reportType\":\"SALES\",\"branchId\":" + fixture.mainBranch().getId()
                        + ",\"sortBy\":\"DATE\",\"sortDirection\":\"DESC\"}",
                202);
        long jobId = queued.path("id").asLong();

        fixture.manager().setRole(Role.CASHIER);
        fixture.manager().setEnabled(false);
        userRepository.save(fixture.manager());

        reportExportJobScheduler.processTenantNow(tenantId);
        var processed = TenantContext.callWith(tenantId,
                () -> reportExportJobRepository.findById(jobId).orElseThrow());
        assertEquals("COMPLETED", processed.getStatus().name(), processed.getErrorMessage());
        assertEquals(fixture.manager().getId(), processed.getRequestedByUserId());
    }

    @Test
    void shiftDetailRequiresManagerLikeRoleWhileCashierCanReadOwnedShiftResources() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("auth-shifts"), 1);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        JsonNode opened = postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                "{\"openingCash\":100,\"note\":\"runtime characterization\"}");
        long shiftId = opened.path("id").asLong();
        assertTrue(shiftId > 0);

        getJsonExpectingStatus("/shifts/" + shiftId, tenantId, cashierToken, 403);
        JsonNode managerView = getJson("/shifts/" + shiftId, tenantId, managerToken);
        assertEquals(shiftId, managerView.path("id").asLong());
        getJsonExpectingStatus("/shifts/" + shiftId + "/orders", tenantId, cashierToken, 200);
    }
}
