package com.chala.posapp.integration;

import com.chala.posapp.entity.stock.StockBatch;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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

// Runs on real MySQL (not H2) — this test exercises the FULLTEXT-backed
// item search (MATCH...AGAINST), which H2 cannot execute.
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class InventoryApiIntegrationTest extends ApiIntegrationTestSupport {

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
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Test
    void branchUserCatalogStockAndTransferApisWork() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("inv"), 5);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        JsonNode branchesBefore = getJson("/branches?activeOnly=true", tenantId, adminToken);
        assertEquals(1, branchesBefore.size());

        JsonNode secondBranch = postJson(
                "/branches",
                tenantId,
                adminToken,
                """
                {
                  "code": "CITY",
                  "name": "City Branch",
                  "address": "City center",
                  "phone": "0771234567"
                }
                """
        );
        long secondBranchId = secondBranch.path("id").asLong();

        JsonNode branchById = getJson("/branches/" + secondBranchId, tenantId, managerToken);
        assertEquals("City Branch", branchById.path("name").asText());

        JsonNode updatedBranch = putJson(
                "/branches/" + secondBranchId,
                tenantId,
                adminToken,
                """
                {
                  "name": "City Branch Updated",
                  "address": "Town hall",
                  "phone": "0779999999",
                  "active": true
                }
                """
        );
        assertEquals("City Branch Updated", updatedBranch.path("name").asText());

        JsonNode category = postJson(
                "/categories",
                tenantId,
                adminToken,
                """
                {
                  "name": "Groceries"
                }
                """
        );
        long categoryId = category.path("id").asLong();

        JsonNode subCategory = postJson(
                "/categories/sub-categories",
                tenantId,
                adminToken,
                """
                {
                  "name": "Rice",
                  "categoryId": %d
                }
                """.formatted(categoryId)
        );
        long subCategoryId = subCategory.path("id").asLong();

        JsonNode categories = getJson("/categories", tenantId, managerToken);
        assertFalse(categories.isEmpty());

        JsonNode subCategories = getJson("/categories/" + categoryId + "/sub-categories", tenantId, managerToken);
        assertEquals(1, subCategories.size());

        JsonNode supplier = postJson(
                "/suppliers",
                tenantId,
                adminToken,
                """
                {
                  "name": "Prime Supplier",
                  "phone": "0112222222",
                  "email": "prime@supplier.local",
                  "address": "Supplier lane",
                  "active": true
                }
                """
        );
        long supplierId = supplier.path("id").asLong();

        JsonNode quickSupplier = postJson(
                "/suppliers/quick",
                tenantId,
                adminToken,
                """
                {
                  "name": "Quick Supplier",
                  "phone": "0113333333",
                  "address": "Quick road"
                }
                """
        );
        assertEquals("Quick Supplier", quickSupplier.path("name").asText());

        JsonNode supplierList = getJson("/suppliers", tenantId, managerToken);
        assertEquals(2, supplierList.size());

        JsonNode supplierById = getJson("/suppliers/" + supplierId, tenantId, managerToken);
        assertEquals("Prime Supplier", supplierById.path("name").asText());

        JsonNode managerUser = postJson(
                "/users",
                tenantId,
                adminToken,
                """
                {
                  "username": "%s-city-manager",
                  "password": "Pass@123",
                  "role": "MANAGER",
                  "branchId": %d
                }
                """.formatted(tenantId, secondBranchId)
        );
        long managerUserId = managerUser.path("id").asLong();

        JsonNode cashierUser = postJson(
                "/users",
                tenantId,
                adminToken,
                """
                {
                  "username": "%s-city-cashier",
                  "password": "Pass@123",
                  "role": "CASHIER",
                  "branchId": %d
                }
                """.formatted(tenantId, secondBranchId)
        );
        long cashierUserId = cashierUser.path("id").asLong();

        JsonNode users = getJson("/users", tenantId, adminToken);
        assertTrue(users.size() >= 5);

        JsonNode cashierById = getJson("/users/" + cashierUserId, tenantId, adminToken);
        assertEquals("CASHIER", cashierById.path("role").asText());

        JsonNode branchAssignment = putJson(
                "/users/" + cashierUserId + "/assign-branch",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d
                }
                """.formatted(fixture.mainBranch().getId())
        );
        assertEquals("Branch assigned", branchAssignment.asText());

        JsonNode statusUpdate = putJson(
                "/users/" + cashierUserId + "/status",
                tenantId,
                adminToken,
                """
                {
                  "enabled": false
                }
                """
        );
        assertEquals("User status updated", statusUpdate.asText());

        JsonNode passwordReset = putJson(
                "/users/" + cashierUserId + "/reset-password",
                tenantId,
                adminToken,
                """
                {
                  "newPassword": "Reset@123"
                }
                """
        );
        assertEquals("Password reset successful", passwordReset.asText());

        JsonNode usersInCityBranch = getJson("/users/branch/" + secondBranchId, tenantId, adminToken);
        assertTrue(usersInCityBranch.size() >= 1);
        assertEquals("MANAGER", getJson("/users/" + managerUserId, tenantId, adminToken).path("role").asText());

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "80001",
                  "name": "Red Rice 1kg",
                  "subCategoryId": %d,
                  "costPrice": 120,
                  "sellingPrice": 160,
                  "reorderLevel": 20
                }
                """.formatted(subCategoryId)
        );
        long itemId = item.path("id").asLong();

        JsonNode bulkItems = postJson(
                "/items/bulk",
                tenantId,
                adminToken,
                """
                [
                  {
                    "barcode": "80002",
                    "name": "White Rice 1kg",
                    "subCategoryId": %d,
                    "costPrice": 110,
                    "sellingPrice": 150,
                    "reorderLevel": 10
                  },
                  {
                    "barcode": "80003",
                    "name": "Brown Rice 1kg",
                    "subCategoryId": %d,
                    "costPrice": 115,
                    "sellingPrice": 155,
                    "reorderLevel": 12
                  }
                ]
                """.formatted(subCategoryId, subCategoryId)
        );
        assertEquals(2, bulkItems.size());

        JsonNode duplicateBulk = jsonRequest(
                HttpMethod.POST,
                "/items/bulk",
                tenantId,
                adminToken,
                """
                [
                  {
                    "barcode": "80100",
                    "name": "Duplicate Rice A",
                    "subCategoryId": %d,
                    "costPrice": 110,
                    "sellingPrice": 150,
                    "reorderLevel": 10
                  },
                  {
                    "barcode": "80100",
                    "name": "Duplicate Rice B",
                    "subCategoryId": %d,
                    "costPrice": 115,
                    "sellingPrice": 155,
                    "reorderLevel": 12
                  }
                ]
                """.formatted(subCategoryId, subCategoryId),
                409
        );
        assertTrue(duplicateBulk.path("message").asText().contains("Duplicate barcodes found in the request"));

        JsonNode itemsPage = getJson("/items?page=0&size=10", tenantId, managerToken);
        assertEquals(3, itemsPage.path("content").size());

        JsonNode itemById = getJson("/items/" + itemId, tenantId, managerToken);
        assertEquals("Red Rice 1kg", itemById.path("name").asText());

        JsonNode itemByBarcode = getJson("/items/barcode/80001", tenantId, managerToken);
        assertEquals(itemId, itemByBarcode.path("id").asLong());

        JsonNode itemSearch = getJson("/items/search?name=Rice", tenantId, managerToken);
        assertEquals(3, itemSearch.size());

        JsonNode recentItems = getJson("/items/recent?limit=2", tenantId, adminToken);
        assertEquals(2, recentItems.size());

        JsonNode printSearch = getJson("/items/search-print?query=8000", tenantId, adminToken);
        assertEquals(3, printSearch.size());

        JsonNode updatedItem = putJson(
                "/items/" + itemId,
                tenantId,
                adminToken,
                """
                {
                  "name": "Red Rice 2kg",
                  "sellingPrice": 175,
                  "reorderLevel": 18,
                  "active": true
                }
                """
        );
        assertEquals("Red Rice 2kg", updatedItem.path("name").asText());

        JsonNode purchase = postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "INV-INV-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 10,
                          "costPrice": 120,
                          "sellingPrice": 175
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplierId, fixture.mainBranch().getId(), itemId)
        );
        long purchaseId = purchase.path("purchaseId").asLong();
        long grnId = purchase.path("grnList").get(0).path("id").asLong();
        assertEquals(1200.0, purchase.path("grandTotal").asDouble(), 0.001);

        JsonNode purchases = getJson("/purchases?page=0&size=10", tenantId, adminToken);
        assertEquals(1, purchases.path("content").size());

        JsonNode purchaseById = getJson("/purchases/" + purchaseId, tenantId, adminToken);
        assertEquals("INV-INV-001", purchaseById.path("invoiceNo").asText());

        JsonNode grns = getJson("/grn?search=INV&page=0&size=10", tenantId, adminToken);
        assertEquals(1, grns.path("content").size());

        JsonNode grnById = getJson("/grn/" + grnId, tenantId, adminToken);
        assertEquals("Prime Supplier", grnById.path("supplierName").asText());

        StockBatch sourceBatch = firstBatchFor(fixture.mainBranch().getId(), itemId);

        JsonNode stockPage = getJson("/stock/branch/" + fixture.mainBranch().getId() + "?page=0&size=10", tenantId, managerToken);
        assertEquals(10000, stockPage.path("content").get(0).path("totalQuantity").asInt());
        assertEquals(10.0, stockPage.path("content").get(0).path("displayQuantity").asDouble(), 0.001);

        JsonNode lowStock = getJson("/stock/low?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertFalse(lowStock.isEmpty());

        JsonNode adjustment = postJson(
                "/stock-adjustments",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "itemId": %d,
                  "batchId": %d,
                  "type": "DAMAGED",
                  "qty": 2,
                  "reason": "Torn package"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, sourceBatch.getId())
        );
        assertEquals(-2000, adjustment.path("qtyChange").asInt());
        assertEquals(-2.0, adjustment.path("displayQtyChange").asDouble(), 0.001);

        JsonNode branchAdjustmentHistory = getJson("/stock-adjustments/branch/" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(1, branchAdjustmentHistory.size());

        JsonNode itemAdjustmentHistory = getJson("/stock-adjustments/branch/" + fixture.mainBranch().getId() + "/item/" + itemId, tenantId, adminToken);
        assertEquals(1, itemAdjustmentHistory.size());

        JsonNode firstTransfer = postJson(
                "/stock-transfers",
                tenantId,
                adminToken,
                """
                {
                  "fromBranchId": %d,
                  "toBranchId": %d,
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 3
                    }
                  ],
                  "note": "Move to city branch"
                }
                """.formatted(fixture.mainBranch().getId(), secondBranchId, itemId, sourceBatch.getId())
        );
        long firstTransferId = firstTransfer.path("id").asLong();
        String firstTransferNo = firstTransfer.path("transferNo").asText();

        JsonNode outgoingPending = getJson("/stock-transfers/outgoing/" + fixture.mainBranch().getId() + "/pending", tenantId, adminToken);
        assertEquals(1, outgoingPending.size());

        JsonNode incomingPending = getJson("/stock-transfers/incoming/" + secondBranchId + "/pending", tenantId, adminToken);
        assertEquals(1, incomingPending.size());

        JsonNode transferDetails = getJson("/stock-transfers/details/" + firstTransferNo, tenantId, adminToken);
        assertEquals(firstTransferId, transferDetails.path("id").asLong());

        JsonNode receivedTransfer = postJson("/stock-transfers/" + firstTransferId + "/receive", tenantId, adminToken, "");
        assertEquals("COMPLETED", receivedTransfer.path("status").asText());
        assertEquals(1, stockBatchRepository.findByBranchIdAndItemId(secondBranchId, itemId).size());
        assertEquals(3000, firstBatchFor(secondBranchId, itemId).getQuantity());

        JsonNode secondTransfer = postJson(
                "/stock-transfers",
                tenantId,
                adminToken,
                """
                {
                  "fromBranchId": %d,
                  "toBranchId": %d,
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1
                    }
                  ],
                  "note": "Second move"
                }
                """.formatted(fixture.mainBranch().getId(), secondBranchId, itemId, sourceBatch.getId())
        );
        long secondTransferId = secondTransfer.path("id").asLong();
        JsonNode receivedSecondTransfer = postJson("/stock-transfers/" + secondTransferId + "/receive", tenantId, adminToken, "");
        assertEquals("COMPLETED", receivedSecondTransfer.path("status").asText());
        assertEquals(1, stockBatchRepository.findByBranchIdAndItemId(secondBranchId, itemId).size());
        assertEquals(4000, firstBatchFor(secondBranchId, itemId).getQuantity());

        JsonNode thirdTransfer = postJson(
                "/stock-transfers",
                tenantId,
                adminToken,
                """
                {
                  "fromBranchId": %d,
                  "toBranchId": %d,
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1
                    }
                  ],
                  "note": "Third move"
                }
                """.formatted(fixture.mainBranch().getId(), secondBranchId, itemId, sourceBatch.getId())
        );
        long thirdTransferId = thirdTransfer.path("id").asLong();

        JsonNode canceledTransfer = postJson(
                "/stock-transfers/" + thirdTransferId + "/cancel",
                tenantId,
                adminToken,
                """
                {
                  "reason": "Route issue"
                }
                """
        );
        assertEquals("CANCELED", canceledTransfer.path("status").asText());
        assertEquals(1, stockBatchRepository.findByBranchIdAndItemId(fixture.mainBranch().getId(), itemId).size());
        assertEquals(4000, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());

        JsonNode outgoingTransfers = getJson("/stock-transfers/outgoing/" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(3, outgoingTransfers.size());

        JsonNode incomingTransfers = getJson("/stock-transfers/incoming/" + secondBranchId, tenantId, adminToken);
        assertEquals(3, incomingTransfers.size());

        deleteExpectingStatus("/branches/" + secondBranchId, tenantId, adminToken, 200);
        JsonNode branchesAfterDelete = getJson("/branches?activeOnly=true", tenantId, adminToken);
        assertEquals(1, branchesAfterDelete.size());

        deleteExpectingStatus("/items/" + itemId, tenantId, adminToken, 200);
        JsonNode deactivatedItem = getJson("/items/" + itemId, tenantId, adminToken);
        assertFalse(deactivatedItem.path("active").asBoolean());

        JsonNode posSearchAfterDeactivate = getJson(
                "/items/searchForPos?name=Red Rice&branchId=" + fixture.mainBranch().getId(),
                tenantId,
                adminToken
        );
        assertTrue(posSearchAfterDeactivate.isEmpty());
    }

    @Test
    void supplierDiscountIsAllocatedToBatchCostForProfitReports() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("disc"), 5);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode category = postJson(
                "/categories",
                tenantId,
                adminToken,
                """
                {
                  "name": "Beverages"
                }
                """
        );
        JsonNode subCategory = postJson(
                "/categories/sub-categories",
                tenantId,
                adminToken,
                """
                {
                  "name": "Soft Drinks",
                  "categoryId": %d
                }
                """.formatted(category.path("id").asLong())
        );
        JsonNode supplier = postJson(
                "/suppliers",
                tenantId,
                adminToken,
                """
                {
                  "name": "Bottle Supplier",
                  "phone": "0112223333",
                  "active": true
                }
                """
        );
        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "BOTTLE-DISC-001",
                  "name": "Bima Bottle",
                  "subCategoryId": %d,
                  "costPrice": 92,
                  "sellingPrice": 100,
                  "reorderLevel": 5
                }
                """.formatted(subCategory.path("id").asLong())
        );
        long itemId = item.path("id").asLong();

        JsonNode purchase = postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "FREE-DISC-001",
                  "discountAmount": 184,
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 12,
                          "costPrice": 92,
                          "sellingPrice": 100
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.path("id").asLong(), fixture.mainBranch().getId(), itemId)
        );
        assertEquals(920.0, purchase.path("grandTotal").asDouble(), 0.001);
        assertEquals(184.0, purchase.path("discountAmount").asDouble(), 0.001);
        assertEquals(76.67, purchase.path("grnList").get(0).path("items").get(0).path("costPrice").asDouble(), 0.001);
        assertEquals(920.0, purchase.path("grnList").get(0).path("items").get(0).path("lineTotal").asDouble(), 0.001);

        StockBatch batch = firstBatchFor(fixture.mainBranch().getId(), itemId);
        assertEquals(12000, batch.getQuantity());
        assertEquals(76.67, batch.getCostPrice().doubleValue(), 0.001);

        JsonNode sale = postJson(
                "/orders",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "paymentMethod": "CASH"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batch.getId())
        );
        assertEquals(100.0, sale.path("grandTotal").asDouble(), 0.001);
        assertEquals(76.67, orderItemRepository.findByOrderId(sale.path("id").asLong()).get(0).getLineCost(), 0.001);

        String today = java.time.LocalDate.now().toString();
        JsonNode profitReport = getJson(
                "/reports/profit?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&limit=10",
                tenantId,
                adminToken
        );
        assertFalse(profitReport.isEmpty());
        assertEquals(23.33, profitReport.get(0).path("profit").asDouble(), 0.01);
    }
}
