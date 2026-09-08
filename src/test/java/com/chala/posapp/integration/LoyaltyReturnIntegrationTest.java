package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A sale paid partly in points, then returned - the whole way through the HTTP surface.
 *
 * <p>This is the money path. The unit tests pin each service on its own; this one proves the
 * figures a cashier and a customer actually see agree with each other: what the till charged,
 * what the return hands back in cash, what comes back as points, and what the balance says
 * afterwards. It runs on H2 under the {@code test} profile, so it needs no Docker and runs on
 * every machine - the container suite does not.
 *
 * <p>The scheme is one point per rupee, spent at one rupee per point, so every figure below can
 * be checked in your head.
 */
class LoyaltyReturnIntegrationTest extends ApiIntegrationTestSupport {

    private static final double UNIT_PRICE = 1000.0;

    private record Shop(TenantFixture fixture, String token, Item item, Customer customer) {
        String tenant() { return fixture.tenantId(); }
        long branch() { return fixture.mainBranch().getId(); }
    }

    /** A shop with one 1,000-rupee item in stock and a customer holding 1,000 points. */
    private Shop shop() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("loy"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Loyalty Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Loyalty Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Item item = itemRepository.save(Item.builder()
                .barcode(uniqueKey("LOY"))
                .name("Loyalty Test Item")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(100))
                .sellingPrice(BigDecimal.valueOf(UNIT_PRICE))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());
        // Stock is held in normalised base units: 10 pieces = 10,000.
        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch()).item(item).batchCode(uniqueKey("B"))
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(100)).sellingPrice(BigDecimal.valueOf(UNIT_PRICE))
                .quantity(10_000).originalQuantity(10_000).build());

        Customer customer = customerRepository.save(Customer.builder()
                .name("Points Customer").phone("0770000001").dueAmount(0.0).active(true).build());

        putJson("/loyalty/settings", fixture.tenantId(), token, """
                {"enabled": true, "pointsPerCurrency": 1, "currencyPerPoint": 1,
                 "minRedemptionPoints": 0, "maxRedemptionPercent": null, "roundEarnedDown": true}
                """);
        postJson("/loyalty/customers/" + customer.getId() + "/adjust", fixture.tenantId(), token, """
                {"points": 1000, "note": "opening balance"}
                """);

        return new Shop(fixture, token, item, customer);
    }

    private JsonNode sell(Shop shop, int qty, int pointsToRedeem, double cashPaid) throws Exception {
        return postJson("/orders", shop.tenant(), shop.token(), """
                {
                  "branchId": %d, "orderType": "CASH", "saleMode": "TAKEAWAY",
                  "customerId": %d, "billDiscount": 0,
                  "loyaltyPointsToRedeem": %d,
                  "paidAmount": %s, "paymentMethod": "CASH",
                  "items": [{"itemId": %d, "qty": %d, "qtyUnit": "PCS", "unitPrice": %s,
                             "discountType": "NONE", "discountValue": 0}]
                }
                """.formatted(shop.branch(), shop.customer().getId(), pointsToRedeem,
                cashPaid, shop.item().getId(), qty, UNIT_PRICE));
    }

    /**
     * @param reason must differ between two returns of the same sale: DuplicateRequestFilter
     *               replays the first response to a byte-identical POST within its window,
     *               which is exactly what two back-to-back partial returns would be.
     */
    private JsonNode returnUnits(Shop shop, JsonNode sale, int units, String reason) throws Exception {
        long orderItemId = sale.path("items").get(0).path("id").asLong();
        return postJson("/orders/" + sale.path("invoiceNo").asText() + "/returns",
                shop.tenant(), shop.token(), """
                {"reason": "%s", "refundMethod": "CASH",
                 "items": [{"orderItemId": %d, "returnQty": %d}]}
                """.formatted(reason, orderItemId, units));
    }

    private int balance(Shop shop) throws Exception {
        return getJson("/loyalty/customers/" + shop.customer().getId(), shop.tenant(), shop.token())
                .path("pointsBalance").asInt();
    }

    @Test
    @DisplayName("a sale paid half in points charges the cash half, earns on it, and banks the balance")
    void saleSettledPartlyInPoints() throws Exception {
        Shop shop = shop();

        JsonNode sale = sell(shop, 1, 500, 500);

        assertThat(sale.path("subTotal").asDouble()).isCloseTo(1000, within(0.001));
        assertThat(sale.path("loyaltyPointsRedeemed").asInt()).isEqualTo(500);
        assertThat(sale.path("loyaltyDiscountAmount").asDouble()).isCloseTo(500, within(0.001));
        assertThat(sale.path("grandTotal").asDouble()).isCloseTo(500, within(0.001));
        assertThat(sale.path("dueAmount").asDouble()).isCloseTo(0, within(0.001));
        // Earned on what was paid in money - not on the 500 the points covered.
        assertThat(sale.path("loyaltyPointsEarned").asInt()).isEqualTo(500);
        // 1000 - 500 spent + 500 earned, as at the sale, and the account agrees.
        assertThat(sale.path("loyaltyPointsBalance").asInt()).isEqualTo(1000);
        assertThat(balance(shop)).isEqualTo(1000);
    }

    @Test
    @DisplayName("returning everything refunds only the cash paid and gives the spent points back")
    void fullReturn() throws Exception {
        Shop shop = shop();
        JsonNode sale = sell(shop, 1, 500, 500);

        JsonNode ret = returnUnits(shop, sale, 1, "everything back");

        // 1,000 of goods came back. The customer paid 500 cash and 500 in points for them.
        assertThat(ret.path("totalRefundAmount").asDouble()).isCloseTo(500, within(0.001));
        assertThat(ret.path("loyaltyValueReturned").asDouble()).isCloseTo(500, within(0.001));
        assertThat(ret.path("loyaltyPointsGivenBack").asInt()).isEqualTo(500);
        // And the 500 the sale had earned are taken back.
        assertThat(ret.path("loyaltyPointsTakenBack").asInt()).isEqualTo(500);
        // 1000 after the sale, +500 returned, -500 clawed back.
        assertThat(ret.path("loyaltyPointsBalance").asInt()).isEqualTo(1000);
        assertThat(balance(shop)).isEqualTo(1000);
        // The line on the slip still shows what the goods sold for; the refund is the cash share.
        assertThat(ret.path("items").get(0).path("finalUnitPrice").asDouble()).isCloseTo(1000, within(0.001));
        assertThat(ret.path("items").get(0).path("refundLineAmount").asDouble()).isCloseTo(500, within(0.001));
    }

    @Test
    @DisplayName("a partial return splits each unit the way the sale was settled, and two partials never exceed the sale")
    void partialReturns() throws Exception {
        Shop shop = shop();
        // Two units, 2,000 of goods: 500 in points, 1,500 cash. Earns 1,500.
        JsonNode sale = sell(shop, 2, 500, 1500);
        assertThat(sale.path("grandTotal").asDouble()).isCloseTo(1500, within(0.001));
        assertThat(sale.path("loyaltyPointsEarned").asInt()).isEqualTo(1500);
        assertThat(balance(shop)).isEqualTo(2000); // 1000 - 500 + 1500

        // One unit back: 1,000 of goods, settled 75% cash / 25% points.
        JsonNode first = returnUnits(shop, sale, 1, "first unit");
        assertThat(first.path("totalRefundAmount").asDouble()).isCloseTo(750, within(0.001));
        assertThat(first.path("loyaltyValueReturned").asDouble()).isCloseTo(250, within(0.001));
        assertThat(first.path("loyaltyPointsGivenBack").asInt()).isEqualTo(250);
        // Half the sale came back, so half of what it earned goes.
        assertThat(first.path("loyaltyPointsTakenBack").asInt()).isEqualTo(750);
        assertThat(balance(shop)).isEqualTo(1500); // 2000 + 250 - 750

        // The other unit completes the order, but it is still a partial: the whole-order path
        // would reverse the first return's own reversal rows. Same split, and the points cap
        // holds - 250 already came back of the 500 spent, so exactly 250 more can.
        JsonNode second = returnUnits(shop, sale, 1, "second unit");
        assertThat(second.path("totalRefundAmount").asDouble()).isCloseTo(750, within(0.001));
        assertThat(second.path("loyaltyPointsGivenBack").asInt()).isEqualTo(250);
        assertThat(second.path("loyaltyPointsTakenBack").asInt()).isEqualTo(750);
        assertThat(balance(shop)).isEqualTo(1000); // back to where the customer started

        // Across both returns: cash out equals cash in, points back equal points spent.
        assertThat(first.path("totalRefundAmount").asDouble() + second.path("totalRefundAmount").asDouble())
                .isCloseTo(1500, within(0.001));
        assertThat(first.path("loyaltyPointsGivenBack").asInt() + second.path("loyaltyPointsGivenBack").asInt())
                .isEqualTo(500);
    }

    @Test
    @DisplayName("a sale with no points refunds the goods' value and moves no points back")
    void plainReturn() throws Exception {
        Shop shop = shop();
        JsonNode sale = sell(shop, 1, 0, 1000);
        assertThat(sale.path("loyaltyPointsRedeemed").asInt()).isZero();

        JsonNode ret = returnUnits(shop, sale, 1, "plain return");

        assertThat(ret.path("totalRefundAmount").asDouble()).isCloseTo(1000, within(0.001));
        assertThat(ret.path("loyaltyValueReturned").asDouble()).isCloseTo(0, within(0.001));
        assertThat(ret.path("loyaltyPointsGivenBack").asInt()).isZero();
        // The 1,000 earned on the sale go back with the goods.
        assertThat(ret.path("loyaltyPointsTakenBack").asInt()).isEqualTo(1000);
        assertThat(balance(shop)).isEqualTo(1000);
    }
}
