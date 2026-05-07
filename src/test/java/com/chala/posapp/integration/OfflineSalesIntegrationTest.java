package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineSalesIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void offlinePinCanBeSavedAndStatusIsVisible() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("pin"), 2);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        JsonNode initialStatus = getJson("/auth/offline-pin/status", tenantId, cashierToken);
        assertTrue(initialStatus.isObject());
        assertEquals(false, initialStatus.path("hasOfflinePin").asBoolean());

        JsonNode updatedStatus = putJson(
                "/auth/offline-pin",
                tenantId,
                cashierToken,
                """
                {
                  "newPin": "1234"
                }
                """
        );
        assertEquals(true, updatedStatus.path("hasOfflinePin").asBoolean());

        JsonNode finalStatus = getJson("/auth/offline-pin/status", tenantId, cashierToken);
        assertEquals(true, finalStatus.path("hasOfflinePin").asBoolean());
    }

    @Test
    void offlineImportIsIdempotentAndSupportsAutomaticBatchSelection() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("offline"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setTenantId(tenantId);
        category.setName("Offline Category");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setTenantId(tenantId);
        subCategory.setName("Offline Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setTenantId(tenantId);
        supplier.setName("Offline Supplier");
        supplier.setPhone("0777777777");
        supplier.setEmail("offline@supplier.local");
        supplier.setAddress("Supplier lane");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "81001",
                  "name": "Offline Tea",
                  "subCategoryId": %d,
                  "costPrice": 40,
                  "sellingPrice": 90,
                  "reorderLevel": 5
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "INV-OFF-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 6,
                          "costPrice": 40,
                          "sellingPrice": 90
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), itemId)
        );

        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1500,
                  "note": "Offline import shift"
                }
                """
        );

        long initialOrderCount = orderRepository.count();
        String clientSaleId = uniqueKey("client-sale");
        JsonNode firstImport = postJson(
                "/orders/offline-import",
                tenantId,
                cashierToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T10:15:00",
                  "branchId": %d,
                  "orderType": "CASH",
                  "saleMode": "TAKEAWAY",
                  "billDiscount": 0,
                  "paidAmount": 90,
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 90,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]
                }
                """.formatted(clientSaleId, fixture.mainBranch().getId(), itemId)
        );

        assertEquals(true, firstImport.path("success").asBoolean());
        assertTrue(firstImport.path("invoiceNo").asText().startsWith("INV-"));
        assertEquals(initialOrderCount + 1, orderRepository.count());
        assertEquals(clientSaleId, orderRepository.findAll().getLast().getClientSaleId());

        JsonNode secondImport = postJson(
                "/orders/offline-import",
                tenantId,
                cashierToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T10:15:00",
                  "branchId": %d,
                  "orderType": "CASH",
                  "saleMode": "TAKEAWAY",
                  "billDiscount": 0,
                  "paidAmount": 90,
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 90,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]
                }
                """.formatted(clientSaleId, fixture.mainBranch().getId(), itemId)
        );

        assertEquals(true, secondImport.path("success").asBoolean());
        assertEquals(firstImport.path("invoiceNo").asText(), secondImport.path("invoiceNo").asText());
        assertEquals(initialOrderCount + 1, orderRepository.count());
    }

    @Test
    void offlineImportBulkReturnsPartialSuccessWithoutRollingBackSuccessfulRows() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("offline-bulk"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setTenantId(tenantId);
        category.setName("Bulk Offline Category");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setTenantId(tenantId);
        subCategory.setName("Bulk Offline Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setTenantId(tenantId);
        supplier.setName("Bulk Offline Supplier");
        supplier.setPhone("0777777778");
        supplier.setEmail("bulk-offline@supplier.local");
        supplier.setAddress("Supplier lane");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "81002",
                  "name": "Offline Coffee",
                  "subCategoryId": %d,
                  "costPrice": 50,
                  "sellingPrice": 100,
                  "reorderLevel": 5
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "INV-OFF-BULK-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 3,
                          "costPrice": 50,
                          "sellingPrice": 100
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), itemId)
        );

        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 2000,
                  "note": "Bulk offline import shift"
                }
                """
        );

        long initialOrderCount = orderRepository.count();
        JsonNode bulkResponse = postJson(
                "/orders/offline-import/bulk",
                tenantId,
                cashierToken,
                """
                [
                  {
                    "clientSaleId": "%s",
                    "offlineSoldAt": "2026-05-04T11:00:00",
                    "branchId": %d,
                    "orderType": "CASH",
                    "saleMode": "TAKEAWAY",
                    "billDiscount": 0,
                    "paidAmount": 100,
                    "items": [
                      {
                        "itemId": %d,
                        "qty": 1,
                        "unitPrice": 100,
                        "discountType": "NONE",
                        "discountValue": 0
                      }
                    ]
                  },
                  {
                    "clientSaleId": "%s",
                    "offlineSoldAt": "2026-05-04T11:05:00",
                    "branchId": %d,
                    "orderType": "CASH",
                    "saleMode": "TAKEAWAY",
                    "billDiscount": 0,
                    "paidAmount": 500,
                    "items": [
                      {
                        "itemId": %d,
                        "qty": 5,
                        "unitPrice": 100,
                        "discountType": "NONE",
                        "discountValue": 0
                      }
                    ]
                  }
                ]
                """.formatted(
                        uniqueKey("bulk-ok"),
                        fixture.mainBranch().getId(),
                        itemId,
                        uniqueKey("bulk-fail"),
                        fixture.mainBranch().getId(),
                        itemId
                )
        );

        assertEquals(2, bulkResponse.size());
        assertEquals(true, bulkResponse.get(0).path("success").asBoolean());
        assertEquals(false, bulkResponse.get(1).path("success").asBoolean());
        assertTrue(bulkResponse.get(1).path("message").asText().contains("Insufficient stock"));
        assertEquals(initialOrderCount + 1, orderRepository.count());
    }

    @Test
    void offlineImportRejectsUnsupportedSaleModesAndOrderTypes() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("offline-invalid"), 2);
        String tenantId = fixture.tenantId();
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        JsonNode invalidOrderType = jsonRequest(
                HttpMethod.POST,
                "/orders/offline-import",
                tenantId,
                cashierToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T12:00:00",
                  "branchId": %d,
                  "orderType": "CREDIT",
                  "saleMode": "TAKEAWAY",
                  "billDiscount": 0,
                  "paidAmount": 0,
                  "items": [
                    {
                      "itemId": 999,
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]
                }
                """.formatted(uniqueKey("offline-credit"), fixture.mainBranch().getId()),
                400
        );
        assertTrue(invalidOrderType.path("message").asText().contains("CASH sales only"));

        JsonNode invalidSaleMode = jsonRequest(
                HttpMethod.POST,
                "/orders/offline-import",
                tenantId,
                cashierToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T12:05:00",
                  "branchId": %d,
                  "orderType": "CASH",
                  "saleMode": "DINE_IN",
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "items": [
                    {
                      "itemId": 999,
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]
                }
                """.formatted(uniqueKey("offline-dinein"), fixture.mainBranch().getId()),
                400
        );
        assertTrue(invalidSaleMode.path("message").asText().contains("TAKEAWAY sales only"));
    }
}
