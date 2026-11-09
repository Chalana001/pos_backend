package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.module.ModuleRouteResolver;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.chala.posapp.integration.SqlCaptureStatementInspector"
})
class PromotionItemSearchEndpointIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private ModuleRouteResolver moduleRouteResolver;

    @Test
    @DisplayName("promotion search returns a narrow envelope and preserves short search fields")
    void typedSearchReturnsNarrowEnvelope() throws Exception {
        Shop shop = shop("promo-search");
        Item item = createItem(shop, "Promo rice", "PROMO-RICE", "Rice alternate", true, true);
        insertBatch(shop.fixture.mainBranch().getId(), item);

        JsonNode response = getJson("/items/promotion-search?name=ri&branchId=" + shop.fixture.mainBranch().getId(),
                shop.fixture.tenantId(), shop.token);

        assertThat(response.path("nextCursor").isNull()).isTrue();
        assertThat(response.path("hasMore").asBoolean()).isFalse();
        assertThat(response.path("items").size()).isEqualTo(1);
        JsonNode row = response.path("items").get(0);
        assertThat(row.path("id").asLong()).isEqualTo(item.getId());
        assertThat(row.path("name").asText()).isEqualTo("Promo rice");
        assertThat(row.path("altName").asText()).isEqualTo("Rice alternate");
        assertThat(row.path("barcode").asText()).isEqualTo("PROMO-RICE");
        assertThat(row.has("sellingPrice")).isTrue();
        assertThat(row.has("costPrice")).isTrue();
        assertThat(row.has("batches")).isFalse();
        assertThat(row.has("ingredients")).isFalse();
        assertThat(row.has("processingOutputs")).isFalse();
        assertThat(row.has("availableQty")).isFalse();
    }

    @Test
    @DisplayName("promotion search rejects unsafe and conflicting requests")
    void validatesSearchModes() throws Exception {
        Shop shop = shop("promo-validation");
        String tenant = shop.fixture.tenantId();
        String token = shop.token;

        getJsonExpectingStatus("/items/promotion-search?branchId=" + shop.fixture.mainBranch().getId(), tenant, token, 400);
        getJsonExpectingStatus("/items/promotion-search?name=rice&pageSize=0", tenant, token, 400);
        getJsonExpectingStatus("/items/promotion-search?name=rice&cursor=10", tenant, token, 400);
        getJsonExpectingStatus("/items/promotion-search?categoryId=1&subCategoryId=2", tenant, token, 400);
        JsonNode capped = getJson("/items/promotion-search?subCategoryId=" + shop.subCategory.getId()
                + "&pageSize=9999", tenant, token);
        assertThat(capped.path("items").isArray()).isTrue();
    }

    @Test
    @DisplayName("category pages advance over excluded candidates and import every eligible item")
    void categoryCursorAdvancesOverExcludedCandidates() throws Exception {
        Shop shop = shop("promo-cursor");
        Item first = createItem(shop, "First eligible", "CURSOR-1", null, true, true);
        Item hidden = createItem(shop, "Hidden candidate", "CURSOR-2", null, true, false);
        Item third = createItem(shop, "Third eligible", "CURSOR-3", true, true);
        insertBatch(shop.fixture.mainBranch().getId(), first);
        insertBatch(shop.fixture.mainBranch().getId(), third);

        String base = "/items/promotion-search?branchId=" + shop.fixture.mainBranch().getId()
                + "&subCategoryId=" + shop.subCategory.getId() + "&pageSize=1";
        JsonNode firstPage = getJson(base, shop.fixture.tenantId(), shop.token);
        assertThat(firstPage.path("items").size()).isEqualTo(1);
        assertThat(firstPage.path("items").get(0).path("id").asLong()).isEqualTo(first.getId());
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();

        long cursor = firstPage.path("nextCursor").asLong();
        JsonNode secondPage = getJson(base + "&cursor=" + cursor, shop.fixture.tenantId(), shop.token);
        assertThat(secondPage.path("items").size()).isZero();
        assertThat(secondPage.path("hasMore").asBoolean()).isTrue();
        assertThat(secondPage.path("nextCursor").asLong()).isEqualTo(hidden.getId());

        long secondCursor = secondPage.path("nextCursor").asLong();
        JsonNode thirdPage = getJson(base + "&cursor=" + secondCursor, shop.fixture.tenantId(), shop.token);
        assertThat(thirdPage.path("items").size()).isEqualTo(1);
        assertThat(thirdPage.path("items").get(0).path("id").asLong()).isEqualTo(third.getId());
        assertThat(thirdPage.path("hasMore").asBoolean()).isFalse();
        assertThat(thirdPage.path("nextCursor").asLong()).isEqualTo(third.getId());
    }

    @Test
    @DisplayName("category cursor imports complete datasets from empty through more than two thousand items")
    void categoryCursorImportsCompleteDatasetSizes() throws Exception {
        int[] sizes = {0, 100, 500, 501, 1000, 1250, 2101};
        for (int size : sizes) {
            Shop shop = shop("promo-size-" + size);
            List<Item> items = new ArrayList<>();
            for (int index = 0; index < size; index++) {
                items.add(Item.builder()
                        .barcode(uniqueKey("SIZE-" + size))
                        .name("Size " + size + " item " + index)
                        .subCategory(shop.subCategory)
                        .costPrice(new BigDecimal("20.00"))
                        .sellingPrice(new BigDecimal("30.00"))
                        .reorderLevel(0)
                        .active(true)
                        .posVisible(true)
                        .build());
            }
            itemRepository.saveAll(items);
            itemRepository.flush();
            for (Item item : items) {
                insertBatch(shop.fixture.mainBranch().getId(), item);
            }

            Set<Long> returned = new HashSet<>();
            Long cursor = null;
            boolean hasMore;
            int requestCount = 0;
            do {
                String url = "/items/promotion-search?subCategoryId=" + shop.subCategory.getId()
                        + "&branchId=" + shop.fixture.mainBranch().getId() + "&pageSize=500";
                if (cursor != null) url += "&cursor=" + cursor;
                JsonNode page = getJson(url, shop.fixture.tenantId(), shop.token);
                requestCount++;
                for (JsonNode row : page.path("items")) {
                    assertThat(returned.add(row.path("id").asLong())).isTrue();
                }
                hasMore = page.path("hasMore").asBoolean();
                Long next = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asLong();
                if (hasMore) {
                    assertThat(next).isNotNull();
                    assertThat(cursor == null || next > cursor).isTrue();
                }
                cursor = next;
            } while (hasMore);

            assertThat(returned).hasSize(size);
            assertThat(requestCount).isEqualTo(size == 0 ? 1 : (size + 499) / 500);
        }
    }

    @Test
    @DisplayName("concrete branch category search uses one bulk stock aggregate")
    void concreteBranchUsesBulkStockRead() throws Exception {
        Shop shop = shop("promo-bulk-stock");
        Item item = createItem(shop, "Bulk stock item", "BULK-STOCK", null, true, true);
        insertBatch(shop.fixture.mainBranch().getId(), item);
        SqlCaptureStatementInspector.clear();

        JsonNode response = getJson("/items/promotion-search?subCategoryId=" + shop.subCategory.getId()
                + "&branchId=" + shop.fixture.mainBranch().getId(), shop.fixture.tenantId(), shop.token);

        assertThat(response.path("items").size()).isEqualTo(1);
        long stockStatements = SqlCaptureStatementInspector.captured().stream()
                .map(String::toLowerCase)
                .filter(sql -> sql.contains("stock_batches"))
                .count();
        assertThat(stockStatements).isEqualTo(1);
    }

    @Test
    @DisplayName("null branch category search skips stock and assignment reads")
    void nullBranchSkipsStockReads() throws Exception {
        Shop shop = shop("promo-null-branch");
        createItem(shop, "Unstocked promotion item", "NULL-BRANCH", null, true, true);
        SqlCaptureStatementInspector.clear();

        JsonNode response = getJson("/items/promotion-search?subCategoryId=" + shop.subCategory.getId(),
                shop.fixture.tenantId(), shop.token);

        assertThat(response.path("items").size()).isEqualTo(1);
        assertThat(SqlCaptureStatementInspector.captured().stream()
                .map(String::toLowerCase)
                .noneMatch(sql -> sql.contains("stock_batches") || sql.contains("branch_service_items")))
                .isTrue();
    }

    @Test
    @DisplayName("promotion search route belongs to promotions module")
    void routeBelongsToPromotionsModule() {
        assertThat(moduleRouteResolver.resolve("/items/promotion-search", "GET"))
                .contains("PROMOTIONS");
    }

    private Shop shop(String prefix) {
        TenantFixture fixture = seedTenantShop(uniqueKey(prefix), 2);
        String token;
        try {
            token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        Category category = new Category();
        category.setName(uniqueKey("Promotion category"));
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName(uniqueKey("Promotion subcategory"));
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);
        return new Shop(fixture, token, subCategory);
    }

    private Item createItem(Shop shop, String name, String barcode, String altName, boolean active, boolean posVisible)
            throws Exception {
        String altNameJson = altName == null ? "null" : "\"" + altName + "\"";
        JsonNode created = postJson("/items", shop.fixture.tenantId(), shop.token, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "altName": %s,
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0,
                  "active": %s,
                  "posVisible": %s
                }
                """.formatted(barcode, name, altNameJson, shop.subCategory.getId(), active, posVisible));
        return itemRepository.findById(created.path("id").asLong()).orElseThrow();
    }

    private Item createItem(Shop shop, String name, String barcode, boolean active, boolean posVisible) throws Exception {
        return createItem(shop, name, barcode, null, active, posVisible);
    }

    private void insertBatch(Long branchId, Item item) {
        stockBatchRepository.save(StockBatch.builder()
                .branch(branchRepository.findById(branchId).orElseThrow())
                .item(item)
                .quantity(10000)
                .originalQuantity(10000)
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .sourceType(StockBatchSourceType.PURCHASE)
                .batchCode(uniqueKey("PROMO-BATCH"))
                .build());
    }

    private record Shop(TenantFixture fixture, String token, SubCategory subCategory) {
    }
}
