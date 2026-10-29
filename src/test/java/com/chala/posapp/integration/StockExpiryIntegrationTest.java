package com.chala.posapp.integration;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expiry filter + expired-stock report contract. The boundary semantics live
 * in ExpiryRules and are exercised here end to end: expireDate before today is
 * EXPIRED, today through the configured cutoff is EXPIRING_SOON, after that
 * NOT_EXPIRED, and no expiry date is MISSING_EXPIRY (never silently safe).
 */
class StockExpiryIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void expiryBoundariesAndReportTotalsFollowConfiguredWindow() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("expiry"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        Long branchId = fixture.mainBranch().getId();

        // Quantities are base units: NORMAL items store milli-units, so 10
        // display pieces = 10000 base units. Values follow the same scaling as
        // the stock-value KPI (display qty × cost).
        Item yesterdayItem = saveExpiryItem(fixture, "EXP-YESTERDAY", LocalDate.now().minusDays(1), 10_000, "10.00");
        Item todayItem = saveExpiryItem(fixture, "EXP-TODAY", LocalDate.now(), 5_000, "20.00");
        Item cutoffItem = saveExpiryItem(fixture, "EXP-CUTOFF", LocalDate.now().plusMonths(2), 4_000, "30.00");
        Item afterCutoffItem = saveExpiryItem(fixture, "EXP-AFTER", LocalDate.now().plusMonths(2).plusDays(1), 7_000, "40.00");
        Item mixedItem = saveExpiryItem(fixture, "EXP-MIXED", LocalDate.now().minusDays(5), 6_000, "50.00");
        // Fresh batch on the same item: aggregate EXPIRED matches, but the
        // total on-hand quantity stays the sum of every positive batch.
        saveBatch(mixedItem, fixture.mainBranch(), "EXP-MIXED-FRESH", LocalDate.now().plusMonths(3), 4_000, "50.00");
        Item zeroQtyItem = saveExpiryItem(fixture, "EXP-ZERO", LocalDate.now().minusDays(2), 0, "60.00");
        Item missingItem = saveExpiryItem(fixture, "EXP-MISSING", null, 3_000, "70.00");

        // ---- Stock table filter: boundaries ----
        assertItemIds(branchId, token, tenantId, "EXPIRED", yesterdayItem, mixedItem);
        assertItemIds(branchId, token, tenantId, "EXPIRING_SOON", todayItem, cutoffItem);
        assertItemIds(branchId, token, tenantId, "NOT_EXPIRED", afterCutoffItem);
        assertItemIds(branchId, token, tenantId, "MISSING_EXPIRY", missingItem);
        assertItemIds(branchId, token, tenantId, "ALL",
                yesterdayItem, todayItem, cutoffItem, afterCutoffItem, mixedItem, zeroQtyItem, missingItem);

        // Mixed item keeps the total on-hand quantity (display units), not
        // just expired qty.
        JsonNode mixedRow = rowFor(branchId, token, tenantId, "EXPIRED", mixedItem);
        assertEquals(10_000, mixedRow.path("totalQuantity").asInt(), "mixed item total (base units) must count fresh batches too");

        // Zero-quantity expired batch: not in EXPIRED (no positive stock), and
        // OUT_OF_STOCK stock-status combined with EXPIRED excludes it too.
        JsonNode zeroInExpired = rowFor(branchId, token, tenantId, "EXPIRED", zeroQtyItem);
        assertTrue(zeroInExpired.isMissingNode(), "zero-qty expired batch must not mark the item EXPIRED");

        // Stock value KPI honors the same expiry filter. Like the other
        // stock-status filters, an item flagged EXPIRED contributes its whole
        // positive stock (both the expired and the fresh batch): 100 + 300 + 200.
        double expiredValue = getJson("/stock/branch/" + branchId + "/value?expiryStatus=EXPIRED", tenantId, token)
                .path("stockValue").asDouble();
        assertEquals(600.0, expiredValue, 0.01);

        // ---- Report: batch rows, totals counted once per batch ----
        JsonNode report = getJson("/reports/v2/expired-stock?branchId=" + branchId + "&size=50", tenantId, token);
        JsonNode summary = report.path("summary");
        assertEquals(2, summary.path("expiredBatches").asLong(), "yesterday + mixed's expired batch");
        assertEquals(16, summary.path("expiredQuantity").asLong(), "display units: 10 + 6");
        assertEquals(10 * 10 + 6 * 50, summary.path("expiredCostValue").asDouble(), 0.01);
        assertEquals(2, summary.path("upcomingBatches").asLong(), "today + cutoff day");
        assertEquals(9, summary.path("upcomingQuantity").asLong(), "display units: 5 + 4");
        assertEquals(5 * 20 + 4 * 30, summary.path("upcomingCostValue").asDouble(), 0.01);
        assertEquals(1, summary.path("missingExpiryBatches").asLong());
        assertEquals(3, summary.path("missingExpiryQuantity").asLong(), "display units");
        // affectedItems counts distinct items with any positive batch in the
        // default active view: all 6 items (missing-expiry included), each
        // once even though the mixed item owns two batches.
        assertEquals(6, summary.path("affectedItems").asLong());
        assertEquals(2, report.path("expiryAlertMonths").asInt(), "default window is 2 months");

        // Every positive-qty batch appears exactly once (mixed item contributes
        // two rows); the zero-qty batch is hidden under the default view.
        assertEquals(7, report.path("totalElements").asLong());

        JsonNode todayRow = reportRowFor(report, todayItem, LocalDate.now());
        assertEquals("EXPIRING_SOON", todayRow.path("expiryStatus").asText());
        assertEquals(0, todayRow.path("daysToExpiry").asLong(), "expiring today is 0 days remaining, still EXPIRING_SOON");
        JsonNode yesterdayRow = reportRowFor(report, yesterdayItem, LocalDate.now().minusDays(1));
        assertEquals("EXPIRED", yesterdayRow.path("expiryStatus").asText());
        assertEquals(1, yesterdayRow.path("daysPastExpiry").asLong());

        // ---- Report: expiryStatus filter and zero-quantity opt-in ----
        JsonNode expiredOnly = getJson("/reports/v2/expired-stock?branchId=" + branchId + "&expiryStatus=EXPIRED", tenantId, token);
        assertEquals(2, expiredOnly.path("totalElements").asLong());
        JsonNode withZero = getJson("/reports/v2/expired-stock?branchId=" + branchId + "&expiryStatus=EXPIRED&includeZeroQuantity=true", tenantId, token);
        assertEquals(3, withZero.path("totalElements").asLong(), "zero-qty expired batch visible only when opted in");

        // ---- Report: pagination and sorting ----
        JsonNode pageOne = getJson("/reports/v2/expired-stock?branchId=" + branchId + "&size=3&page=0", tenantId, token);
        assertEquals(3, pageOne.path("items").size());
        assertEquals(7, pageOne.path("totalElements").asLong());
        assertEquals(3, pageOne.path("totalPages").asInt());
        assertTrue(pageOne.path("first").asBoolean());
        JsonNode pageTwo = getJson("/reports/v2/expired-stock?branchId=" + branchId + "&size=3&page=1", tenantId, token);
        assertFalse(pageTwo.path("first").asBoolean());
        JsonNode sortedByValue = getJson("/reports/v2/expired-stock?branchId=" + branchId + "&sortBy=value&sortDirection=desc&size=1", tenantId, token);
        assertEquals(6 * 50, sortedByValue.path("items").get(0).path("estimatedCostValue").asDouble(), 0.01);

        // ---- Expiry date range filter ----
        // Range is inclusive on both ends; cutoff-day = today+2 months exactly,
        // so today + cutoff both match. range includes only expiry-bearing
        // batches (MISSING_EXPIRY has no date and stays out of a date filter).
        JsonNode ranged = getJson("/reports/v2/expired-stock?branchId=" + branchId
                + "&expiryFrom=" + LocalDate.now() + "&expiryTo=" + LocalDate.now().plusMonths(2), tenantId, token);
        assertEquals(2, ranged.path("totalElements").asLong(), "today + cutoff day, range is inclusive");

        // Range can also exclude the cutoff batch by ending a day earlier.
        JsonNode rangedBeforeCutoff = getJson("/reports/v2/expired-stock?branchId=" + branchId
                + "&expiryFrom=" + LocalDate.now() + "&expiryTo=" + LocalDate.now().plusMonths(2).minusDays(1), tenantId, token);
        assertEquals(1, rangedBeforeCutoff.path("totalElements").asLong(), "only today survives the narrower range");

        // ---- Config: window round-trip and cutoff arithmetic ----
        JsonNode saved = putJson("/app-configuration?branchId=" + branchId, tenantId, token, """
                {"expiryAlertMonths":1}
                """);
        assertEquals(1, saved.path("expiryAlertMonths").asInt());
        // With a 1-month window the 2-month batches are NOT_EXPIRED; cutoff
        // arithmetic is calendar-month based.
        JsonNode oneMonth = getJson("/reports/v2/expired-stock?branchId=" + branchId + "&expiryStatus=NOT_EXPIRED", tenantId, token);
        assertEquals(3, oneMonth.path("totalElements").asLong(), "cutoff + after + fresh mixed batch");
        // Restore default for the branch.
        putJson("/app-configuration?branchId=" + branchId, tenantId, token, """
                {"expiryAlertMonths":2}
                """);
    }

    @Test
    void cashierSeesOnlyAssignedBranchAndExpiryFilterIsServerSide() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("expiry-cashier"), 2);
        String tenantId = fixture.tenantId();

        Branch otherBranch = new Branch();
        otherBranch.setCode(uniqueKey("EXPB").toUpperCase().replace("-", ""));
        otherBranch.setName("Other Branch");
        otherBranch.setAddress("Other address");
        otherBranch.setPhone("0770000001");
        otherBranch.setActive(true);
        otherBranch = branchRepository.save(otherBranch);

        Item mainItem = saveExpiryItem(fixture, "CASH-MAIN-EXPIRED", LocalDate.now().minusDays(1), 8, "10.00");
        saveItemFor(otherBranch, "CASH-OTHER-EXPIRED", LocalDate.now().minusDays(1), 9, "10.00");

        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        // Cashier's own branch: expired filter works for the stock table.
        JsonNode own = getJson("/stock/branch/" + fixture.mainBranch().getId() + "?expiryStatus=EXPIRED", tenantId, cashierToken);
        assertEquals(1, own.path("totalElements").asLong());
        assertEquals(mainItem.getId().longValue(), own.path("content").get(0).path("itemId").asLong());

        // Other branch is refused for the cashier: 400, matching the
        // enforceBranchAccess contract every stock endpoint already uses.
        jsonRequest(org.springframework.http.HttpMethod.GET,
                "/stock/branch/" + otherBranch.getId() + "?expiryStatus=EXPIRED",
                tenantId, cashierToken, null, 400);

        // Report resolves the same branch scoping for a manager.
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);
        JsonNode report = getJson("/reports/v2/expired-stock", tenantId, managerToken);
        assertEquals(1, report.path("totalElements").asLong());
        assertEquals(mainItem.getId().longValue(), report.path("items").get(0).path("itemId").asLong());
    }

    private Item saveExpiryItem(TenantFixture fixture, String barcode, LocalDate expiry, int qty, String cost) {
        return saveItemFor(fixture.mainBranch(), barcode, expiry, qty, cost);
    }

    private Item saveItemFor(Branch branch, String barcode, LocalDate expiry, int qty, String cost) {
        Category c = new Category();
        c.setName(barcode + "-CAT");
        c = categoryRepository.save(c);
        SubCategory sc = new SubCategory();
        sc.setName(barcode + "-SUB");
        sc.setCategory(c);
        sc = subCategoryRepository.save(sc);
        Item item = itemRepository.save(Item.builder().barcode(barcode).name(barcode).subCategory(sc)
                .costPrice(new BigDecimal(cost)).sellingPrice(new BigDecimal(cost).multiply(BigDecimal.valueOf(2)))
                .reorderLevel(0).itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS).active(true).build());
        saveBatch(item, branch, barcode, expiry, qty, cost);
        return item;
    }

    private void saveBatch(Item item, Branch branch, String batchCode, LocalDate expiry, int qty, String cost) {
        stockBatchRepository.save(StockBatch.builder().branch(branch).item(item).batchCode(batchCode)
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(new BigDecimal(cost)).sellingPrice(new BigDecimal(cost).multiply(BigDecimal.valueOf(2)))
                .quantity(qty).originalQuantity(qty)
                .expireDate(expiry != null ? expiry.atStartOfDay() : null)
                .build());
    }

    private void assertItemIds(Long branchId, String token, String tenantId, String expiryStatus, Item... expectedItems) throws Exception {
        String path = "/stock/branch/" + branchId + "?expiryStatus=" + expiryStatus + "&size=100";
        JsonNode content = getJson(path, tenantId, token).path("content");
        assertEquals(expectedItems.length, content.size(),
                "expiryStatus=" + expiryStatus + " expected " + expectedItems.length + " rows");
        var expected = new java.util.HashSet<Long>();
        for (Item item : expectedItems) expected.add(item.getId());
        for (JsonNode row : content) {
            long id = row.path("itemId").asLong();
            assertTrue(expected.remove(id), "unexpected item " + id + " under expiryStatus=" + expiryStatus);
        }
        assertTrue(expected.isEmpty(), "missing expected items under expiryStatus=" + expiryStatus);
    }

    private JsonNode rowFor(Long branchId, String token, String tenantId, String expiryStatus, Item item) throws Exception {
        String path = "/stock/branch/" + branchId + "?expiryStatus=" + expiryStatus
                + "&search=" + item.getBarcode() + "&size=100";
        JsonNode content = getJson(path, tenantId, token).path("content");
        for (JsonNode row : content) {
            if (row.path("itemId").asLong() == item.getId()) return row;
        }
        return MissingNode.getInstance();
    }

    private JsonNode reportRowFor(JsonNode report, Item item, LocalDate expireDate) {
        for (JsonNode row : report.path("items")) {
            if (row.path("itemId").asLong() == item.getId()
                    && row.path("expireDate").asText().startsWith(expireDate.toString())) {
                return row;
            }
        }
        return MissingNode.getInstance();
    }
}
