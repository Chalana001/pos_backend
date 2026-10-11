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
 * {@code GET /promotions}, the campaign board's own page.
 *
 * <p>The board used to receive every campaign the shop ever had and slice pages in the
 * browser. This pins the server-side contract: page metadata, the branch filter, and the
 * two halves of the search - the campaign's own name, and the name or barcode of an item
 * it targets.
 */
class PromotionListIntegrationTest extends ApiIntegrationTestSupport {

    private record Shop(TenantFixture fixture, String token, Long branchId, Item haal) {
        String tenant() { return fixture.tenantId(); }
    }

    private Shop shopWithItem() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("plist"), 2);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("List Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("List Sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Item haal = itemRepository.save(Item.builder()
                .barcode(uniqueKey("HAAL"))
                .name("Haal 1kg")
                .altName("Rice 1kg")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(150))
                .sellingPrice(BigDecimal.valueOf(200))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());

        return new Shop(fixture, token, fixture.mainBranch().getId(), haal);
    }

    private JsonNode createCampaign(Shop shop, String name, boolean active) throws Exception {
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
                  "active": %s,
                  "priority": 0,
                  "items": [{"id": %d}]
                }
                """.formatted(name, shop.branchId(), active, shop.haal().getId()));
    }

    private JsonNode page(Shop shop, String query) throws Exception {
        return getJson("/promotions" + (query == null || query.isBlank() ? "" : "?" + query),
                shop.tenant(), shop.token());
    }

    @Test
    @DisplayName("the response is a page, with metadata rather than a whole list")
    void returnsPageShape() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "Page shape sale", true);

        JsonNode result = page(shop, "page=0&size=25");

        assertThat(result.path("page").asInt()).isZero();
        assertThat(result.path("size").asInt()).isEqualTo(25);
        assertThat(result.path("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(result.path("totalPages").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(result.path("first").asBoolean()).isTrue();
        assertThat(result.path("last").asBoolean()).isTrue();
        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("items").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("search matches the campaign's own name")
    void searchByCampaignName() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "Avurudu mega sale", true);
        createCampaign(shop, "Ordinary Tuesday", true);

        JsonNode result = page(shop, "search=avurudu");

        assertThat(result.path("totalElements").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("name").asText()).isEqualTo("Avurudu mega sale");
    }

    @Test
    @DisplayName("search matches a target item's name, alternate name and barcode")
    void searchByTargetItem() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "Named for the item, not itself", true);

        assertThat(page(shop, "search=haal").path("totalElements").asInt()).isEqualTo(1);
        assertThat(page(shop, "search=rice").path("totalElements").asInt()).isEqualTo(1);
        assertThat(page(shop, "search=" + shop.haal().getBarcode()).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a search matching nothing but a blank id list still answers cleanly")
    void searchWithNoTargetMatchAnswersOnName() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "No item is called xyzzy", true);

        // The item projection returns zero ids, which must not become IN () SQL; the page
        // query still answers on campaign name.
        JsonNode result = page(shop, "search=xyzzy");

        assertThat(result.path("totalElements").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("name").asText()).contains("xyzzy");
    }

    @Test
    @DisplayName("the branch filter is applied in the query")
    void filtersByBranch() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "Branch sale", true);

        JsonNode none = page(shop, "branchId=999999");
        JsonNode mine = page(shop, "branchId=" + shop.branchId());

        assertThat(none.path("totalElements").asInt()).isZero();
        assertThat(none.path("items").size()).isZero();
        assertThat(mine.path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retired campaign stays out of the board")
    void retiredCampaignIsExcluded() throws Exception {
        Shop shop = shopWithItem();
        JsonNode campaign = createCampaign(shop, "Retired before the board loaded", true);

        deleteExpectingStatus("/promotions/" + campaign.path("id").asLong(), shop.tenant(), shop.token(), 204);

        JsonNode result = page(shop, "search=Retired before");
        assertThat(result.path("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("size is clamped: zero up to one, huge down to one hundred")
    void clampsSize() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "Clamp sale", true);

        assertThat(page(shop, "size=0").path("size").asInt()).isEqualTo(1);
        assertThat(page(shop, "size=5000").path("size").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("ordering keeps the board's old contract: active first, then newest start")
    void ordersActiveThenNewest() throws Exception {
        Shop shop = shopWithItem();
        JsonNode paused = createCampaign(shop, "Paused historical sale", false);
        JsonNode live = createCampaign(shop, "Live current sale", true);

        JsonNode items = page(shop, "size=100").path("items");
        long pausedId = paused.path("id").asLong();
        long liveId = live.path("id").asLong();
        int liveIndex = -1;
        int pausedIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            long id = items.get(i).path("id").asLong();
            if (id == liveId) liveIndex = i;
            if (id == pausedId) pausedIndex = i;
        }
        assertThat(liveIndex).isGreaterThanOrEqualTo(0);
        assertThat(pausedIndex).isGreaterThanOrEqualTo(0);
        assertThat(liveIndex).isLessThan(pausedIndex);
    }

    @Test
    @DisplayName("pagination is server-side: two pages never contain the same campaign")
    void pagesDoNotOverlap() throws Exception {
        Shop shop = shopWithItem();
        for (int i = 0; i < 4; i++) {
            createCampaign(shop, "Paged campaign " + i, true);
        }

        JsonNode first = page(shop, "page=0&size=2");
        JsonNode second = page(shop, "page=1&size=2");

        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(second.path("page").asInt()).isEqualTo(1);
        long firstA = first.path("items").get(0).path("id").asLong();
        long firstB = first.path("items").get(1).path("id").asLong();
        for (JsonNode row : second.path("items")) {
            long id = row.path("id").asLong();
            assertThat(id).isNotEqualTo(firstA).isNotEqualTo(firstB);
        }
    }

    @Test
    @DisplayName("the api mount serves the same page as the root mount")
    void apiAliasServesTheSameShape() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "Alias sale", true);

        JsonNode result = getJson("/api/promotions?page=0&size=25", shop.tenant(), shop.token());
        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("size").asInt()).isEqualTo(25);
    }

    @Test
    @DisplayName("page below zero is answered as page zero, not an error")
    void negativePageIsNormalized() throws Exception {
        Shop shop = shopWithItem();
        createCampaign(shop, "Negative page sale", true);

        assertThat(page(shop, "page=-3").path("page").asInt()).isZero();
    }
}
