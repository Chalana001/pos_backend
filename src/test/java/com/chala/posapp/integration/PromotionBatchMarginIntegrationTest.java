package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
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
 * A promotion judged against the batches it will actually sell.
 *
 * <p>An item bought twice at different costs and priced differently is two products wearing one
 * name. The margin guard used to read the reference cost kept on the item, which belongs to
 * neither batch: it passed a promotion that loses money on one of them, and FIFO, not the shop,
 * decides which one the customer gets.
 */
class PromotionBatchMarginIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(TenantFixture fixture, String token, Item item) {
        String tenant() { return fixture.tenantId(); }
        long branch() { return fixture.mainBranch().getId(); }
    }

    /**
     * One item, item-level price 600 and cost 60, stocked as two batches:
     * an old cheap one (sells 500, cost 40) and a new dear one (sells 600, cost 550).
     */
    private Shop shopWithTwoBatches() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("btch"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Batch Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Batch Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Item item = itemRepository.save(Item.builder()
                .barcode(uniqueKey("BTCH"))
                .name("Two Batch Item")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(60))
                .sellingPrice(BigDecimal.valueOf(600))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());

        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch()).item(item).batchCode("OLD-CHEAP")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(40)).sellingPrice(BigDecimal.valueOf(500))
                .quantity(5_000).originalQuantity(5_000).build());
        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch()).item(item).batchCode("NEW-DEAR")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(550)).sellingPrice(BigDecimal.valueOf(600))
                .quantity(5_000).originalQuantity(5_000).build());

        return new Shop(fixture, token, item);
    }

    private JsonNode priceCheck(Shop shop, String offerPriceJson) throws Exception {
        return postJson("/promotions/price-check", shop.tenant(), shop.token(), """
                {
                  "branchId": %d,
                  "discountType": "NONE", "discountValue": 0, "allowBelowCost": false,
                  "items": [{"id": %d, "offerPrice": %s}]
                }
                """.formatted(shop.branch(), shop.item().getId(), offerPriceJson));
    }

    @Test
    @DisplayName("an offer price above the item's cost but below a batch's cost is caught")
    void worstBatchDecides() throws Exception {
        Shop shop = shopWithTwoBatches();

        // 500 clears the item's reference cost of 60 easily, and clears the cheap batch's 40.
        // It does not clear the dear batch's 550, and that batch will be sold.
        JsonNode line = priceCheck(shop, "500").path("items").get(0);

        assertThat(line.path("status").asText()).isEqualTo("BELOW_COST");
        assertThat(line.path("message").asText()).contains("NEW-DEAR");
        assertThat(line.path("costPrice").asDouble()).isCloseTo(550, within(0.001));
    }

    @Test
    @DisplayName("the table is told the spread so one name cannot hide two prices")
    void reportsTheSpread() throws Exception {
        Shop shop = shopWithTwoBatches();

        JsonNode line = priceCheck(shop, "500").path("items").get(0);

        assertThat(line.path("batchCount").asInt()).isEqualTo(2);
        assertThat(line.path("minBatchPrice").asDouble()).isCloseTo(500, within(0.001));
        assertThat(line.path("maxBatchPrice").asDouble()).isCloseTo(600, within(0.001));
        // The headline stays the item's price. That is the number the shop thinks in.
        assertThat(line.path("normalPrice").asDouble()).isCloseTo(600, within(0.001));
    }

    @Test
    @DisplayName("a price that clears every batch is fine, and says so")
    void clearsEveryBatch() throws Exception {
        Shop shop = shopWithTwoBatches();

        JsonNode line = priceCheck(shop, "580").path("items").get(0);

        // 580 is above the dear batch's 550, and the cheap batch sells at 500 anyway - the
        // engine caps the offer at each batch's own price and never raises one.
        assertThat(line.path("status").asText()).isEqualTo("OK");
    }

    @Test
    @DisplayName("an item with no batches still falls back to its own figures")
    void noBatchesFallsBack() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("btch"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        Category category = new Category();
        category.setName("Bare Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Bare Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);
        Item bare = itemRepository.save(Item.builder()
                .barcode(uniqueKey("BARE")).name("Never Received")
                .subCategory(subCategory).itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(100)).sellingPrice(BigDecimal.valueOf(200))
                .reorderLevel(0).active(true).posVisible(true).build());

        JsonNode line = postJson("/promotions/price-check", fixture.tenantId(), token, """
                {
                  "branchId": %d,
                  "discountType": "NONE", "discountValue": 0, "allowBelowCost": false,
                  "items": [{"id": %d, "offerPrice": 80}]
                }
                """.formatted(fixture.mainBranch().getId(), bare.getId())).path("items").get(0);

        assertThat(line.path("status").asText()).isEqualTo("BELOW_COST");
        assertThat(line.path("batchCount").asInt()).isZero();
        assertThat(line.path("costPrice").asDouble()).isCloseTo(100, within(0.001));
    }
}
