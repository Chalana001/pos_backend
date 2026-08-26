package com.chala.posapp.integration;

import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.ShiftStatus;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineSalesIntegrationTest extends ApiIntegrationTestSupport {

    /** Category, sub-category, supplier, item and a stocked purchase — enough to sell one. */
    private long seedSellableItem(TenantFixture fixture, String adminToken, String barcode, String name)
            throws Exception {
        String tenantId = fixture.tenantId();

        Category category = new Category();
        category.setName(name + " Category");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName(name + " Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName(name + " Supplier");
        supplier.setPhone("0777777777");
        supplier.setEmail(uniqueKey("supplier") + "@supplier.local");
        supplier.setAddress("Supplier lane");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 40,
                  "sellingPrice": 90,
                  "reorderLevel": 5
                }
                """.formatted(barcode, name, subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "%s",
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
                """.formatted(supplier.getId(), uniqueKey("PINV"), fixture.mainBranch().getId(), itemId)
        );

        return itemId;
    }

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
        category.setName("Offline Category");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Offline Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
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
        category.setName("Bulk Offline Category");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Bulk Offline Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
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
                        // A missing item, not a stock shortfall: short stock no longer fails an
                        // offline import, so it can no longer stand in for the failing row this
                        // test needs to prove the successful one is not rolled back with it.
                        999_999_999L
                )
        );

        assertEquals(2, bulkResponse.size());
        assertEquals(true, bulkResponse.get(0).path("success").asBoolean());
        assertEquals(false, bulkResponse.get(1).path("success").asBoolean());
        assertTrue(bulkResponse.get(1).path("message").asText().contains("Item not found"));
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

    @Test
    void offlineImportKeepsThePrintedInvoiceNoAndBanksCashToTheSellingCashier() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("offline-attrib"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        long itemId = seedSellableItem(fixture, adminToken, "81002", "Attribution Tea");

        // The cashier who made the sale opens their drawer; the ADMIN does the import.
        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Selling cashier drawer"
                }
                """
        );

        String printedInvoiceNo = "OFF-B%d-K7M2-0042".formatted(fixture.mainBranch().getId());
        JsonNode imported = postJson(
                "/orders/offline-import",
                tenantId,
                adminToken,
                """
                {
                  "clientSaleId": "%s",
                  "invoiceNo": "%s",
                  "offlineCashierUserId": %d,
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
                """.formatted(
                        uniqueKey("client-sale"),
                        printedInvoiceNo,
                        fixture.cashier().getId(),
                        fixture.mainBranch().getId(),
                        itemId
                )
        );

        assertEquals(true, imported.path("success").asBoolean());

        // The number on the customer's receipt survives, instead of the server minting an
        // unrelated INV- that the receipt could never be matched against.
        assertEquals(printedInvoiceNo, imported.path("invoiceNo").asText());

        Order saved = orderRepository.findByClientSaleId(
                orderRepository.findAll().getLast().getClientSaleId()
        ).orElseThrow();
        assertEquals(printedInvoiceNo, saved.getInvoiceNo());

        // The sale belongs to the cashier who took the money, not to the importing admin.
        assertEquals(fixture.cashier().getId(), saved.getCashierUserId());

        // And the cash reaches that cashier's drawer rather than vanishing.
        CashShift sellingShift = cashShiftRepository
                .findByBranchIdAndCashierUserIdAndStatus(
                        fixture.mainBranch().getId(), fixture.cashier().getId(), ShiftStatus.OPEN)
                .orElseThrow();
        assertEquals(90.0, sellingShift.getCashSales(), 0.001);

        // created_at carries the moment of sale, and business_date follows it, so an
        // outage sale is not booked on the day it happened to be pushed.
        assertEquals(LocalDate.of(2026, 5, 4), saved.getBusinessDate());
        assertEquals(LocalDate.of(2026, 5, 4), saved.getCreatedAt().toLocalDate());
    }

    @Test
    void offlineImportIsRejectedWhenTheSellingCashierHasNoOpenShift() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("offline-noshift"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        long itemId = seedSellableItem(fixture, adminToken, "81003", "No Shift Tea");

        long initialOrderCount = orderRepository.count();

        // Nobody opened a drawer. Previously the import succeeded and the cash was
        // silently dropped by addCashSaleToOpenShift's ifPresent.
        JsonNode rejected = jsonRequest(
                HttpMethod.POST,
                "/orders/offline-import",
                tenantId,
                adminToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineCashierUserId": %d,
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
                """.formatted(
                        uniqueKey("client-sale"),
                        fixture.cashier().getId(),
                        fixture.mainBranch().getId(),
                        itemId
                ),
                400
        );

        assertTrue(rejected.path("message").asText().toLowerCase().contains("no open shift"));
        assertEquals(initialOrderCount, orderRepository.count());
    }

    @Test
    void offlineImportAbsorbsAStockShortfallInsteadOfRefusingAPaidSale() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("offline-short"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        // seedSellableItem stocks 6.
        long itemId = seedSellableItem(fixture, adminToken, "81004", "Short Stock Tea");

        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 500,
                  "note": "Short stock drawer"
                }
                """
        );

        long initialOrderCount = orderRepository.count();
        long initialAuditCount = stockOverrideAuditRepository.count();

        // Ten sold against six on hand. This sale already happened offline — the goods are
        // gone and the cash is in the drawer — so refusing it would only keep real revenue
        // off the books and strand a paid transaction in the queue.
        JsonNode imported = postJson(
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
                  "paidAmount": 900,
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 10,
                      "unitPrice": 90,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]
                }
                """.formatted(uniqueKey("client-sale"), fixture.mainBranch().getId(), itemId)
        );

        assertEquals(true, imported.path("success").asBoolean());
        assertEquals(initialOrderCount + 1, orderRepository.count());

        // Stock is allowed to go negative rather than the sale being lost. Batch
        // quantities are held in base units (1000 per display unit), so six on hand less
        // ten sold is -4 units, stored as -4000.
        int remaining = stockBatchRepository.findBatchesForBranchItem(fixture.mainBranch().getId(), itemId)
                .stream()
                .mapToInt(batch -> batch.getQuantity() == null ? 0 : batch.getQuantity())
                .sum();
        assertEquals(-4000, remaining);

        // ...and the shortfall is audited, which is what makes it reconcilable later.
        assertEquals(initialAuditCount + 1, stockOverrideAuditRepository.count());
    }

    @Test
    void offlineImportBanksThePriceThatWasActuallyChargedRatherThanApplyingAPromotion() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("offline-promo"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        long itemId = seedSellableItem(fixture, adminToken, "81005", "Promo Tea");

        // A 50% bill promotion is running right now.
        postJson(
                "/promotions",
                tenantId,
                adminToken,
                """
                {
                  "name": "Half price everything",
                  "scope": "BILL",
                  "discountType": "PERCENT",
                  "discountValue": 50,
                  "minBillAmount": 0,
                  "maxDiscountAmount": 0,
                  "startAt": "2020-01-01T00:00:00",
                  "endAt": "2099-01-01T00:00:00",
                  "branchId": %d,
                  "active": true,
                  "priority": 1
                }
                """.formatted(fixture.mainBranch().getId())
        );

        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                { "openingCash": 500, "note": "Promo drawer" }
                """
        );

        // The till was offline: it never saw the promotion, so it printed and took 90.
        JsonNode imported = postJson(
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
                """.formatted(uniqueKey("client-sale"), fixture.mainBranch().getId(), itemId)
        );

        assertEquals(true, imported.path("success").asBoolean());

        Order saved = orderRepository.findAll().getLast();

        // The ledger says what the customer actually paid. Had the promotion been applied
        // the grand total would be 45, min(paidAmount, grandTotal) would silently absorb
        // the other 45 as change that was never handed over, and the receipt in the
        // customer's hand would disagree with the books.
        assertEquals(90.0, saved.getGrandTotal(), 0.001);
        assertEquals(0.0, saved.getPromotionDiscountTotal(), 0.001);
        assertEquals(90.0, saved.getSalePaidAmount(), 0.001);

        // Proves the assertions above are not vacuous — the promotion really is live and
        // really would have halved that sale — and that live checkout still applies it.
        JsonNode liveOrder = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
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
                """.formatted(fixture.mainBranch().getId(), itemId)
        );
        assertEquals(45.0, liveOrder.path("grandTotal").asDouble(), 0.001);
    }
}
