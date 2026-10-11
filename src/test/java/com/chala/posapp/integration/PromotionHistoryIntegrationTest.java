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
 * {@code GET /promotions/history} and {@code GET /promotions/redemptions}, the Campaign
 * History page's two halves.
 *
 * <p>Both used to answer without page metadata: history returned every campaign ever, and
 * the ledger returned a window whose "is there more" was a guess from whether the page came
 * back full. This pins the page contract both now carry.
 */
class PromotionHistoryIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(TenantFixture fixture, String token, Long branchId) {
        String tenant() { return fixture.tenantId(); }
    }

    private Shop shop() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("phist"), 2);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        return new Shop(fixture, token, fixture.mainBranch().getId());
    }

    private JsonNode createCampaign(Shop shop, String name) throws Exception {
        // Unique per campaign: H2's shared test catalog enforces sub-category name uniqueness,
        // and one shop's history suite creates several campaigns in one tenant context.
        String suffix = uniqueKey("HS");
        Category category = new Category();
        category.setName("History Category " + suffix);
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("History Sub " + suffix);
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);
        Item item = itemRepository.save(Item.builder()
                .barcode(uniqueKey("HIST"))
                .name("History item " + suffix)
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(100))
                .sellingPrice(BigDecimal.valueOf(150))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());

        return postJson("/promotions", shop.tenant(), shop.token(), """
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
                  "items": [{"id": %d}]
                }
                """.formatted(name, shop.branchId(), item.getId()));
    }

    private JsonNode history(Shop shop, String query) throws Exception {
        return getJson("/promotions/history" + (query == null || query.isBlank() ? "" : "?" + query),
                shop.tenant(), shop.token());
    }

    @Test
    @DisplayName("campaign history is a page with metadata")
    void campaignHistoryIsPaged() throws Exception {
        Shop shop = shop();
        createCampaign(shop, "History page campaign");

        JsonNode result = history(shop, "page=0&size=25");

        assertThat(result.path("page").asInt()).isZero();
        assertThat(result.path("size").asInt()).isEqualTo(25);
        assertThat(result.path("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(result.path("totalPages").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("items").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a retired campaign stays in history, unlike the board")
    void retiredCampaignStaysInHistory() throws Exception {
        Shop shop = shop();
        JsonNode campaign = createCampaign(shop, "Retired but still historic");

        deleteExpectingStatus("/promotions/" + campaign.path("id").asLong(), shop.tenant(), shop.token(), 204);

        JsonNode result = history(shop, "search=Retired but still historic");
        assertThat(result.path("totalElements").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("deleted").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("campaign history searches by name")
    void campaignHistorySearchesByName() throws Exception {
        Shop shop = shop();
        createCampaign(shop, "Named history campaign");
        createCampaign(shop, "Different campaign");

        JsonNode result = history(shop, "search=Named history");
        assertThat(result.path("totalElements").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("name").asText()).isEqualTo("Named history campaign");
    }

    @Test
    @DisplayName("campaign history filters by branch")
    void campaignHistoryFiltersByBranch() throws Exception {
        Shop shop = shop();
        createCampaign(shop, "History branch campaign");

        assertThat(history(shop, "branchId=999999").path("totalElements").asInt()).isZero();
        assertThat(history(shop, "branchId=" + shop.branchId()).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("history pages do not overlap")
    void campaignHistoryPagesDoNotOverlap() throws Exception {
        Shop shop = shop();
        for (int i = 0; i < 4; i++) {
            createCampaign(shop, "Paged history campaign " + i);
        }

        JsonNode first = history(shop, "page=0&size=2");
        JsonNode second = history(shop, "page=1&size=2");

        assertThat(first.path("size").asInt()).isEqualTo(2);
        long firstA = first.path("items").get(0).path("id").asLong();
        long firstB = first.path("items").get(1).path("id").asLong();
        for (JsonNode row : second.path("items")) {
            long id = row.path("id").asLong();
            assertThat(id).isNotEqualTo(firstA).isNotEqualTo(firstB);
        }
    }

    @Test
    @DisplayName("history clamps size: zero up to one, huge down to one hundred")
    void campaignHistoryClampsSize() throws Exception {
        Shop shop = shop();
        createCampaign(shop, "Clamped history campaign");

        assertThat(history(shop, "size=0").path("size").asInt()).isEqualTo(1);
        assertThat(history(shop, "size=5000").path("size").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("the redemption ledger is a page with a real total")
    void redemptionsArePagedWithTotals() throws Exception {
        Shop shop = shop();

        JsonNode result = getJson("/promotions/redemptions?page=0&size=100&from=2020-01-01&to=2099-01-01",
                shop.tenant(), shop.token());

        assertThat(result.path("page").asInt()).isZero();
        assertThat(result.path("size").asInt()).isEqualTo(100);
        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("totalElements").asLong()).isGreaterThanOrEqualTo(result.path("items").size());
        assertThat(result.path("totalPages").asInt())
                .isEqualTo((int) Math.ceil(result.path("totalElements").asLong() / 100.0));
        assertThat(result.path("first").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("redemption size is clamped: zero up to one, huge down to five hundred")
    void redemptionSizeIsClamped() throws Exception {
        Shop shop = shop();

        JsonNode tiny = getJson("/promotions/redemptions?size=0", shop.tenant(), shop.token());
        JsonNode huge = getJson("/promotions/redemptions?size=100000", shop.tenant(), shop.token());

        assertThat(tiny.path("size").asInt()).isEqualTo(1);
        assertThat(huge.path("size").asInt()).isEqualTo(500);
    }

    @Test
    @DisplayName("the api mount serves the same paged history shape")
    void apiAliasServesTheSameShape() throws Exception {
        Shop shop = shop();
        createCampaign(shop, "Alias history campaign");

        JsonNode result = getJson("/api/promotions/history?page=0&size=25", shop.tenant(), shop.token());
        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("size").asInt()).isEqualTo(25);
    }
}
