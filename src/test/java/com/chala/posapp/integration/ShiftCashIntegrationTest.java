package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.entity.Order;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The shift's drawer numbers must agree between the live screen and the closed
 * record, offline timestamps must land in Colombo time, and card / bank / cash
 * credit-collection activity must be visible on the shift.
 */
class ShiftCashIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(
            TenantFixture fixture, String tenantId, String token, String adminToken,
            long itemId, long batchId) {
        long branch() {
            return fixture.mainBranch().getId();
        }
    }

    private Shop shop(String key) throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey(key), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Shift Cash Cat");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Shift Cash Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);
        Item item = itemRepository.save(Item.builder()
                .barcode(uniqueKey("BC"))
                .name("Shift Cash Item")
                .subCategory(subCategory)
                .costPrice(BigDecimal.valueOf(40))
                .sellingPrice(BigDecimal.valueOf(90))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());
        StockBatch batch = stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch()).item(item).batchCode(uniqueKey("SB"))
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(40)).sellingPrice(BigDecimal.valueOf(90))
                .quantity(10_000).originalQuantity(10_000).build());

        return new Shop(fixture, tenantId, token, adminToken, item.getId(), batch.getId());
    }

    private JsonNode openShift(Shop shop, double openingCash) throws Exception {
        return postJson("/shifts/open", shop.tenantId(), shop.token(), """
                {"openingCash": %s, "note": "shift cash test"}
                """.formatted(openingCash));
    }

    private JsonNode sell(Shop shop, String paymentMethod, double paidAmount) throws Exception {
        // BANK sales require a reference (OrderService rule); anything else ignores it.
        String bankReference = "BANK".equals(paymentMethod) ? ", \"bankReference\": \"REF-SHIFT-1\"" : "";
        return postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d, "orderType": "CASH", "saleMode": "TAKEAWAY",
                  "billDiscount": 0, "paidAmount": %s, "paymentMethod": "%s"%s,
                  "items": [{"itemId": %d, "batchId": %d, "qty": 1, "unitPrice": 90,
                             "discountType": "NONE", "discountValue": 0}]
                }
                """.formatted(shop.branch(), paidAmount, paymentMethod, bankReference, shop.itemId(), shop.batchId()));
    }

    private JsonNode myShift(Shop shop) throws Exception {
        return getJson("/shifts/me", shop.tenantId(), shop.token());
    }

    @Test
    @DisplayName("a card sale does not inflate expected cash, and closing keeps the live drawer number")
    void cardSaleDoesNotInflateExpectedCash() throws Exception {
        Shop shop = shop("card");
        openShift(shop, 1000);

        JsonNode cardSale = sell(shop, "CARD", 90);
        assertThat(cardSale.path("grandTotal").asDouble()).isCloseTo(90, within(0.001));

        JsonNode live = myShift(shop);
        assertThat(live.path("cashSales").asDouble()).isCloseTo(0, within(0.001));
        assertThat(live.path("cardSales").asDouble()).isCloseTo(90, within(0.001));

        JsonNode closed = postJson("/shifts/close", shop.tenantId(), shop.token(), """
                {"countedCash": 1000, "note": "drawer counted"}
                """);

        // Before the fix the close-time recompute counted the 90 card sale as cash and
        // reported 1090 expected — the number users saw change the moment they closed.
        assertThat(closed.path("cashSales").asDouble()).isCloseTo(0, within(0.001));
        assertThat(closed.path("expectedCash").asDouble()).isCloseTo(1000, within(0.001));
        assertThat(closed.path("cashDifference").asDouble()).isCloseTo(0, within(0.001));
    }

    @Test
    @DisplayName("closing keeps the live drawer number instead of re-deriving it from orders")
    void cashRefundSurvivesClose() throws Exception {
        Shop shop = shop("refund");
        openShift(shop, 500);

        // A cash sale, then the same sale is returned. Returns are ADMIN/MANAGER-only,
        // and reverseShiftCash subtracts from the PROCESSING user's open shift, so the
        // manager processes it from their own shift; the cashier's shift below pins the
        // close-time rule this fix owns: whatever the live ledger says must survive.
        JsonNode sale = sell(shop, "CASH", 90);
        long orderItemId = sale.path("items").get(0).path("id").asLong();

        String managerToken = login(shop.tenantId(), shop.fixture().manager().getUsername(), DEFAULT_PASSWORD);
        JsonNode refund = postJson(
                "/orders/" + sale.path("invoiceNo").asText() + "/returns",
                shop.tenantId(), managerToken, """
                {"reason": "damaged goods", "refundMethod": "CASH",
                 "items": [{"orderItemId": %d, "returnQty": 1}]}
                """.formatted(orderItemId));
        assertThat(refund.path("totalRefundAmount").asDouble()).isCloseTo(90, within(0.001));

        // The cashier's live ledger: 90 in from the sale. The manager's shift took the
        // refund out of its own bucket (empty, clamped at 0), which is the known
        // cross-user attribution gap, separate from this fix.
        JsonNode live = myShift(shop);
        double liveCashSales = live.path("cashSales").asDouble();

        JsonNode closed = postJson("/shifts/close", shop.tenantId(), shop.token(), """
                {"countedCash": 590, "note": "drawer counted"}
                """);

        // The old close-time recompute re-summed orders over a time window: card sales
        // were counted as cash, refunds re-added, and anything outside the window
        // dropped. The closed record must be the live number, unchanged.
        assertThat(closed.path("cashSales").asDouble()).isCloseTo(liveCashSales, within(0.001));
        assertThat(closed.path("expectedCash").asDouble())
                .isCloseTo(500 + liveCashSales, within(0.001));
    }

    @Test
    @DisplayName("a bank sale lands in bankSales and leaves the drawer untouched")
    void bankSaleTrackedInOwnBucket() throws Exception {
        Shop shop = shop("bank");
        openShift(shop, 250);

        sell(shop, "BANK", 90);

        JsonNode live = myShift(shop);
        assertThat(live.path("cashSales").asDouble()).isCloseTo(0, within(0.001));
        assertThat(live.path("bankSales").asDouble()).isCloseTo(90, within(0.001));

        JsonNode closed = postJson("/shifts/close", shop.tenantId(), shop.token(), """
                {"countedCash": 250, "note": "drawer counted"}
                """);
        assertThat(closed.path("expectedCash").asDouble()).isCloseTo(250, within(0.001));
        assertThat(closed.path("bankSales").asDouble()).isCloseTo(90, within(0.001));
    }

    @Test
    @DisplayName("settling an old credit due in cash is tracked as a shift cash collection")
    void cashCreditSettlementTrackedAsCollection() throws Exception {
        Shop shop = shop("collect");
        openShift(shop, 100);

        // A credit sale from an earlier moment (no shift needed for it to exist).
        Customer customer = customerRepository.save(Customer.builder()
                .name("Collect Customer").phone("0771234567").creditLimit(500.0).dueAmount(0.0).active(true).build());
        JsonNode creditSale = postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d, "orderType": "CREDIT", "customerId": %d,
                  "billDiscount": 0, "paidAmount": 0,
                  "items": [{"itemId": %d, "batchId": %d, "qty": 1, "unitPrice": 90,
                             "discountType": "NONE", "discountValue": 0}]
                }
                """.formatted(shop.branch(), customer.getId(), shop.itemId(), shop.batchId()));

        JsonNode settlement = postJson(
                "/orders/" + creditSale.path("invoiceNo").asText() + "/payments",
                shop.tenantId(), shop.token(), """
                {"amount": 90, "paymentMethod": "CASH", "note": "paying off the tab"}
                """);
        assertThat(settlement.path("dueAmount").asDouble()).isCloseTo(0, within(0.001));

        JsonNode live = myShift(shop);
        assertThat(live.path("cashCollections").asDouble()).isCloseTo(90, within(0.001));
        // The collection is real drawer money but is NOT folded into expected cash:
        // it is reported, not silently baked in.
        assertThat(live.path("cashSales").asDouble()).isCloseTo(0, within(0.001));
    }

    @Test
    @DisplayName("an offline sale stamped as a UTC instant is stored as Colombo local time")
    void offlineUtcTimestampBecomesColomboTime() throws Exception {
        Shop shop = shop("tz");
        openShift(shop, 0);

        // 09:00Z is 14:30 in Colombo. Jackson receives this as a Z-suffixed string;
        // with lenient-instant parsing enabled it must convert using the mapper zone.
        // uniqueKey() is random per call, so the id is minted once and reused for lookup.
        String utcSaleId = uniqueKey("tz-sale");
        JsonNode imported = postJson("/orders/offline-import", shop.tenantId(), shop.token(), """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-10-07T09:00:00Z",
                  "branchId": %d,
                  "orderType": "CASH", "saleMode": "TAKEAWAY",
                  "billDiscount": 0, "paidAmount": 90, "paymentMethod": "CASH",
                  "items": [{"itemId": %d, "qty": 1, "unitPrice": 90,
                             "discountType": "NONE", "discountValue": 0}]
                }
                """.formatted(utcSaleId, shop.branch(), shop.itemId()));
        assertThat(imported.path("success").asBoolean()).isTrue();

        Order order = orderRepository
                .findByClientSaleId(utcSaleId).orElseThrow();
        assertThat(order.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 14, 30, 0));
        assertThat(order.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 7));

        // The legacy no-offset form must keep its digits exactly as before.
        String legacyId = uniqueKey("tz-legacy");
        postJson("/orders/offline-import", shop.tenantId(), shop.token(), """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T10:15:00",
                  "branchId": %d,
                  "orderType": "CASH", "saleMode": "TAKEAWAY",
                  "billDiscount": 0, "paidAmount": 90, "paymentMethod": "CASH",
                  "items": [{"itemId": %d, "qty": 1, "unitPrice": 90,
                             "discountType": "NONE", "discountValue": 0}]
                }
                """.formatted(legacyId, shop.branch(), shop.itemId()));
        Order legacy = orderRepository.findByClientSaleId(legacyId).orElseThrow();
        assertThat(legacy.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 5, 4, 10, 15, 0));
    }

    @Test
    @DisplayName("a drawer purchase still folds into totalExpenses and is listed under the shift")
    void drawerPurchaseFoldsIntoExpenses() throws Exception {
        Shop shop = shop("drawer");
        // The drawer purchase is paid by the admin, so it is the ADMIN's shift the
        // payment leaves (applyDrawerCashOutIfNeeded resolves the paying user's shift).
        JsonNode adminShift = postJson(
                "/shifts/branch/open?branchId=" + shop.branch(), shop.tenantId(), shop.adminToken(), """
                {"openingCash": 2000, "note": "admin drawer shift"}
                """);
        long shiftId = adminShift.path("id").asLong();

        Supplier supplier = new Supplier();
        supplier.setName("Drawer Supplier");
        supplier.setPhone("0779876543");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        postJson("/purchases", shop.tenantId(), shop.adminToken(), """
                {
                  "supplierId": %d,
                  "invoiceNo": "%s",
                  "paymentMethod": "CASH",
                  "cashSource": "CASH_DRAWER",
                  "branches": [
                    {"branchId": %d, "items": [
                      {"itemId": %d, "qty": 2, "costPrice": 40, "sellingPrice": 90}
                    ]}
                  ],
                  "paidAmount": 80
                }
                """.formatted(supplier.getId(), uniqueKey("PINV"), shop.branch(), shop.itemId()));

        JsonNode live = getJson("/shifts/" + shiftId, shop.tenantId(), shop.adminToken());
        assertThat(live.path("totalExpenses").asDouble()).isCloseTo(80, within(0.001));

        JsonNode purchases = getJson(
                "/shifts/" + shiftId + "/purchases?page=0&size=10", shop.tenantId(), shop.adminToken());
        assertThat(purchases.path("totalElements").asInt()).isEqualTo(1);
        assertThat(purchases.path("content").get(0).path("cashSourceAmount").asDouble())
                .isCloseTo(80, within(0.001));
    }
}
