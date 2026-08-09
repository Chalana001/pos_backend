package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Runs on real MySQL (not H2) — this test exercises the FULLTEXT-backed
// item search (MATCH...AGAINST), which H2 cannot execute.
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class WeightItemIntegrationTest extends ApiIntegrationTestSupport {

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
    void weightItemFlowSupportsKgAndGramAcrossInventoryAndSales() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("wgt"), 5);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Groceries");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Spices");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName("Weight Supplier");
        supplier.setPhone("0711111111");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        Supplier backupSupplier = new Supplier();
        backupSupplier.setName("Backup Weight Supplier");
        backupSupplier.setPhone("0722222222");
        backupSupplier.setAddress("Backup supplier address");
        backupSupplier.setActive(true);
        backupSupplier = supplierRepository.save(backupSupplier);

        JsonNode secondBranch = postJson(
                "/branches",
                tenantId,
                adminToken,
                """
                {
                  "code": "B2",
                  "name": "Branch Two",
                  "address": "Second branch",
                  "phone": "0771234567"
                }
                """
        );
        long secondBranchId = secondBranch.path("id").asLong();

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "91001",
                  "name": "Loose Sugar",
                  "subCategoryId": %d,
                  "costPrice": 1000,
                  "sellingPrice": 1300,
                  "reorderLevel": 2.5,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG"
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();
        assertEquals("WEIGHT", item.path("itemType").asText());
        assertEquals("KG", item.path("defaultUnit").asText());
        assertEquals(2.5, item.path("reorderLevel").asDouble(), 0.001);
        assertEquals(2500, item.path("reorderLevelBaseQty").asInt());

        JsonNode purchase = postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "WGT-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 4,
                          "costPrice": 1000,
                          "sellingPrice": 1300
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), itemId)
        );
        assertEquals(4000.0, purchase.path("grandTotal").asDouble(), 0.001);
        assertEquals(4.0, purchase.path("grnList").get(0).path("items").get(0).path("qty").asDouble(), 0.001);
        assertEquals("KG", purchase.path("grnList").get(0).path("items").get(0).path("qtyUnit").asText());

        long sourceBatchId = firstBatchFor(fixture.mainBranch().getId(), itemId).getId();
        assertEquals(4000, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());

        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 5000,
                  "note": "Weight shift"
                }
                """
        );

        JsonNode order = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 500,
                      "qtyUnit": "G",
                      "unitPrice": 1300,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 650
                }
                """.formatted(fixture.mainBranch().getId(), itemId, sourceBatchId)
        );
        assertEquals(650.0, order.path("grandTotal").asDouble(), 0.001);
        assertEquals(500.0, order.path("items").get(0).path("qty").asDouble(), 0.001);
        assertEquals("G", order.path("items").get(0).path("qtyUnit").asText());
        assertEquals(3500, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());

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
                  "qty": 250,
                  "qtyUnit": "G",
                  "reason": "Bag damaged"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, sourceBatchId)
        );
        assertEquals(-250, adjustment.path("qtyChange").asInt());
        assertEquals(-250.0, adjustment.path("displayQtyChange").asDouble(), 0.001);
        assertEquals("G", adjustment.path("qtyUnit").asText());
        assertEquals(3250, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());

        JsonNode transfer = postJson(
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
                      "qty": 1.25,
                      "qtyUnit": "KG"
                    }
                  ],
                  "note": "Move sugar"
                }
                """.formatted(fixture.mainBranch().getId(), secondBranchId, itemId, sourceBatchId)
        );
        long transferId = transfer.path("id").asLong();
        assertEquals(1.25, transfer.path("items").get(0).path("qty").asDouble(), 0.001);
        assertEquals("KG", transfer.path("items").get(0).path("qtyUnit").asText());
        assertEquals(2000, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());

        postJson("/stock-transfers/" + transferId + "/receive", tenantId, adminToken, "");
        assertEquals(1250, firstBatchFor(secondBranchId, itemId).getQuantity());

        JsonNode secondPurchase = postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "WGT-002",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 1,
                          "costPrice": 1050,
                          "sellingPrice": 1350
                        }
                      ]
                    }
                  ]
                }
                """.formatted(backupSupplier.getId(), fixture.mainBranch().getId(), itemId)
        );

        JsonNode stockPage = getJson("/stock/branch/" + fixture.mainBranch().getId() + "?page=0&size=10", tenantId, adminToken);
        JsonNode stockRow = stockPage.path("content").get(0);
        assertEquals(3000, stockRow.path("totalQuantity").asInt());
        assertEquals(3.0, stockRow.path("displayQuantity").asDouble(), 0.001);
        assertEquals("KG", stockRow.path("defaultUnit").asText());

        JsonNode stockDetails = getJson("/stock/branch/" + fixture.mainBranch().getId() + "/item/" + itemId, tenantId, adminToken);
        assertEquals(itemId, stockDetails.path("itemId").asLong());
        assertEquals(3.0, stockDetails.path("displayQuantity").asDouble(), 0.001);
        assertEquals(2, stockDetails.path("activeBatches").size());

        JsonNode stockPurchases = getJson("/stock/branch/" + fixture.mainBranch().getId() + "/item/" + itemId + "/purchases?page=0&size=10", tenantId, adminToken);
        assertEquals(2, stockPurchases.path("content").size());
        assertEquals(secondPurchase.path("purchaseId").asLong(), stockPurchases.path("content").get(0).path("purchaseId").asLong());
        assertEquals("WGT-002", stockPurchases.path("content").get(0).path("invoiceNo").asText());

        JsonNode supplierFilteredPurchases = getJson(
                "/stock/branch/" + fixture.mainBranch().getId() + "/item/" + itemId + "/purchases?page=0&size=10&supplierId=" + supplier.getId(),
                tenantId,
                adminToken
        );
        assertEquals(1, supplierFilteredPurchases.path("content").size());
        assertEquals(purchase.path("purchaseId").asLong(), supplierFilteredPurchases.path("content").get(0).path("purchaseId").asLong());

        JsonNode searchFilteredPurchases = getJson(
                "/stock/branch/" + fixture.mainBranch().getId() + "/item/" + itemId + "/purchases?page=0&size=10&search=WGT-002",
                tenantId,
                adminToken
        );
        assertEquals(1, searchFilteredPurchases.path("content").size());
        assertEquals(secondPurchase.path("purchaseId").asLong(), searchFilteredPurchases.path("content").get(0).path("purchaseId").asLong());

        String today = LocalDate.now().toString();
        JsonNode dateFilteredPurchases = getJson(
                "/stock/branch/" + fixture.mainBranch().getId() + "/item/" + itemId + "/purchases?page=0&size=10&from=" + today + "&to=" + today,
                tenantId,
                adminToken
        );
        assertEquals(2, dateFilteredPurchases.path("content").size());

        JsonNode posItems = getJson(
                "/items/searchForPos?name=Loose&branchId=" + fixture.mainBranch().getId(),
                tenantId,
                adminToken
        );
        JsonNode posItem = posItems.get(0);
        JsonNode posBatch = posItem.path("batches").get(0);
        assertEquals(2000, posBatch.path("qty").asInt());
        assertEquals(2.0, posBatch.path("displayQty").asDouble(), 0.001);
        assertEquals("KG", posBatch.path("displayUnit").asText());
    }
}
