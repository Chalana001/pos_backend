package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Close Shift dialog's per-payment-method breakdown. The rows must come
 * from the order_payments ledger grouped over the shift's window, the totals
 * must never double count, unknown/legacy codes must still render, and the
 * expected-cash formula must stay exactly the drawer's own event ledger.
 */
class ShiftPaymentSummaryIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(TenantFixture fixture, String tenantId, String token, String adminToken, long itemId, long batchId) {
        long branch() {
            return fixture.mainBranch().getId();
        }
    }

    private Shop shop(String key) throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey(key), 1);
        String token = login(fixture.tenantId(), fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        String adminToken = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("PM Sum Cat");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("PM Sum Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);
        Item item = itemRepository.save(Item.builder()
                .barcode(uniqueKey("BC"))
                .name("PM Sum Item")
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
                .quantity(100_000).originalQuantity(100_000).build());

        return new Shop(fixture, fixture.tenantId(), token, adminToken, item.getId(), batch.getId());
    }

    private JsonNode openShift(Shop shop) throws Exception {
        return postJson("/shifts/open", shop.tenantId(), shop.token(), """
                {"openingCash": 1000, "note": "payment summary test"}
                """);
    }

    private JsonNode sell(Shop shop, String method, double total, int qty) throws Exception {
        String reference = "BANK".equals(method) ? ", \"bankReference\": \"REF-1\"" : "";
        return postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d, "orderType": "CASH", "saleMode": "TAKEAWAY",
                  "billDiscount": 0, "paidAmount": %s, "paymentMethod": "%s"%s,
                  "items": [{"itemId": %d, "batchId": %d, "qty": %d, "unitPrice": 90,
                             "discountType": "NONE", "discountValue": 0}]
                }
                """.formatted(shop.branch(), total, method, reference, shop.itemId(), shop.batchId(), qty));
    }

    private JsonNode sell(Shop shop, String method, double total) throws Exception {
        return sell(shop, method, total, 1);
    }

    private JsonNode summary(Shop shop, long shiftId, String token) throws Exception {
        return getJson("/shifts/" + shiftId + "/payment-summary", shop.tenantId(), token);
    }

    private JsonNode methodRow(JsonNode summary, String code) {
        for (JsonNode row : summary.path("methods")) {
            if (code.equals(row.path("code").asText())) {
                return row;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a shift with cash, card and bank sales gets one correct row per method")
    void perMethodRowsWithCountsAndTotals() throws Exception {
        Shop shop = shop("rows");
        JsonNode shift = openShift(shop);

        sell(shop, "CASH", 90);
        sell(shop, "CARD", 90);
        sell(shop, "CARD", 180, 2);
        sell(shop, "BANK", 90);

        JsonNode summary = summary(shop, shift.path("id").asLong(), shop.token());

        // Ledger truth: amount + shortfall = grand total, summed once per row.
        assertThat(summary.path("totalSales").asDouble()).isCloseTo(450, within(0.001));
        assertThat(summary.path("totalTransactions").asLong()).isEqualTo(4);

        JsonNode cash = methodRow(summary, "CASH");
        assertThat(cash).isNotNull();
        assertThat(cash.path("transactionCount").asLong()).isEqualTo(1);
        assertThat(cash.path("amount").asDouble()).isCloseTo(90, within(0.001));
        assertThat(cash.path("includeInExpectedCash").asBoolean()).isTrue();
        assertThat(cash.path("displayName").asText()).isEqualTo("Cash");
        assertThat(cash.path("category").asText()).isEqualTo("CASH");

        JsonNode card = methodRow(summary, "CARD");
        assertThat(card.path("transactionCount").asLong()).isEqualTo(2);
        assertThat(card.path("amount").asDouble()).isCloseTo(270, within(0.001));
        assertThat(card.path("includeInExpectedCash").asBoolean()).isFalse();

        JsonNode bank = methodRow(summary, "BANK");
        assertThat(bank.path("amount").asDouble()).isCloseTo(90, within(0.001));
        assertThat(bank.path("unknown").asBoolean()).isFalse();

        // Percentages sum to 100 and each row's share is correct.
        assertThat(cash.path("percentage").asDouble()).isCloseTo(20.0, within(0.001));
        assertThat(card.path("percentage").asDouble()).isCloseTo(60.0, within(0.001));
        double sum = 0;
        for (JsonNode row : summary.path("methods")) {
            sum += row.path("percentage").asDouble();
        }
        assertThat(sum).isCloseTo(100.0, within(0.001));
    }

    @Test
    @DisplayName("a newly created payment method appears in the shift summary with no code change")
    void newlyCreatedMethodAppears() throws Exception {
        Shop shop = shop("new-method");
        JsonNode shift = openShift(shop);

        postJson("/payment-methods", shop.tenantId(), shop.adminToken(), """
                {"displayName": "QR Pay", "category": "MOBILE_DIGITAL",
                 "referencePolicy": "NONE", "includeInExpectedCash": false,
                 "allowsCredit": false, "active": true}
                """);
        sell(shop, "QR_PAY", 90);

        JsonNode summary = summary(shop, shift.path("id").asLong(), shop.token());
        JsonNode qr = methodRow(summary, "QR_PAY");
        assertThat(qr).isNotNull();
        assertThat(qr.path("displayName").asText()).isEqualTo("QR Pay");
        assertThat(qr.path("category").asText()).isEqualTo("MOBILE_DIGITAL");
        assertThat(qr.path("amount").asDouble()).isCloseTo(90, within(0.001));
        assertThat(qr.path("unknown").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("an unknown method's sales still render, humanized and ungrouped")
    void unknownMethodStillRenders() throws Exception {
        Shop shop = shop("unknown");
        JsonNode shift = openShift(shop);

        // Simulate a ledger row for a code with no configuration: the sale is
        // inserted directly so the API contract is not bypassed elsewhere.
        com.chala.posapp.entity.Order order = orderRepository.save(com.chala.posapp.entity.Order.builder()
                .invoiceNo(uniqueKey("INV"))
                .branchId(shop.branch())
                .cashierUserId(shop.fixture.cashier().getId())
                .orderType(com.chala.posapp.entity.OrderType.CASH)
                .paymentMethod("SOMETHING_OLD")
                .status(com.chala.posapp.entity.OrderStatus.COMPLETED)
                .subTotal(90.0).billDiscount(0.0).grandTotal(90.0)
                .paidAmount(90.0).dueAmount(0.0)
                .salePaidAmount(90.0).saleDueAmount(0.0)
                .build());
        orderPaymentRepository.save(com.chala.posapp.entity.OrderPayment.builder()
                .orderId(order.getId())
                .invoiceNo(order.getInvoiceNo())
                .branchId(shop.branch())
                .cashierUserId(shop.fixture.cashier().getId())
                .amount(90.0)
                .paymentMethod("SOMETHING_OLD")
                .build());

        JsonNode summary = summary(shop, shift.path("id").asLong(), shop.token());
        JsonNode old = methodRow(summary, "SOMETHING_OLD");
        assertThat(old).isNotNull();
        assertThat(old.path("displayName").asText()).isEqualTo("Something old");
        assertThat(old.path("category").asText()).isEqualTo("OTHER");
        assertThat(old.path("unknown").asBoolean()).isTrue();
        assertThat(old.path("amount").asDouble()).isCloseTo(90, within(0.001));
    }

    @Test
    @DisplayName("expected cash still uses the drawer ledger and excludes card and bank")
    void expectedCashUnchanged() throws Exception {
        Shop shop = shop("expected");
        JsonNode shift = openShift(shop);

        sell(shop, "CASH", 90);
        sell(shop, "CARD", 90);
        sell(shop, "BANK", 90);

        JsonNode live = getJson("/shifts/me", shop.tenantId(), shop.token());
        // opening 1000 + cash 90; card and bank never touch the drawer.
        assertThat(live.path("cashSales").asDouble()).isCloseTo(90, within(0.001));
        assertThat(live.path("cardSales").asDouble()).isCloseTo(90, within(0.001));
        assertThat(live.path("bankSales").asDouble()).isCloseTo(90, within(0.001));

        JsonNode closed = postJson("/shifts/close", shop.tenantId(), shop.token(), """
                {"countedCash": 1090, "note": "counted"}
                """);
        assertThat(closed.path("expectedCash").asDouble()).isCloseTo(1090, within(0.001));
        assertThat(closed.path("cashDifference").asDouble()).isCloseTo(0, within(0.001));
    }

    @Test
    @DisplayName("the cashier reads their own shift summary; another cashier is refused")
    void cashierOwnShiftAccessOnly() throws Exception {
        Shop shop = shop("own");
        JsonNode shift = openShift(shop);

        // The owning cashier: 200 (this is the close dialog's data source).
        JsonNode own = summary(shop, shift.path("id").asLong(), shop.token());
        assertThat(own.path("shiftId").asLong()).isEqualTo(shift.path("id").asLong());

        // A different cashier in the same tenant has no access to this shift.
        User other = createUser(shop.tenantId(), uniqueKey("other-cashier"),
                com.chala.posapp.entity.Role.CASHIER, shop.branch(), DEFAULT_PASSWORD, true);
        String otherToken = login(shop.tenantId(), other.getUsername(), DEFAULT_PASSWORD);
        jsonRequest(org.springframework.http.HttpMethod.GET,
                "/shifts/" + shift.path("id").asLong() + "/payment-summary",
                shop.tenantId(), otherToken, null, 400);
    }

    @Test
    @DisplayName("canceled sales are excluded from the method breakdown")
    void canceledSalesExcluded() throws Exception {
        Shop shop = shop("canceled");
        JsonNode shift = openShift(shop);

        JsonNode sale = sell(shop, "CARD", 90);
        postJson("/orders/" + sale.path("invoiceNo").asText() + "/cancel",
                shop.tenantId(), shop.adminToken(), "{\"reason\": \"mistake\"}");

        JsonNode summary = summary(shop, shift.path("id").asLong(), shop.token());
        assertThat(summary.path("totalTransactions").asLong()).isEqualTo(0);
        assertThat(summary.path("totalSales").asDouble()).isCloseTo(0, within(0.001));
        assertThat(methodRow(summary, "CARD")).isNull();
    }

    @Test
    @DisplayName("a bank sale's reference is visible on the shift's orders list")
    void referenceOnShiftOrders() throws Exception {
        Shop shop = shop("reference");
        openShift(shop);

        JsonNode sale = sell(shop, "BANK", 90);
        JsonNode orders = getJson("/shifts/" + sale.path("id") + "/orders?page=0&size=10",
                shop.tenantId(), shop.adminToken());
        // The endpoint is shift-id keyed; use the returned order via the summary path.
        JsonNode live = getJson("/shifts/me", shop.tenantId(), shop.token());
        JsonNode page = getJson("/shifts/" + live.path("id").asLong() + "/orders?page=0&size=10",
                shop.tenantId(), shop.adminToken());
        boolean found = false;
        for (JsonNode row : page.path("content")) {
            if (sale.path("invoiceNo").asText().equals(row.path("invoiceNo").asText())) {
                found = true;
                assertThat(row.path("bankReference").asText()).isEqualTo("REF-1");
            }
        }
        assertThat(found).isTrue();
    }
}
