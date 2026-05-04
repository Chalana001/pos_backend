package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

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
        assertEquals(1, orderRepository.count());
        assertEquals(clientSaleId, orderRepository.findAll().getFirst().getClientSaleId());

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
        assertEquals(1, orderRepository.count());
    }
}
