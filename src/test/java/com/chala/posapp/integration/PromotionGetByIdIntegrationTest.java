package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /promotions/{id}}, the read the edit screen is built on.
 *
 * <p>It used to load the whole list and find its row client-side, then fetch each item by id
 * one at a time, forty sequential round trips on a forty-item campaign. One call now returns
 * the campaign with its item targets resolved to name, barcode and shelf price, and the code
 * count the builder gates activation on.
 *
 * <p>Tenancy note: isolation here is per-catalog routing, not a WHERE clause, so in this H2
 * suite, where every test tenant resolves to one shared catalog, a cross-tenant read cannot
 * be simulated the way production would fail it. The unknown-id 404 below is the honest
 * equivalent under the test profile.
 */
class PromotionGetByIdIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(TenantFixture fixture, String token, Item tea, Item rice) {
        String tenant() { return fixture.tenantId(); }
        long branch() { return fixture.mainBranch().getId(); }
    }

    private Shop shopWithTwoItems() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("pget"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Get Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Get Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Item tea = itemRepository.save(Item.builder()
                .barcode(uniqueKey("TEA")).name("Promotion Tea 500g")
                .subCategory(subCategory).itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(400)).sellingPrice(BigDecimal.valueOf(500))
                .reorderLevel(0).active(true).posVisible(true)
                .build());
        Item rice = itemRepository.save(Item.builder()
                .barcode(uniqueKey("RICE")).name("Promotion Rice 1kg")
                .subCategory(subCategory).itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(180)).sellingPrice(BigDecimal.valueOf(220))
                .reorderLevel(0).active(true).posVisible(true)
                .build());

        return new Shop(fixture, token, tea, rice);
    }

    /**
     * An ITEM campaign carrying a different offer price per item: the shape the edit screen
     * exists to render, and the one the whole list-and-find flow was worst at.
     */
    private JsonNode createCampaign(Shop shop) throws Exception {
        return postJson("/promotions", shop.tenant(), shop.token(), """
                {
                  "name": "Avurudu Sale",
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
                  "items": [
                    {"id": %d, "offerPrice": 450},
                    {"id": %d, "offerPrice": 199}
                  ]
                }
                """.formatted(shop.branch(), shop.tea().getId(), shop.rice().getId()));
    }

    @Test
    @DisplayName("one call returns the campaign with item names, barcodes and shelf prices")
    void resolvesItemTargets() throws Exception {
        Shop shop = shopWithTwoItems();
        long campaignId = createCampaign(shop).path("id").asLong();

        JsonNode promotion = getJson("/promotions/" + campaignId, shop.tenant(), shop.token());

        assertThat(promotion.path("name").asText()).isEqualTo("Avurudu Sale");
        assertThat(promotion.path("itemIds").size()).isEqualTo(2);

        JsonNode tea = promotion.path("items").get(0);
        assertThat(tea.path("id").asLong()).isEqualTo(shop.tea().getId());
        assertThat(tea.path("name").asText()).isEqualTo("Promotion Tea 500g");
        assertThat(tea.path("barcode").asText()).isEqualTo(shop.tea().getBarcode());
        assertThat(tea.path("sellingPrice").asDouble()).isEqualTo(500.0);
        assertThat(tea.path("offerPrice").asDouble()).isEqualTo(450.0);

        JsonNode rice = promotion.path("items").get(1);
        assertThat(rice.path("sellingPrice").asDouble()).isEqualTo(220.0);
        assertThat(rice.path("offerPrice").asDouble()).isEqualTo(199.0);
    }

    @Test
    @DisplayName("the code count the builder gates activation on is answered, not loaded")
    void countsCodesWithoutLoadingThem() throws Exception {
        Shop shop = shopWithTwoItems();
        long campaignId = createCampaign(shop).path("id").asLong();

        // Zero before any code exists: the builder's "add a code before activating" state.
        assertThat(getJson("/promotions/" + campaignId, shop.tenant(), shop.token())
                .path("codeCount").asInt()).isZero();

        // A public code makes the campaign code-gated, which is exactly what codeCount reports.
        postJson("/promotions/" + campaignId + "/codes", shop.tenant(), shop.token(), """
                {
                  "code": "AVURUDU25",
                  "count": 1,
                  "codeType": "PUBLIC",
                  "maxRedemptions": null,
                  "perCustomerLimit": null,
                  "validFrom": null,
                  "validTo": null
                }
                """);

        JsonNode promotion = getJson("/promotions/" + campaignId, shop.tenant(), shop.token());
        assertThat(promotion.path("codeCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retired campaign is not found: soft delete keeps it out of the admin surface")
    void retiredCampaignIsNotFound() throws Exception {
        Shop shop = shopWithTwoItems();
        long campaignId = createCampaign(shop).path("id").asLong();

        deleteExpectingStatus("/promotions/" + campaignId, shop.tenant(), shop.token(), 204);
        jsonRequest(HttpMethod.GET, "/promotions/" + campaignId, shop.tenant(), shop.token(), null, 404);
    }

    @Test
    @DisplayName("an unknown id is a plain 404, not a 500")
    void unknownIdIsNotFound() throws Exception {
        Shop shop = shopWithTwoItems();

        jsonRequest(HttpMethod.GET, "/promotions/999999", shop.tenant(), shop.token(), null, 404);
    }

    @Test
    @DisplayName("the {id} pattern does not shadow the fixed sub-paths on the same controller")
    void literalSubPathsStillResolve() throws Exception {
        Shop shop = shopWithTwoItems();
        createCampaign(shop);

        // A campaign named "history" must not hijack GET /promotions/history: Spring prefers
        // literal patterns over templates, and these three prove it held on this controller,
        // where /{id} was added after fixed GET routes already existed. The ids in "othr-1"
        // style could never collide, but a numeric campaign id and these paths share a shape,
        // which is exactly the regression this pins.
        getJson("/promotions/history", shop.tenant(), shop.token());
        getJson("/promotions/settings", shop.tenant(), shop.token());
        getJson("/promotions/redemptions", shop.tenant(), shop.token());
    }
}
