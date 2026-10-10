package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's switch for below-cost promotions.
 *
 * <p>An item bought at 150 and sold at 200, offered at 125, is a campaign selling below cost.
 * The default answer is a refusal with the item and the numbers named; the campaign's own
 * "allow below cost" flag is the per-campaign escape hatch. What this pins is the shop-level
 * switch: with it on, the same offer saves without any flag on the request, the campaign row
 * is persisted with the flag set (so the engine and the offline bundle, which never read
 * settings, price it the same way), and the price check agrees that the row is OK.
 */
class PromotionBelowCostSettingIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(TenantFixture fixture, String token, Item item) {
        String tenant() { return fixture.tenantId(); }
        long branch() { return fixture.mainBranch().getId(); }
    }

    private Shop shopWithItem() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("bcst"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Below Cost Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Below Cost Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Item item = itemRepository.save(Item.builder()
                .barcode(uniqueKey("BCST"))
                .name("Stock bought at 150")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(150))
                .sellingPrice(BigDecimal.valueOf(200))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());

        return new Shop(fixture, token, item);
    }

    /** The exact scenario from the feature request: 150 cost, 200 normal, 125 offer. */
    private String campaignJson(Shop shop, String name) {
        return """
                {
                  "name": "%s",
                  "scope": "ITEM",
                  "discountType": "PERCENT",
                  "discountValue": 10,
                  "minBillAmount": 0,
                  "maxDiscountAmount": 0,
                  "startAt": "2020-01-01T00:00:00",
                  "endAt": "2099-01-01T00:00:00",
                  "branchId": %d,
                  "active": true,
                  "priority": 0,
                  "allowBelowCost": false,
                  "items": [{"id": %d, "offerPrice": 125}]
                }
                """.formatted(name, shop.branch(), shop.item().getId());
    }

    @Test
    @DisplayName("below cost is refused by default and allowed once the owner switches it on")
    void ownerSwitchLiftsTheGuard() throws Exception {
        Shop shop = shopWithItem();

        // Default: refused, and the refusal names the item and the numbers.
        jsonRequest(org.springframework.http.HttpMethod.POST, "/promotions", shop.tenant(), shop.token(),
                campaignJson(shop, "Cash flow sale"), 400);

        // The price check agrees with the refusal while the switch is off.
        JsonNode refused = postJson("/promotions/price-check", shop.tenant(), shop.token(), """
                {
                  "branchId": %d,
                  "discountType": "NONE", "discountValue": 0, "allowBelowCost": false,
                  "items": [{"id": %d, "offerPrice": 125}]
                }
                """.formatted(shop.branch(), shop.item().getId()));
        assertThat(refused.path("items").get(0).path("status").asText()).isEqualTo("BELOW_COST");

        // The owner switches the shop-level setting on; the toggle is admin-only, like the rest
        // of the settings modal.
        putJson("/promotions/settings", shop.tenant(), shop.token(), """
                {
                  "approvalRequired": false,
                  "approvalThresholdPercent": null,
                  "approvalThresholdAmount": null,
                  "cashierManualStacking": true,
                  "allowBelowCostPromotions": true
                }
                """);

        // The settings row round-trips the new field.
        JsonNode settings = getJson("/promotions/settings", shop.tenant(), shop.token());
        assertThat(settings.path("allowBelowCostPromotions").asBoolean()).isTrue();

        // The same request that was refused now saves...
        JsonNode campaign = postJson("/promotions", shop.tenant(), shop.token(), campaignJson(shop, "Cash flow sale"));
        assertThat(campaign.path("allowBelowCost").asBoolean()).isTrue();

        // ...and the price check now reports the row as OK, so the builder's margin badge
        // cannot disagree with the save that just succeeded.
        JsonNode allowed = postJson("/promotions/price-check", shop.tenant(), shop.token(), """
                {
                  "branchId": %d,
                  "discountType": "NONE", "discountValue": 0, "allowBelowCost": false,
                  "items": [{"id": %d, "offerPrice": 125}]
                }
                """.formatted(shop.branch(), shop.item().getId()));
        assertThat(allowed.path("items").get(0).path("status").asText()).isEqualTo("OK");
    }

    @Test
    @DisplayName("switching the setting off again restores the guard for new campaigns")
    void switchOffRestoresTheGuard() throws Exception {
        Shop shop = shopWithItem();

        putJson("/promotions/settings", shop.tenant(), shop.token(), """
                {
                  "approvalRequired": false,
                  "approvalThresholdPercent": null,
                  "approvalThresholdAmount": null,
                  "cashierManualStacking": true,
                  "allowBelowCostPromotions": true
                }
                """);
        JsonNode saved = postJson("/promotions", shop.tenant(), shop.token(), campaignJson(shop, "Cash flow sale"));
        assertThat(saved.path("allowBelowCost").asBoolean()).isTrue();

        putJson("/promotions/settings", shop.tenant(), shop.token(), """
                {
                  "approvalRequired": false,
                  "approvalThresholdPercent": null,
                  "approvalThresholdAmount": null,
                  "cashierManualStacking": true,
                  "allowBelowCostPromotions": false
                }
                """);

        // The existing campaign keeps its flag - the switch governs new saves, it does not
        // reach back and re-price a campaign the owner already published.
        JsonNode reloaded = getJson("/promotions/" + saved.path("id").asLong(), shop.tenant(), shop.token());
        assertThat(reloaded.path("allowBelowCost").asBoolean()).isTrue();

        // But a fresh below-cost campaign is refused again. Named differently from the one
        // already saved, because DuplicateRequestFilter would otherwise read this identical
        // body as a double-click on the first save and replay its success.
        jsonRequest(org.springframework.http.HttpMethod.POST, "/promotions", shop.tenant(), shop.token(),
                campaignJson(shop, "Second cash flow sale"), 400);
    }
}
