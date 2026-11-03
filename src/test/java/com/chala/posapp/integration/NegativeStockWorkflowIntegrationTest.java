package com.chala.posapp.integration;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.OrderItemStockUsage;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.StockOverrideAudit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.entity.stock.PurchaseStockOffset;
import com.chala.posapp.repository.OrderItemStockUsageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NegativeStockWorkflowIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private OrderItemStockUsageRepository orderItemStockUsageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("mixed positive and negative batches are net stock but still trigger the purchase dialog")
    void mixedLedgerExposesNetQuantityAndNegativeFlag() throws Exception {
        Shop shop = shop("mixed");
        insertBatch(shop.branch(), shop.item(), -5, "30.00");
        insertBatch(shop.branch(), shop.item(), 5, "30.00");

        JsonNode purchaseSearch = getJson(
                "/items/searchForPurchase?name=" + shop.item().getName().substring(0, 2) + "&branchId="
                        + shop.branch().getId(),
                shop.tenantId(),
                shop.token()
        ).get(0);

        assertThat(purchaseSearch.path("availableQty").asDouble()).isZero();
        assertThat(purchaseSearch.path("hasNegativeStock").asBoolean()).isTrue();

        JsonNode posSearch = getJson(
                "/items/searchForPos?name=" + shop.item().getName().substring(0, 2) + "&branchId="
                        + shop.branch().getId(),
                shop.tenantId(),
                shop.token()
        ).get(0);
        assertThat(posSearch.path("availableQty").asDouble()).isZero();
        assertThat(posSearch.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(posSearch.path("batches")).hasSize(1);
    }

    @Test
    @DisplayName("item search exposes the net quantity and negative flag only for the requested branch")
    void itemSearchExposesNetQuantityAndNegativeFlagPerBranch() throws Exception {
        Shop shop = shop("search-flag");
        Branch otherBranch = createBranch("SEARCH-" + uniqueKey("B"));
        insertBatch(shop.branch(), shop.item(), -5, "30.00");
        insertBatch(shop.branch(), shop.item(), 5, "30.00");
        insertBatch(otherBranch, shop.item(), 4, "30.00");

        JsonNode scoped = itemSearch(shop, shop.branch().getId());
        assertThat(scoped.path("availableQty").asDouble()).isZero();
        assertThat(scoped.path("hasNegativeStock").asBoolean()).isTrue();
        // The other branch's +4 batch must not leak into a branch-scoped search.
        assertThat(scoped.path("batches")).hasSize(1);

        // And the main branch's negatives must not leak into the other branch's.
        JsonNode otherScoped = itemSearch(shop, otherBranch.getId());
        assertThat(otherScoped.path("availableQty").asDouble()).isEqualTo(4.0);
        assertThat(otherScoped.path("hasNegativeStock").asBoolean()).isFalse();
    }

    private JsonNode itemSearch(Shop shop, long branchId) throws Exception {
        JsonNode results = getJson(
                "/items/search?name=" + shop.item().getName().substring(0, 2) + "&branchId=" + branchId,
                shop.tenantId(),
                shop.token()
        );
        for (JsonNode candidate : results) {
            if (candidate.path("id").asLong() == shop.item().getId()) {
                return candidate;
            }
        }
        throw new AssertionError("item missing from /items/search results");
    }

    @Test
    @DisplayName("authorized shortage creates one negative override batch and later shortage reuses it")
    void shortagesCreateAndReuseCanonicalNegativeBatch() throws Exception {
        Shop shop = shop("override-reuse");

        JsonNode first = sellWithoutStock(shop, 2);
        StockBatch override = onlyNegativeBatch(shop);
        assertThat(override.getQuantity()).isEqualTo(-2000);
        assertThat(override.getSourceType()).isEqualTo(StockBatchSourceType.OVERRIDE);
        assertThat(override.getSellingPrice()).isEqualByComparingTo("30.00");
        assertThat(override.getCostPrice()).isEqualByComparingTo("20.00");
        assertThat(override.getOriginalQuantity()).isZero();
        assertThat(override.getBatchCode()).startsWith(
                "OVERRIDE-" + shop.branch().getId() + "-" + shop.item().getId() + "-");

        long overrideId = override.getId();
        long firstOrderItemId = first.path("items").get(0).path("id").asLong();
        assertThat(orderItemStockUsages(firstOrderItemId))
                .containsExactly(overrideId + ":" + 2000);
        assertThat(overrideAudit(firstOrderItemId).path("batchId").asLong()).isEqualTo(overrideId);

        sellWithoutStock(shop, 3);
        assertThat(negativeBatches(shop)).containsExactly(overrideId);
        assertThat(stockBatchRepository.findById(overrideId).orElseThrow().getQuantity()).isEqualTo(-5000);
        assertThat(stockBatchRepository.findByBranchIdAndItemId(shop.branch().getId(), shop.item().getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("checkout reuses a legacy negative row without rewriting its source or prices")
    void checkoutReusesLegacyNegativeWithoutMetadataRewrite() throws Exception {
        Shop shop = shop("legacy-negative");
        StockBatch legacy = stockBatchRepository.save(StockBatch.builder()
                .branch(shop.branch())
                .item(shop.item())
                .quantity(-2000)
                .originalQuantity(9000)
                .costPrice(new BigDecimal("11.25"))
                .sellingPrice(new BigDecimal("42.75"))
                .sourceType(StockBatchSourceType.PURCHASE)
                .batchCode(uniqueKey("LEGACY-NEG"))
                .receivedAt(LocalDateTime.now().minusDays(3))
                .build());
        sellWithoutStock(shop, 2);

        StockBatch reused = stockBatchRepository.findById(legacy.getId()).orElseThrow();
        assertThat(reused.getQuantity()).isEqualTo(-4000);
        assertThat(reused.getSourceType()).isEqualTo(StockBatchSourceType.PURCHASE);
        assertThat(reused.getCostPrice()).isEqualByComparingTo("11.25");
        assertThat(reused.getSellingPrice()).isEqualByComparingTo("42.75");
        assertThat(reused.getOriginalQuantity()).isEqualTo(9000);
        assertThat(reused.getBatchCode()).isEqualTo(legacy.getBatchCode());
        assertThat(negativeBatches(shop)).containsExactly(legacy.getId());
    }

    @Test
    @DisplayName("legacy multiple negatives choose the oldest without mutating the others at checkout")
    void checkoutChoosesOldestNegativeAndLeavesLegacyRowsUntouched() throws Exception {
        Shop shop = shop("legacy-multiple");
        StockBatch newest = stockBatchRepository.save(StockBatch.builder()
                .branch(shop.branch())
                .item(shop.item())
                .quantity(-1000)
                .originalQuantity(1000)
                .costPrice(new BigDecimal("20"))
                .sellingPrice(new BigDecimal("30"))
                .sourceType(StockBatchSourceType.PURCHASE)
                .batchCode(uniqueKey("NEG-NEW"))
                .receivedAt(LocalDateTime.now().plusHours(1))
                .build());
        StockBatch oldest = stockBatchRepository.save(StockBatch.builder()
                .branch(shop.branch())
                .item(shop.item())
                .quantity(-2000)
                .originalQuantity(2000)
                .costPrice(new BigDecimal("21"))
                .sellingPrice(new BigDecimal("35"))
                .sourceType(StockBatchSourceType.OVERRIDE)
                .batchCode(uniqueKey("NEG-OLD"))
                .receivedAt(LocalDateTime.now().minusDays(10))
                .build());
        // @CreationTimestamp overrides a supplied value on insert, so the
        // historical ordering this test depends on must be written afterwards.
        jdbcTemplate.update("UPDATE stock_batches SET received_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(10), oldest.getId());
        jdbcTemplate.update("UPDATE stock_batches SET received_at = ? WHERE id = ?",
                LocalDateTime.now(), newest.getId());

        postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "stockOverrideReason": "Legacy multiple negatives",
                  "items": [{
                    "itemId": %d,
                    "qty": 1,
                    "qtyUnit": "PCS",
                    "unitPrice": 30,
                    "discountType": "NONE",
                    "discountValue": 0
                  }],
                  "billDiscount": 0,
                  "paidAmount": 30,
                  "paymentMethod": "CASH"
                }
                """.formatted(shop.branch().getId(), shop.item().getId()));

        assertThat(stockBatchRepository.findById(oldest.getId()).orElseThrow().getQuantity()).isEqualTo(-3000);
        assertThat(stockBatchRepository.findById(newest.getId()).orElseThrow().getQuantity()).isEqualTo(-1000);
        assertThat(negativeBatches(shop)).containsExactly(oldest.getId(), newest.getId());
    }

    @Test
    @DisplayName("manual batch selection consumes only the selected batch and records shortage separately")
    void manualSelectionSplitsPositiveUsageAndShortage() throws Exception {
        Shop shop = shop("manual-partial");
        long selected = insertBatch(shop.branch(), shop.item(), 2, "30.00");
        long unselected = insertBatch(shop.branch(), shop.item(), 7, "30.00");

        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "stockOverrideReason": "Manual partial batch",
                  "items": [{
                    "itemId": %d,
                    "batchId": %d,
                    "qty": 5,
                    "qtyUnit": "PCS",
                    "unitPrice": 37.50,
                    "discountType": "NONE",
                    "discountValue": 0
                  }],
                  "billDiscount": 0,
                  "paidAmount": 188,
                  "paymentMethod": "CASH"
                }
                """.formatted(shop.branch().getId(), shop.item().getId(), selected));

        long shortageBatch = onlyNegativeBatch(shop).getId();
        assertThat(shortageBatch).isNotEqualTo(selected);
        assertThat(shortageBatch).isNotEqualTo(unselected);
        assertThat(stockBatchRepository.findById(selected).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findById(unselected).orElseThrow().getQuantity()).isEqualTo(7000);
        assertThat(stockBatchRepository.findById(shortageBatch).orElseThrow().getQuantity()).isEqualTo(-3000);

        long orderItemId = sale.path("items").get(0).path("id").asLong();
        assertThat(orderItemStockUsages(orderItemId))
                .containsExactlyInAnyOrder(selected + ":" + 2000, shortageBatch + ":" + 3000);
        assertThat(sale.path("items").get(0).path("unitPrice").asDouble()).isEqualTo(37.5);
        assertThat(sale.path("items").get(0).path("batchId").asLong()).isEqualTo(selected);
    }

    @Test
    @DisplayName("automatic allocation records positive and shortage usages against their own batch IDs")
    void automaticAllocationRecordsPositiveAndShortageUsages() throws Exception {
        Shop shop = shop("automatic-partial");
        long first = insertBatch(shop.branch(), shop.item(), 2, "30.00");
        long second = insertBatch(shop.branch(), shop.item(), 1, "35.00");

        JsonNode sale = sellWithoutStock(shop, 5, "35.00", 37.50);
        long shortageBatch = onlyNegativeBatch(shop).getId();

        assertThat(stockBatchRepository.findById(first).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findById(second).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findById(shortageBatch).orElseThrow().getQuantity()).isEqualTo(-2000);
        assertThat(orderItemStockUsages(sale.path("items").get(0).path("id").asLong()))
                .containsExactlyInAnyOrder(
                        first + ":" + 2000,
                        second + ":" + 1000,
                        shortageBatch + ":" + 2000
                );
    }

    @Test
    @DisplayName("blocked override creates no batch, usage, audit, or order")
    void blockedOverridePersistsNothing() throws Exception {
        Shop shop = shop("blocked");
        putJson("/app-configuration", shop.tenantId(), shop.token(), """
                {
                  "recipeItemsEnabled": true,
                  "weightItemsEnabled": true,
                  "servicesEnabled": true,
                  "tableManagementEnabled": true,
                  "dineInEnabled": true,
                  "categoryMode": "MAIN_AND_SUB",
                  "stockOverrideMode": "BLOCK"
                }
                """);
        long ordersBefore = orderRepository.count();

        JsonNode blocked = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                shop.tenantId(),
                shop.token(),
                """
                        {
                          "branchId": %d,
                          "orderType": "CASH",
                          "allowStockOverride": true,
                          "items": [{
                            "itemId": %d,
                            "qty": 1,
                            "qtyUnit": "PCS",
                            "unitPrice": 30,
                            "discountType": "NONE",
                            "discountValue": 0
                          }],
                          "billDiscount": 0,
                          "paidAmount": 30,
                          "paymentMethod": "CASH"
                        }
                        """.formatted(shop.branch().getId(), shop.item().getId()),
                409
        );

        assertThat(blocked.path("code").asText()).isEqualTo("STOCK_OVERRIDE_REQUIRED");
        assertThat(stockBatchRepository.findByBranchIdAndItemId(
                shop.branch().getId(), shop.item().getId())).isEmpty();
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(orderItemStockUsageRepository.findAll()).isEmpty();
        assertThat(stockOverrideAuditRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("different items and branches keep independent negative override batches")
    void negativeBatchesAreScopedByBranchAndItem() throws Exception {
        Shop first = shop("scope-first", 3);
        Shop second = shop("scope-second");
        Branch otherBranch = createBranch("SCOPE-" + uniqueKey("B"));
        Item otherItem = first.item();

        sellWithoutStock(first, 2);
        sellWithoutStock(second, 3);
        long firstBranchBatch = onlyNegativeBatch(first).getId();
        long secondBranchBatch = onlyNegativeBatch(second).getId();

        JsonNode otherBranchSale = postJson("/orders", first.tenantId(), first.token(), """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "items": [{
                    "itemId": %d,
                    "qty": 1,
                    "qtyUnit": "PCS",
                    "unitPrice": 30,
                    "discountType": "NONE",
                    "discountValue": 0
                  }],
                  "billDiscount": 0,
                  "paidAmount": 30,
                  "paymentMethod": "CASH"
                }
                """.formatted(otherBranch.getId(), otherItem.getId()));
        assertThat(otherBranchSale.path("grandTotal").asDouble()).isEqualTo(30.0);

        assertThat(negativeBatches(first)).containsExactly(firstBranchBatch);
        assertThat(negativeBatches(second)).containsExactly(secondBranchBatch);
        assertThat(stockBatchRepository.findNegativeBatchesForBranchItem(
                otherBranch.getId(), otherItem.getId())).hasSize(1);
        assertThat(stockBatchRepository.findNegativeBatchesForBranchItem(
                otherBranch.getId(), otherItem.getId()).get(0).getId())
                .isNotEqualTo(firstBranchBatch)
                .isNotEqualTo(secondBranchBatch);
    }

    @Test
    @DisplayName("concurrent first-time override sales create one negative batch only")
    void concurrentFirstOverridesCreateOneNegativeBatch() throws Exception {
        Shop shop = shop("concurrent");
        String managerToken = login(
                shop.tenantId(), shop.fixture().manager().getUsername(), DEFAULT_PASSWORD);
        postJson("/shifts/open", shop.tenantId(), managerToken, """
                {"openingCash": 0, "note": "Concurrent override test"}
                """);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        String firstClientId = uniqueKey("CONCURRENT");
        String secondClientId = uniqueKey("CONCURRENT");

        try {
            Future<JsonNode> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return postJson("/orders/offline-import", shop.tenantId(), managerToken,
                        offlineSellBody(shop, 2, 30, firstClientId));
            });
            Future<JsonNode> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return postJson("/orders/offline-import", shop.tenantId(), managerToken,
                        offlineSellBody(shop, 3, 35, secondClientId));
            });
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS).path("success").asBoolean()).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS).path("success").asBoolean()).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(negativeBatches(shop)).hasSize(1);
        assertThat(stockBatchRepository.findById(negativeBatches(shop).get(0)).orElseThrow().getQuantity())
                .isEqualTo(-5000);
    }

    @Test
    @DisplayName("a concurrent purchase offset and checkout both apply without losing stock updates")
    void concurrentPurchaseOffsetAndCheckoutKeepLedgerConsistent() throws Exception {
        Shop shop = shop("purchase-checkout-race");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        Branch otherBranch = createBranch("RACE-" + uniqueKey("B"));
        long untouched = insertBatch(otherBranch, shop.item(), 3, "30.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        JsonNode purchaseResult;
        JsonNode checkoutResult;

        try {
            // The purchase offsets the negative batch (-5 + 2) while the
            // checkout sells one more unit through an override (-1). Which one
            // wins the ledger lock is not deterministic and must not be
            // asserted: the only valid outcome is that both updates survive.
            Future<JsonNode> purchase = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createPurchase(shop, 2, 30, false);
            });
            Future<JsonNode> checkout = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return sellWithoutStock(shop, 1);
            });
            start.countDown();

            purchaseResult = purchase.get(20, TimeUnit.SECONDS);
            checkoutResult = checkout.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(purchaseResult.path("purchaseId").asLong()).isPositive();
        assertThat(checkoutResult.path("grandTotal").asDouble()).isEqualTo(30.0);

        // -5 + 2 - 1 = -4, in either ordering. A lost update would leave -3
        // (the checkout's -1 overwritten) or -6 (the purchase's +2 overwritten).
        assertThat(total(shop)).isEqualTo(-4000);

        long purchaseId = purchaseResult.path("purchaseId").asLong();
        List<PurchaseStockOffset> offsets = purchaseStockOffsetRepository
                .findByPurchaseIdAndReversedAtIsNullOrderByIdAsc(purchaseId);
        assertThat(offsets).hasSize(1);
        assertThat(offsets.get(0).getNegativeBatchId()).isEqualTo(negative);
        assertThat(offsets.get(0).getQuantityOffset()).isEqualTo(2000);
        // The offset applied to a batch holding -5 or -6 depending on who
        // locked first, leaving it at -3 or -4.
        assertThat(offsets.get(0).getBatchQuantityAfterOffset()).isIn(-3000, -4000);

        long orderItemId = checkoutResult.path("items").get(0).path("id").asLong();
        assertThat(orderItemStockUsages(orderItemId)).containsExactly(negative + ":" + 1000);
        assertThat(overrideAudit(orderItemId).path("batchId").asLong()).isEqualTo(negative);

        // The override reused the existing negative row instead of creating a
        // duplicate, and the other branch's stock is untouched.
        assertThat(negativeBatches(shop)).containsExactly(negative);
        assertThat(stockBatchRepository.findByBranchIdAndItemId(
                shop.branch().getId(), shop.item().getId())).hasSize(1);
        assertThat(stockBatchRepository.findById(untouched).orElseThrow().getQuantity()).isEqualTo(3000);
    }

    @Test
    @DisplayName("purchase No offsets only the received quantity and keeps the full GRN quantity")
    void purchaseNoOffsetsReceiptAndKeepsGrnQuantity() throws Exception {
        Shop shop = shop("purchase-no");
        long negative30 = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        long positive35 = insertBatch(shop.branch(), shop.item(), 9, "35.00");

        JsonNode purchase = createPurchase(shop, 6, 30, false);

        assertThat(stockBatchRepository.findById(negative30).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findById(positive35).orElseThrow().getQuantity()).isEqualTo(9000);
        assertThat(purchase.path("grnList").get(0).path("items").get(0).path("qty").asDouble())
                .isEqualTo(6);
        assertThat(total(shop)).isEqualTo(10000);

        StockBatch purchased = stockBatchRepository
                .findByBranchIdAndBatchCodeStartingWith(shop.branch().getId(),
                        "GRN-" + purchase.path("grnList").get(0).path("grnNo").asText())
                .stream().findFirst().orElseThrow();
        assertThat(purchased.getQuantity()).isEqualTo(1000);
    }

    @Test
    @DisplayName("partial purchase consolidation leaves one negative row without changing the ledger total")
    void partialPurchaseConsolidatesNegativeRows() throws Exception {
        Shop shop = shop("purchase-partial");
        long olderNegative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        long newerNegative = insertBatch(shop.branch(), shop.item(), -3, "35.00");

        JsonNode purchase = createPurchase(shop, 2, 30, false);

        assertThat(stockBatchRepository.findById(olderNegative).orElseThrow().getQuantity()).isEqualTo(-6000);
        assertThat(stockBatchRepository.findById(newerNegative).orElseThrow().getQuantity()).isZero();
        assertThat(purchase.path("grnList").get(0).path("items").get(0).path("qty").asDouble())
                .isEqualTo(2);
        assertThat(total(shop)).isEqualTo(-6000);
        assertThat(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + purchase.path("grnList").get(0).path("grnNo").asText()))
                .isEmpty();
    }

    @Test
    @DisplayName("stock profile/table and stock processing expose the signed net quantity")
    void stockSurfacesExposeSignedNetQuantity() throws Exception {
        Shop shop = shop("displays");
        shop.item().setStockProcessingEnabled(true);
        itemRepository.save(shop.item());
        insertBatch(shop.branch(), shop.item(), -5, "30.00");
        insertBatch(shop.branch(), shop.item(), 2, "30.00");

        JsonNode stockDetails = getJson(
                "/stock/branch/" + shop.branch().getId() + "/item/" + shop.item().getId(),
                shop.tenantId(),
                shop.token()
        );
        assertThat(stockDetails.path("totalQuantity").asInt()).isEqualTo(-3000);
        assertThat(stockDetails.path("displayQuantity").asDouble()).isEqualTo(-3.0);
        assertThat(stockDetails.path("activeBatches")).hasSize(2);

        JsonNode processingSource = getJson(
                "/stock-processing/sources?branchId=" + shop.branch().getId(),
                shop.tenantId(),
                shop.token()
        ).get(0);
        assertThat(processingSource.path("availableBaseQty").asInt()).isEqualTo(-3000);
        assertThat(processingSource.path("availableQty").asDouble()).isEqualTo(-3.0);
        assertThat(processingSource.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(processingSource.path("batches")).hasSize(2);
    }

    @Test
    @DisplayName("purchase Yes clears every negative balance, audits it, and adds the full receipt")
    void purchaseYesClearsNegativesWithStockAdjustmentHistory() throws Exception {
        Shop shop = shop("purchase-yes");
        long negative30 = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        long negative35 = insertBatch(shop.branch(), shop.item(), -2, "35.00");

        JsonNode purchase = createPurchase(shop, 4, 40, true);

        assertThat(stockBatchRepository.findById(negative30).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findById(negative35).orElseThrow().getQuantity()).isZero();
        assertThat(stockAdjustmentRepository.findByBranchIdAndItemIdOrderByCreatedAtDesc(
                shop.branch().getId(), shop.item().getId())).hasSize(2)
                .allSatisfy(adjustment -> {
                    assertThat(adjustment.getPurchaseId()).isEqualTo(purchase.path("purchaseId").asLong());
                    assertThat(adjustment.getStockBatchId()).isNotNull();
                    assertThat(adjustment.getReversedByAdjustmentId()).isNull();
                });
        assertThat(purchase.path("grnList").get(0).path("items").get(0).path("qty").asDouble())
                .isEqualTo(4);
        assertThat(total(shop)).isEqualTo(4000);
    }

    @Test
    @DisplayName("cancelling a Yes purchase reverses its linked adjustments and restores the negative balances")
    void cancelYesPurchaseRestoresNegativeBalancesWithAuditedReversals() throws Exception {
        Shop shop = shop("cancel-yes");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        JsonNode purchase = createPurchase(shop, 10, 30, true);
        long purchaseId = purchase.path("purchaseId").asLong();
        String grnNo = purchase.path("grnList").get(0).path("grnNo").asText();

        List<com.chala.posapp.entity.stock.StockAdjustment> originals =
                stockAdjustmentRepository.findByPurchaseIdAndReversedByAdjustmentIdIsNullOrderByIdAsc(purchaseId);
        assertThat(originals).hasSize(1);
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isZero();
        assertThat(total(shop)).isEqualTo(10_000);

        JsonNode canceled = cancelPurchase(shop, purchaseId, "wrong quantity");

        assertThat(canceled.path("status").asText()).isEqualTo("CANCELED");
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-5_000);
        assertThat(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + grnNo)).isEmpty();
        assertThat(total(shop)).isEqualTo(-5_000);

        List<com.chala.posapp.entity.stock.StockAdjustment> adjustments =
                stockAdjustmentRepository.findByPurchaseIdOrderByIdAsc(purchaseId);
        assertThat(adjustments).hasSize(2);
        com.chala.posapp.entity.stock.StockAdjustment original = adjustments.get(0);
        com.chala.posapp.entity.stock.StockAdjustment reversal = adjustments.get(1);
        assertThat(original.getQtyChange()).isEqualTo(5_000);
        assertThat(original.getReversedByAdjustmentId()).isEqualTo(reversal.getId());
        assertThat(reversal.getReversalOfAdjustmentId()).isEqualTo(original.getId());
        assertThat(reversal.getQtyChange()).isEqualTo(-5_000);
        assertThat(reversal.getStockBatchId()).isEqualTo(negative);

        jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/purchases/" + purchaseId + "/cancel",
                shop.tenantId(),
                shop.token(),
                """
                {"reason": "second cancellation"}
                """,
                409
        );
        assertThat(stockAdjustmentRepository.findByPurchaseIdOrderByIdAsc(purchaseId)).hasSize(2);
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-5_000);
    }

    @Test
    @DisplayName("purchase No leaves the allocated units unavailable to a physical supplier return")
    void purchaseNoAllocatedUnitsAreNotPhysicallyReturnable() throws Exception {
        Shop shop = shop("return-no");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        JsonNode purchase = createPurchase(shop, 10, 30, false);
        long purchaseId = purchase.path("purchaseId").asLong();
        long grnId = purchase.path("grnList").get(0).path("id").asLong();
        long grnItemId = purchase.path("grnList").get(0).path("items").get(0).path("id").asLong();

        StockBatch purchasedBatch = stockBatchRepository
                .findByBranchIdAndBatchCodeStartingWith(shop.branch().getId(),
                        "GRN-" + purchase.path("grnList").get(0).path("grnNo").asText())
                .stream().findFirst().orElseThrow();
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isZero();
        assertThat(grnItemId).isPositive();
        assertThat(purchasedBatch.getQuantity()).isEqualTo(5_000);
        assertThat(purchase.path("grnList").get(0).path("items").get(0).path("qty").asDouble())
                .isEqualTo(10);
        assertThat(total(shop)).isEqualTo(5_000);

        JsonNode returned = postJson("/purchases/" + purchaseId + "/returns", shop.tenantId(), shop.token(), """
                {
                  "grnId": %d,
                  "reason": "three units physically returned",
                  "items": [{"grnItemId": %d, "returnQty": 3}]
                }
                """.formatted(grnId, grnItemId));

        assertThat(returned.path("totalReturnAmount").asDouble()).isEqualTo(60.0);
        assertThat(stockBatchRepository.findById(purchasedBatch.getId()).orElseThrow().getQuantity())
                .isEqualTo(2_000);
        assertThat(total(shop)).isEqualTo(2_000);

        jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/purchases/" + purchaseId + "/returns",
                shop.tenantId(),
                shop.token(),
                """
                {
                  "grnId": %d,
                  "reason": "allocated units are not physical stock",
                  "items": [{"grnItemId": %d, "returnQty": 3}]
                }
                """.formatted(grnId, grnItemId),
                400
        );
        assertThat(total(shop)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("a rejected purchase leaves no stock movement or partial purchase row")
    void rejectedPurchaseRollsBackReceiptAndNegativeAdjustment() throws Exception {
        Shop shop = shop("purchase-rollback");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        long purchasesBefore = purchaseRepository.count();

        jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/purchases",
                shop.tenantId(),
                shop.token(),
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "%s",
                  "paidAmount": 999999,
                  "branches": [{
                    "branchId": %d,
                    "items": [{
                      "itemId": %d,
                      "qty": 10,
                      "costPrice": 20,
                      "sellingPrice": 30,
                      "zeroNegativeStock": true
                    }]
                  }]
                }
                """.formatted(shop.supplier().getId(), uniqueKey("ROLLBACK"),
                shop.branch().getId(), shop.item().getId()),
                400
        );

        assertThat(purchaseRepository.count()).isEqualTo(purchasesBefore);
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-5_000);
        assertThat(stockAdjustmentRepository.findByBranchIdAndItemIdOrderByCreatedAtDesc(
                shop.branch().getId(), shop.item().getId())).isEmpty();
        assertThat(total(shop)).isEqualTo(-5_000);
    }

    @Test
    @DisplayName("two No purchases against one negative ledger apply sequentially without lost updates")
    void concurrentPurchasesApplyAgainstCommittedNegativeState() throws Exception {
        Shop shop = shop("purchase-race");
        long negative = insertBatch(shop.branch(), shop.item(), -8, "30.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        JsonNode firstPurchase;
        JsonNode secondPurchase;
        try {
            Future<JsonNode> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createPurchase(shop, 5, 30, false);
            });
            Future<JsonNode> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createPurchase(shop, 10, 30, false);
            });
            start.countDown();
            firstPurchase = first.get(20, TimeUnit.SECONDS);
            secondPurchase = second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(firstPurchase.path("purchaseId").asLong()).isPositive();
        assertThat(secondPurchase.path("purchaseId").asLong()).isPositive();
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isZero();
        assertThat(total(shop)).isEqualTo(7_000);
        assertThat(purchaseStockOffsetRepository.findAll()).isNotEmpty();
    }

    @Test
    @DisplayName("opening -8 with No purchases of 5 then 10 ends at 7")
    void multipleNoPurchasesEndAtSeven() throws Exception {
        Shop shop = shop("multiple-no");
        long negative = insertBatch(shop.branch(), shop.item(), -8, "30.00");

        createPurchase(shop, 5, 30, false);
        JsonNode second = createPurchase(shop, 10, 30, false);

        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isZero();
        assertThat(total(shop)).isEqualTo(7_000);
        assertThat(second.path("grnList").get(0).path("items").get(0).path("qty").asDouble())
                .isEqualTo(10);
    }

    @Test
    @DisplayName("No purchase followed by a sale cannot be canceled and keeps stock at 3")
    void noPartialSaleBlocksCancellation() throws Exception {
        Shop shop = shop("no-partial-sale");
        insertBatch(shop.branch(), shop.item(), -5, "30.00");
        JsonNode purchase = createPurchase(shop, 10, 30, false);
        long purchaseId = purchase.path("purchaseId").asLong();
        long batchId = stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + purchase.path("grnList").get(0).path("grnNo").asText())
                .get(0).getId();

        postJson("/orders", shop.tenantId(), shop.token(), saleBody(shop, batchId, 2));

        assertThat(total(shop)).isEqualTo(3_000);
        JsonNode blocked = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/purchases/" + purchaseId + "/cancel",
                shop.tenantId(),
                shop.token(),
                """
                {"reason": "sold stock cannot be reversed"}
                """,
                400
        );
        assertThat(blocked.path("message").asText()).contains("sold or adjusted");
        assertThat(total(shop)).isEqualTo(3_000);
    }

    @Test
    @DisplayName("Yes purchase followed by a sale cannot be canceled and keeps stock at 8")
    void yesPartialSaleBlocksCancellation() throws Exception {
        Shop shop = shop("yes-partial-sale");
        insertBatch(shop.branch(), shop.item(), -5, "30.00");
        JsonNode purchase = createPurchase(shop, 10, 30, true);
        long purchaseId = purchase.path("purchaseId").asLong();
        long batchId = stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + purchase.path("grnList").get(0).path("grnNo").asText())
                .get(0).getId();

        postJson("/orders", shop.tenantId(), shop.token(), saleBody(shop, batchId, 2));

        assertThat(total(shop)).isEqualTo(8_000);
        JsonNode blocked = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/purchases/" + purchaseId + "/cancel",
                shop.tenantId(),
                shop.token(),
                """
                {"reason": "sold stock cannot be reversed"}
                """,
                400
        );
        assertThat(blocked.path("message").asText()).contains("sold or adjusted");
        assertThat(total(shop)).isEqualTo(8_000);
        assertThat(stockAdjustmentRepository.findByPurchaseIdOrderByIdAsc(purchaseId)).hasSize(1);
    }

    @Test
    @DisplayName("a manually selected positive batch sells normally even when branch net stock is lower")
    void manualSelectionConsumesSelectedPositiveBatchDespiteNetLedger() throws Exception {
        Shop shop = shop("checkout-manual");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        long positive = insertBatch(shop.branch(), shop.item(), 5, "30.00");

        JsonNode sale = postJson(
                "/orders",
                shop.tenantId(),
                shop.token(),
                saleBody(shop, positive, 1)
        );

        assertThat(sale.path("grandTotal").asDouble()).isEqualTo(30.0);
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-5000);
        assertThat(stockBatchRepository.findById(positive).orElseThrow().getQuantity()).isEqualTo(4000);
    }

    @Test
    @DisplayName("zero and negative explicit batch IDs are rejected without changing stock")
    void zeroAndNegativeExplicitBatchesAreRejected() throws Exception {
        Shop shop = shop("invalid-batch");
        long zeroBatch = insertBatch(shop.branch(), shop.item(), 0, "30.00");
        long negativeBatch = insertBatch(shop.branch(), shop.item(), -2, "30.00");

        JsonNode zeroResponse = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                shop.tenantId(),
                shop.token(),
                saleBody(shop, zeroBatch, 1),
                400
        );
        JsonNode negativeResponse = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                shop.tenantId(),
                shop.token(),
                saleBody(shop, negativeBatch, 1),
                400
        );

        assertThat(zeroResponse.path("message").asText()).contains("no available stock");
        assertThat(negativeResponse.path("message").asText()).contains("no available stock");
        assertThat(stockBatchRepository.findById(zeroBatch).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findById(negativeBatch).orElseThrow().getQuantity()).isEqualTo(-2000);
    }

    @Test
    @DisplayName("return after purchase offset restores the exact original negative batch usage")
    void returnAfterPurchaseOffsetRestoresOriginalNegativeBatch() throws Exception {
        Shop shop = shop("return-offset");
        JsonNode sale = sellWithoutStock(shop, 3);
        long orderItemId = sale.path("items").get(0).path("id").asLong();
        long negativeBatch = onlyNegativeBatch(shop).getId();

        createPurchase(shop, 5, 30, false);
        assertThat(stockBatchRepository.findById(negativeBatch).orElseThrow().getQuantity()).isZero();
        assertThat(total(shop)).isEqualTo(2000);

        String managerToken = login(shop.tenantId(), shop.fixture().manager().getUsername(), DEFAULT_PASSWORD);
        JsonNode returned = postJson("/orders/" + sale.path("invoiceNo").asText() + "/returns",
                shop.tenantId(), managerToken, """
                        {"reason": "return after purchase offset", "refundMethod": "CASH",
                         "items": [{"orderItemId": %d, "returnQty": 3}]}
                        """.formatted(orderItemId));

        assertThat(returned.path("totalRefundAmount").asDouble()).isEqualTo(90.0);
        assertThat(stockBatchRepository.findById(negativeBatch).orElseThrow().getQuantity()).isEqualTo(3000);
        assertThat(total(shop)).isEqualTo(5000);
        assertThat(orderItemStockUsages(orderItemId)).containsExactly(negativeBatch + ":" + 3000);
    }

    @Test
    @DisplayName("a past sale keeps its batch ID and returning it restores that original batch")
    void returnRestoresOriginalBatchAfterReconciliation() throws Exception {
        Shop shop = shop("returns");
        long originalBatch = insertBatch(shop.branch(), shop.item(), 1, "30.00");

        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(),
                saleBody(shop, originalBatch, 1));
        long orderItemId = sale.path("items").get(0).path("id").asLong();
        assertThat(stockBatchRepository.findById(originalBatch).orElseThrow().getQuantity()).isZero();

        String managerToken = login(shop.tenantId(), shop.fixture().manager().getUsername(), DEFAULT_PASSWORD);
        JsonNode returned = postJson("/orders/" + sale.path("invoiceNo").asText() + "/returns",
                shop.tenantId(), managerToken, """
                        {"reason": "return after migration", "refundMethod": "CASH",
                         "items": [{"orderItemId": %d, "returnQty": 1}]}
                        """.formatted(orderItemId));

        assertThat(returned.path("totalRefundAmount").asDouble()).isEqualTo(30.0);
        assertThat(stockBatchRepository.findById(originalBatch).orElseThrow().getQuantity()).isEqualTo(1000);

        OrderItem soldLine = orderItemRepository.findByOrderId(sale.path("id").asLong()).get(0);
        assertThat(soldLine.getBatchId()).isEqualTo(originalBatch);
        assertThat(total(shop)).isEqualTo(1000);
    }

    @Test
    @DisplayName("explicit batch IDs 0 and negative are rejected as invalid before lookup")
    void zeroAndNegativeExplicitBatchIdsAreRejected() throws Exception {
        Shop shop = shop("invalid-batch-id");

        JsonNode zeroIdResponse = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                shop.tenantId(),
                shop.token(),
                saleBody(shop, 0, 1),
                400
        );
        JsonNode negativeIdResponse = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                shop.tenantId(),
                shop.token(),
                saleBody(shop, -1, 1),
                400
        );

        assertThat(zeroIdResponse.path("message").asText()).contains("Invalid batch ID");
        assertThat(negativeIdResponse.path("message").asText()).contains("Invalid batch ID");
        assertThat(stockBatchRepository.findByBranchIdAndItemId(
                shop.branch().getId(), shop.item().getId())).isEmpty();
    }

    @Test
    @DisplayName("cancelling a fully offsetting purchase restores the negative batch, reverses the offset rows and deletes the GRN batch")
    void cancelAfterFullOffsetRestoresNegativeBatch() throws Exception {
        Shop shop = shop("cancel-full-offset");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");

        JsonNode purchase = createPurchase(shop, 8, 30, false);
        long purchaseId = purchase.path("purchaseId").asLong();
        String grnNo = purchase.path("grnList").get(0).path("grnNo").asText();
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isZero();
        List<StockBatch> grnBatches = stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + grnNo);
        assertThat(grnBatches).hasSize(1);
        assertThat(grnBatches.get(0).getQuantity()).isEqualTo(3000);

        List<PurchaseStockOffset> offsets = purchaseStockOffsetRepository.findByPurchaseIdOrderByIdAsc(purchaseId);
        assertThat(offsets).hasSize(1);
        assertThat(offsets.get(0).getNegativeBatchId()).isEqualTo(negative);
        assertThat(offsets.get(0).getQuantityOffset()).isEqualTo(5000);
        assertThat(offsets.get(0).getBatchQuantityAfterOffset()).isZero();
        assertThat(offsets.get(0).getGrnId()).isNotNull();
        assertThat(offsets.get(0).getGrnItemId()).isNotNull();
        assertThat(offsets.get(0).getReversedAt()).isNull();

        JsonNode canceled = cancelPurchase(shop, purchaseId, "wrong quantity");

        assertThat(canceled.path("status").asText()).isEqualTo("CANCELED");
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-5000);
        assertThat(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + grnNo)).isEmpty();
        assertThat(purchaseStockOffsetRepository.findByPurchaseIdOrderByIdAsc(purchaseId))
                .hasSize(1)
                .allSatisfy(offset -> assertThat(offset.getReversedAt()).isNotNull());
    }

    @Test
    @DisplayName("cancelling a partially offsetting purchase restores the negative batch")
    void cancelAfterPartialOffsetRestoresNegativeBatch() throws Exception {
        Shop shop = shop("cancel-partial-offset");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");

        // The receipt covers only part of the debt, so it is fully consumed by
        // the offset and no GRN batch is created.
        JsonNode purchase = createPurchase(shop, 2, 30, false);
        long purchaseId = purchase.path("purchaseId").asLong();
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-3000);
        assertThat(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + purchase.path("grnList").get(0).path("grnNo").asText()))
                .isEmpty();

        cancelPurchase(shop, purchaseId, "wrong quantity");

        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-5000);
        assertThat(purchaseStockOffsetRepository.findByPurchaseIdOrderByIdAsc(purchaseId))
                .allSatisfy(offset -> assertThat(offset.getReversedAt()).isNotNull());
    }

    @Test
    @DisplayName("cancelling an offset that consolidated several negative rows restores every row")
    void cancelAfterConsolidatingOffsetRestoresEveryNegativeRow() throws Exception {
        Shop shop = shop("cancel-multi-offset");
        long olderNegative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        long newerNegative = insertBatch(shop.branch(), shop.item(), -3, "35.00");

        JsonNode purchase = createPurchase(shop, 2, 30, false);
        // The receipt covers part of the older row, then consolidation moves
        // the whole remaining liability onto it and zeroes the newer row.
        assertThat(stockBatchRepository.findById(olderNegative).orElseThrow().getQuantity()).isEqualTo(-6000);
        assertThat(stockBatchRepository.findById(newerNegative).orElseThrow().getQuantity()).isZero();

        cancelPurchase(shop, purchase.path("purchaseId").asLong(), "wrong quantity");

        assertThat(stockBatchRepository.findById(olderNegative).orElseThrow().getQuantity()).isEqualTo(-5000);
        assertThat(stockBatchRepository.findById(newerNegative).orElseThrow().getQuantity()).isEqualTo(-3000);
    }

    @Test
    @DisplayName("cancellation is blocked when the offset target batch changed after the purchase")
    void cancelIsBlockedWhenOffsetTargetChangedAfterPurchase() throws Exception {
        Shop shop = shop("cancel-blocked-offset");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");
        JsonNode purchase = createPurchase(shop, 2, 30, false);
        long purchaseId = purchase.path("purchaseId").asLong();

        // Sell past the GRN batch so the shortage decrements the offset target
        // while leaving the GRN batch itself untouched.
        long other = insertBatch(shop.branch(), shop.item(), 1, "30.00");
        postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "stockOverrideReason": "Sale after purchase offset",
                  "items": [{
                    "itemId": %d,
                    "batchId": %d,
                    "qty": 2,
                    "qtyUnit": "PCS",
                    "unitPrice": 30,
                    "discountType": "NONE",
                    "discountValue": 0
                  }],
                  "billDiscount": 0,
                  "paidAmount": 60,
                  "paymentMethod": "CASH"
                }
                """.formatted(shop.branch().getId(), shop.item().getId(), other));
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-4000);

        JsonNode blocked = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/purchases/" + purchaseId + "/cancel",
                shop.tenantId(),
                shop.token(),
                """
                {"reason": "should be blocked"}
                """,
                400
        );

        assertThat(blocked.path("message").asText()).contains("sold or adjusted");
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isEqualTo(-4000);
        assertThat(getJson("/purchases/" + purchaseId, shop.tenantId(), shop.token()).path("status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("replacing an offsetting purchase reverses the original offsets and records new ones")
    void replacementOfOffsettingPurchaseReversesAndRedoesOffsets() throws Exception {
        Shop shop = shop("replace-offset");
        long negative = insertBatch(shop.branch(), shop.item(), -5, "30.00");

        JsonNode original = createPurchase(shop, 6, 30, false);
        long originalId = original.path("purchaseId").asLong();
        String originalGrnNo = original.path("grnList").get(0).path("grnNo").asText();
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isZero();

        JsonNode replacement = postJson("/purchases/" + originalId + "/replace", shop.tenantId(), shop.token(), """
                {
                  "supplierId": %d,
                  "invoiceNo": "%s",
                  "branches": [{
                    "branchId": %d,
                    "items": [{
                      "itemId": %d,
                      "qty": 6,
                      "costPrice": 20,
                      "sellingPrice": 30,
                      "zeroNegativeStock": false
                    }]
                  }]
                }
                """.formatted(shop.supplier().getId(), original.path("invoiceNo").asText(),
                shop.branch().getId(), shop.item().getId()));
        long replacementId = replacement.path("purchaseId").asLong();

        assertThat(replacementId).isNotEqualTo(originalId);
        assertThat(stockBatchRepository.findById(negative).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + originalGrnNo)).isEmpty();
        List<StockBatch> replacementBatches = stockBatchRepository.findByBranchIdAndBatchCodeStartingWith(
                shop.branch().getId(), "GRN-" + replacement.path("grnList").get(0).path("grnNo").asText());
        assertThat(replacementBatches).hasSize(1);
        assertThat(replacementBatches.get(0).getQuantity()).isEqualTo(1000);

        assertThat(purchaseStockOffsetRepository.findByPurchaseIdOrderByIdAsc(originalId))
                .hasSize(1)
                .allSatisfy(offset -> assertThat(offset.getReversedAt()).isNotNull());
        assertThat(purchaseStockOffsetRepository.findByPurchaseIdAndReversedAtIsNullOrderByIdAsc(replacementId))
                .hasSize(1)
                .allSatisfy(offset -> {
                    assertThat(offset.getNegativeBatchId()).isEqualTo(negative);
                    assertThat(offset.getQuantityOffset()).isEqualTo(5000);
                });

        JsonNode originalState = getJson("/purchases/" + originalId, shop.tenantId(), shop.token());
        assertThat(originalState.path("status").asText()).isEqualTo("CANCELED");
        assertThat(originalState.path("replacedByPurchaseId").asLong()).isEqualTo(replacementId);
    }

    @Test
    @DisplayName("concurrent sale cancellation and checkout serialize without losing stock")
    void concurrentSaleCancellationAndCheckoutSerialize() throws Exception {
        Shop shop = shop("sale-cancel-race");
        long saleBatch = insertBatch(shop.branch(), shop.item(), 5, "30.00");
        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), saleBody(shop, saleBatch, 2));
        String invoiceNo = sale.path("invoiceNo").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        JsonNode cancel;
        JsonNode checkout;
        try {
            Future<JsonNode> cancellation = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return cancelSale(shop, invoiceNo);
            });
            Future<JsonNode> saleFuture = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return postJson("/orders", shop.tenantId(), shop.token(), saleBody(shop, saleBatch, 1));
            });
            start.countDown();
            cancel = cancellation.get(20, TimeUnit.SECONDS);
            checkout = saleFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(cancel.path("status").asText()).isEqualTo("CANCELED");
        assertThat(checkout.path("id").asLong()).isPositive();
        assertThat(total(shop)).isEqualTo(4_000);
    }

    @Test
    @DisplayName("two simultaneous sale cancellations restore stock exactly once")
    void duplicateSaleCancellationRestoresOnce() throws Exception {
        Shop shop = shop("sale-cancel-duplicate");
        long saleBatch = insertBatch(shop.branch(), shop.item(), 5, "30.00");
        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), saleBody(shop, saleBatch, 2));
        String invoiceNo = sale.path("invoiceNo").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        JsonNode first;
        JsonNode second;
        try {
            Future<JsonNode> a = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return cancelSale(shop, invoiceNo);
            });
            Future<JsonNode> b = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return cancelSale(shop, invoiceNo);
            });
            start.countDown();
            first = a.get(20, TimeUnit.SECONDS);
            second = b.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(first.path("status").asText()).isEqualTo("CANCELED");
        assertThat(second.path("status").asText()).isEqualTo("CANCELED");
        assertThat(stockBatchRepository.findById(saleBatch).orElseThrow().getQuantity()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("duplicate concurrent returns cannot restore more than the sold quantity")
    void duplicateConcurrentReturnRestoresOnlyOnce() throws Exception {
        Shop shop = shop("return-duplicate-race");
        long saleBatch = insertBatch(shop.branch(), shop.item(), 5, "30.00");
        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), saleBody(shop, saleBatch, 1));
        long orderItemId = sale.path("items").get(0).path("id").asLong();
        String invoiceNo = sale.path("invoiceNo").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<JsonNode> results = new java.util.ArrayList<>();
        try {
            Future<JsonNode> a = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return returnSale(shop, invoiceNo, orderItemId, 1);
            });
            Future<JsonNode> b = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return returnSale(shop, invoiceNo, orderItemId, 1);
            });
            start.countDown();
            results.add(a.get(20, TimeUnit.SECONDS));
            results.add(b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("returnNo").asText()).isNotBlank();
        assertThat(results.get(1).path("returnNo").asText()).isNotBlank();
        assertThat(total(shop)).isEqualTo(5_000);
        assertThat(orderItemStockUsages(orderItemId)).containsExactly(saleBatch + ":1000");
    }

    @Test
    @DisplayName("a multi-line sale cancellation restores every line")
    void multiLineSaleCancellationRestoresAllLines() throws Exception {
        Shop shop = shop("multi-line-cancel");
        long firstBatch = insertBatch(shop.branch(), shop.item(), 4, "30.00");
        Item secondItem = createItem(shop, "Second cancellation item");
        long secondBatch = insertBatch(shop.branch(), secondItem, 4, "30.00");
        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {"itemId": %d, "batchId": %d, "qty": 2, "qtyUnit": "PCS", "unitPrice": 30, "discountType": "NONE", "discountValue": 0},
                    {"itemId": %d, "batchId": %d, "qty": 2, "qtyUnit": "PCS", "unitPrice": 30, "discountType": "NONE", "discountValue": 0}
                  ],
                  "billDiscount": 0,
                  "paidAmount": 120,
                  "paymentMethod": "CASH"
                }
                """.formatted(shop.branch().getId(), shop.item().getId(), firstBatch,
                secondItem.getId(), secondBatch));

        cancelSale(shop, sale.path("invoiceNo").asText());

        assertThat(stockBatchRepository.findById(firstBatch).orElseThrow().getQuantity()).isEqualTo(4_000);
        assertThat(stockBatchRepository.findById(secondBatch).orElseThrow().getQuantity()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("a multi-line sale return restores every line")
    void multiLineSaleReturnRestoresAllLines() throws Exception {
        Shop shop = shop("multi-line-return");
        long firstBatch = insertBatch(shop.branch(), shop.item(), 4, "30.00");
        Item secondItem = createItem(shop, "Second return item");
        long secondBatch = insertBatch(shop.branch(), secondItem, 4, "30.00");
        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {"itemId": %d, "batchId": %d, "qty": 2, "qtyUnit": "PCS", "unitPrice": 30, "discountType": "NONE", "discountValue": 0},
                    {"itemId": %d, "batchId": %d, "qty": 2, "qtyUnit": "PCS", "unitPrice": 30, "discountType": "NONE", "discountValue": 0}
                  ],
                  "billDiscount": 0,
                  "paidAmount": 120,
                  "paymentMethod": "CASH"
                }
                """.formatted(shop.branch().getId(), shop.item().getId(), firstBatch,
                secondItem.getId(), secondBatch));
        String managerToken = login(shop.tenantId(), shop.fixture().manager().getUsername(), DEFAULT_PASSWORD);

        postJson("/orders/" + sale.path("invoiceNo").asText() + "/returns", shop.tenantId(), managerToken, """
                {"reason": "multi-line return", "refundMethod": "CASH",
                 "items": [
                   {"orderItemId": %d, "returnQty": 1},
                   {"orderItemId": %d, "returnQty": 1}
                 ]}
                """.formatted(sale.path("items").get(0).path("id").asLong(),
                sale.path("items").get(1).path("id").asLong()));

        assertThat(stockBatchRepository.findById(firstBatch).orElseThrow().getQuantity()).isEqualTo(3_000);
        assertThat(stockBatchRepository.findById(secondBatch).orElseThrow().getQuantity()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("a returned sale cannot later be canceled and restored a second time")
    void cancellationAfterReturnIsRejected() throws Exception {
        Shop shop = shop("cancel-after-return");
        long saleBatch = insertBatch(shop.branch(), shop.item(), 5, "30.00");
        JsonNode sale = postJson("/orders", shop.tenantId(), shop.token(), saleBody(shop, saleBatch, 2));
        long orderItemId = sale.path("items").get(0).path("id").asLong();
        String invoiceNo = sale.path("invoiceNo").asText();
        returnSale(shop, invoiceNo, orderItemId, 1);
        assertThat(total(shop)).isEqualTo(4_000);

        JsonNode blocked = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders/" + invoiceNo + "/cancel",
                shop.tenantId(),
                shop.token(),
                "{\"reason\":\"cannot cancel after return\"}",
                400
        );
        assertThat(blocked.path("message").asText()).contains("already been returned");
        assertThat(total(shop)).isEqualTo(4_000);
        assertThat(getJson("/orders/" + invoiceNo, shop.tenantId(), shop.token())
                .path("status").asText()).isEqualTo("COMPLETED");
    }

    private JsonNode cancelPurchase(Shop shop, long purchaseId, String reason) throws Exception {
        return postJson("/purchases/" + purchaseId + "/cancel", shop.tenantId(), shop.token(), """
                {"reason": "%s"}
                """.formatted(reason));
    }

    private JsonNode cancelSale(Shop shop, String invoiceNo) throws Exception {
        return postJson("/orders/" + invoiceNo + "/cancel", shop.tenantId(), shop.token(), """
                {"reason": "cancel sale"}
                """);
    }

    private JsonNode returnSale(Shop shop, String invoiceNo, long orderItemId, int returnQty) throws Exception {
        String managerToken = login(shop.tenantId(), shop.fixture().manager().getUsername(), DEFAULT_PASSWORD);
        return postJson("/orders/" + invoiceNo + "/returns", shop.tenantId(), managerToken, """
                {"reason": "return sale", "refundMethod": "CASH",
                 "items": [{"orderItemId": %d, "returnQty": %d}]}
                """.formatted(orderItemId, returnQty));
    }

    private JsonNode sellWithoutStock(Shop shop, int quantity) throws Exception {
        return sellWithoutStock(shop, quantity, "30.00", 30.0);
    }

    private String offlineSellBody(Shop shop, int quantity, double unitPrice, String clientSaleId) {
        return """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-11-02T00:00:00",
                  "invoiceNo": "%s",
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [{
                    "itemId": %d,
                    "qty": %d,
                    "qtyUnit": "PCS",
                    "unitPrice": %s,
                    "discountType": "NONE",
                    "discountValue": 0
                  }],
                  "billDiscount": 0,
                  "paidAmount": %s,
                  "paymentMethod": "CASH"
                }
                """.formatted(
                clientSaleId,
                clientSaleId,
                shop.branch().getId(),
                shop.item().getId(),
                quantity,
                BigDecimal.valueOf(unitPrice).stripTrailingZeros().toPlainString(),
                Math.round(quantity * unitPrice)
        );
    }

    private JsonNode sellWithoutStock(
            Shop shop,
            int quantity,
            String selectedSellingPrice,
            double unitPrice
    ) throws Exception {
        return postJson("/orders", shop.tenantId(), shop.token(), """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "stockOverrideReason": "Authorized negative-stock sale",
                  "items": [{
                    "itemId": %d,
                    "qty": %d,
                    "qtyUnit": "PCS",
                    "unitPrice": %s,
                    "discountType": "NONE",
                    "discountValue": 0
                  }],
                  "billDiscount": 0,
                  "paidAmount": %s,
                  "paymentMethod": "CASH"
                }
                """.formatted(
                shop.branch().getId(),
                shop.item().getId(),
                quantity,
                BigDecimal.valueOf(unitPrice).stripTrailingZeros().toPlainString(),
                Math.round(quantity * unitPrice)
        ));
    }

    private List<Long> negativeBatches(Shop shop) {
        return stockBatchRepository.findNegativeBatchesForBranchItem(
                        shop.branch().getId(), shop.item().getId())
                .stream()
                .map(StockBatch::getId)
                .toList();
    }

    private StockBatch onlyNegativeBatch(Shop shop) {
        List<StockBatch> negativeBatches = stockBatchRepository.findNegativeBatchesForBranchItem(
                shop.branch().getId(), shop.item().getId());
        assertThat(negativeBatches).hasSize(1);
        return negativeBatches.get(0);
    }

    private List<String> orderItemStockUsages(long orderItemId) {
        return orderItemStockUsageRepository.findByOrderItemId(orderItemId).stream()
                .map(usage -> usage.getBatchId() + ":" + usage.getQuantity())
                .toList();
    }

    private JsonNode overrideAudit(long orderItemId) throws Exception {
        return objectMapper.valueToTree(
                stockOverrideAuditRepository.findAll().stream()
                        .filter(audit -> audit.getOrderItemId().equals(orderItemId))
                        .findFirst()
                        .orElseThrow()
        );
    }

    private Branch createBranch(String code) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName(code + " branch");
        branch.setAddress("Colombo");
        branch.setPhone("0770000000");
        branch.setActive(true);
        return branchRepository.save(branch);
    }

    private JsonNode createPurchase(Shop shop, int quantity, int sellingPrice, boolean zeroNegative)
            throws Exception {
        return postJson("/purchases", shop.tenantId(), shop.token(), """
                {
                  "supplierId": %d,
                  "invoiceNo": "%s",
                  "branches": [{
                    "branchId": %d,
                    "items": [{
                      "itemId": %d,
                      "qty": %d,
                      "costPrice": 20,
                      "sellingPrice": %d,
                      "zeroNegativeStock": %s
                    }]
                  }]
                }
                """.formatted(
                shop.supplier().getId(),
                uniqueKey("INV"),
                shop.branch().getId(),
                shop.item().getId(),
                quantity,
                sellingPrice,
                zeroNegative
        ));
    }

    private String saleBody(Shop shop, long batchId, int quantity) {
        return """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [{
                    "itemId": %d,
                    "batchId": %d,
                    "qty": %d,
                    "qtyUnit": "PCS",
                    "unitPrice": 30,
                    "discountType": "NONE",
                    "discountValue": 0
                  }],
                  "billDiscount": 0,
                  "paidAmount": %d,
                  "paymentMethod": "CASH"
                }
                """.formatted(
                shop.branch().getId(),
                shop.item().getId(),
                batchId,
                quantity,
                quantity * 30
        );
    }

    private Shop shop(String prefix) throws Exception {
        return shop(prefix, 2);
    }

    private Shop shop(String prefix, int maxBranches) throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey(prefix), maxBranches);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName(uniqueKey("Negative category"));
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName(uniqueKey("Negative stock"));
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName(uniqueKey("Negative supplier"));
        supplier.setPhone("07" + String.format("%08d",
                Math.floorMod(System.nanoTime(), 100_000_000L)));
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode itemJson = postJson("/items", fixture.tenantId(), token, """
                {
                  "barcode": "%s",
                  "name": "Negative stock item",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0
                }
                """.formatted(uniqueKey("NEG"), subCategory.getId()));
        Item item = itemRepository.findById(itemJson.path("id").asLong()).orElseThrow();

        return new Shop(fixture, token, item, supplier);
    }

    private Item createItem(Shop shop, String name) throws Exception {
        JsonNode itemJson = postJson("/items", shop.tenantId(), shop.token(), """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0
                }
                """.formatted(uniqueKey("SECOND"), name, shop.item().getSubCategory().getId()));
        return itemRepository.findById(itemJson.path("id").asLong()).orElseThrow();
    }

    private long insertBatch(Branch branch, Item item, int displayQuantity, String sellingPrice) {
        int quantity = displayQuantity * 1000;
        return stockBatchRepository.save(StockBatch.builder()
                .branch(branch)
                .item(item)
                .quantity(quantity)
                .originalQuantity(Math.abs(quantity))
                .costPrice(BigDecimal.valueOf(20))
                .sellingPrice(new BigDecimal(sellingPrice))
                .receivedAt(java.time.LocalDateTime.now())
                .batchCode(uniqueKey("SEED"))
                .build()).getId();
    }

    private int total(Shop shop) {
        return stockBatchRepository.getTotalQuantityByItemAndBranch(
                shop.branch().getId(), shop.item().getId());
    }

    private record Shop(TenantFixture fixture, String token, Item item, Supplier supplier) {
        String tenantId() {
            return fixture.tenantId();
        }

        Branch branch() {
            return fixture.mainBranch();
        }
    }
}