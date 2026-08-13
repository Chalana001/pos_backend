package com.chala.posapp.integration;

import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import com.chala.posapp.entity.SaleMode;
import com.chala.posapp.entity.ShiftStatus;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.entity.CashSource;
import com.chala.posapp.entity.CreditPayment;
import com.chala.posapp.entity.Expense;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.entity.PurchaseReturn;
import com.chala.posapp.entity.ReturnStatus;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.stock.StockProcessing;
import com.chala.posapp.entity.stock.StockProcessingOutput;
import com.chala.posapp.repository.StockProcessingRepository;
import com.chala.posapp.repository.StockProcessingOutputRepository;
import com.chala.posapp.repository.PurchaseReturnRepository;
import com.chala.posapp.service.ReportExportJobScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewReportControllerIntegrationTest extends ApiIntegrationTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private StockProcessingRepository stockProcessingRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private StockProcessingOutputRepository stockProcessingOutputRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private PurchaseReturnRepository purchaseReturnRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private ReportExportJobScheduler reportExportJobScheduler;

    @Test
    void durableExportWorkerCompletesQueuedTenantJob() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-export-job"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode queued = jsonRequest(org.springframework.http.HttpMethod.POST, "/reports/export-jobs", tenantId, token, """
                {"reportType":"SALES","branchId":%d,"sortBy":"DATE","sortDirection":"DESC"}
                """.formatted(fixture.mainBranch().getId()), 202);
        assertEquals("QUEUED", queued.path("status").asText());

        reportExportJobScheduler.processTenantNow(tenantId);

        JsonNode completed = getJson("/reports/export-jobs/" + queued.path("id").asLong(), tenantId, token);
        assertEquals("COMPLETED", completed.path("status").asText(), completed.path("errorMessage").asText());
        assertTrue(completed.path("downloadable").asBoolean());
    }

    @Test
    void demandForecastProjectsRecentDemandAndLabelsSparseHistory() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("forecast"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        Item steady = saveForecastItem(fixture, "FORECAST-STEADY", 10, 8, 20);
        Item sparse = saveForecastItem(fixture, "FORECAST-SPARSE", 6, 5, 15);

        for (int day = 1; day <= 20; day++) saveForecastSale(fixture, steady, 2000, day, "STEADY-" + day);
        saveForecastSale(fixture, sparse, 1000, 2, "SPARSE-1");

        JsonNode response = getJson("/api/reports/v2/demand-forecast?branchId=" + fixture.mainBranch().getId()
                + "&forecastDays=30&targetCoverDays=30", tenantId, token);
        assertEquals(90, response.path("historyDays").asInt());
        assertEquals(30, response.path("forecastDays").asInt());
        assertTrue(response.path("actionableItems").asLong() >= 1);

        JsonNode steadyForecast = findByItemId(response.path("items"), steady.getId());
        assertEquals("HIGH", steadyForecast.path("confidence").asText());
        assertTrue(steadyForecast.path("projectedDemand").asDouble() > 0);
        assertTrue(steadyForecast.path("suggestedReorderQty").asDouble() > 0);

        JsonNode sparseForecast = findByItemId(response.path("items"), sparse.getId());
        assertEquals("INSUFFICIENT", sparseForecast.path("confidence").asText());
        assertEquals(0.0, sparseForecast.path("projectedDemand").asDouble(), 0.001);
        assertFalse(sparseForecast.path("warning").asText().isBlank());

        JsonNode highOnly = getJson("/api/reports/v2/demand-forecast?branchId=" + fixture.mainBranch().getId()
                + "&forecastDays=14&targetCoverDays=30&confidence=HIGH&actionableOnly=true", tenantId, token);
        assertEquals("HIGH", highOnly.path("confidenceFilter").asText());
        assertTrue(highOnly.path("actionableOnly").asBoolean());
        for (JsonNode item : highOnly.path("items")) {
            assertEquals("HIGH", item.path("confidence").asText());
            assertTrue(item.path("suggestedReorderQty").asDouble() > 0);
        }

        JsonNode export = jsonRequest(org.springframework.http.HttpMethod.POST, "/reports/export-jobs", tenantId, token, """
                {"reportType":"DEMAND_FORECAST","branchId":%d,"forecastDays":14,"targetCoverDays":30,"confidence":"HIGH","actionableOnly":true}
                """.formatted(fixture.mainBranch().getId()), 202);
        reportExportJobScheduler.processTenantNow(tenantId);
        JsonNode completed = getJson("/reports/export-jobs?page=0&size=10", tenantId, token).path("items").get(0);
        assertEquals(export.path("id").asLong(), completed.path("id").asLong());
        assertEquals("COMPLETED", completed.path("status").asText());
        assertTrue(completed.path("downloadable").asBoolean());
    }

    @Test
    void reportExportLifecycleSupportsSchedulesCancellationAndDeletion() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-lifecycle"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode queued = jsonRequest(org.springframework.http.HttpMethod.POST, "/reports/export-jobs", tenantId, token, """
                {"reportType":"SALES","branchId":%d,"sortBy":"DATE","sortDirection":"DESC"}
                """.formatted(fixture.mainBranch().getId()), 202);
        JsonNode cancelled = postJson("/reports/export-jobs/" + queued.path("id").asLong() + "/cancel", tenantId, token, "{}");
        assertEquals("CANCELLED", cancelled.path("status").asText());
        deleteExpectingStatus("/reports/export-jobs/" + queued.path("id").asLong(), tenantId, token, 204);

        JsonNode schedule = postJson("/reports/schedules", tenantId, token, """
                {
                  "report":{"reportType":"PRODUCT","branchId":%d,"sortBy":"REVENUE","sortDirection":"DESC"},
                  "frequency":"DAILY",
                  "nextRunAt":"%s"
                }
                """.formatted(fixture.mainBranch().getId(), LocalDateTime.now().minusMinutes(1)));
        assertTrue(schedule.path("enabled").asBoolean());

        reportExportJobScheduler.processTenantNow(tenantId);

        JsonNode jobs = getJson("/reports/export-jobs?page=0&size=10", tenantId, token);
        assertEquals("PRODUCT", jobs.path("items").get(0).path("reportType").asText());
        assertEquals(schedule.path("id").asLong(), jobs.path("items").get(0).path("scheduleId").asLong());
        assertEquals("COMPLETED", jobs.path("items").get(0).path("status").asText());

        JsonNode schedules = getJson("/reports/schedules", tenantId, token);
        assertEquals(1, schedules.size());
        assertTrue(LocalDateTime.parse(schedules.get(0).path("nextRunAt").asText()).isAfter(LocalDateTime.now()));
        deleteExpectingStatus("/reports/schedules/" + schedule.path("id").asLong(), tenantId, token, 204);
    }

    @Test
    void creditReportsRespectRequestedBranch() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-credit"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode secondBranch = postJson(
                "/branches",
                tenantId,
                token,
                """
                {
                  "code": "CREDIT2",
                  "name": "Credit Branch Two",
                  "address": "Second address",
                  "phone": "0771000002"
                }
                """
        );

        Customer customer = customerRepository.save(Customer.builder()
                .name("Branch Credit Customer")
                .phone("0771000099")
                .dueAmount(300.0)
                .active(true)
                .build());

        saveCreditOrder(fixture.mainBranch().getId(), fixture.admin().getId(), customer.getId(), 100.0, "RPT-CREDIT-1");
        saveCreditOrder(secondBranch.path("id").asLong(), fixture.admin().getId(), customer.getId(), 200.0, "RPT-CREDIT-2");

        JsonNode mainDue = getJson(
                "/reports/credit-due?branchId=" + fixture.mainBranch().getId(), tenantId, token);
        assertEquals(1, mainDue.size());
        assertEquals(100.0, mainDue.get(0).path("dueAmount").asDouble(), 0.001);

        JsonNode allDue = getJson("/reports/credit-due", tenantId, token);
        assertEquals(300.0, allDue.get(0).path("dueAmount").asDouble(), 0.001);

        JsonNode mainAging = getJson(
                "/api/reports/v2/credit-aging?branchId=" + fixture.mainBranch().getId(), tenantId, token);
        assertEquals(1, mainAging.size());
        assertEquals(100.0, mainAging.get(0).path("totalDue").asDouble(), 0.001);
    }

    @Test
    void shiftSummaryUsesPersistedOrderFields() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-shift"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        LocalDateTime openedAt = LocalDate.now().atStartOfDay();

        CashShift shiftEntity = cashShiftRepository.save(CashShift.builder()
                .branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.cashier().getId())
                .status(ShiftStatus.CLOSED)
                .openingCash(500.0)
                .totalExpenses(20.0)
                .totalCashDrops(50.0)
                .cashSales(120.0)
                .countedCash(550.0)
                .expectedCash(550.0)
                .cashDifference(-10.0)
                .openedAt(openedAt)
                .closedAt(LocalDateTime.now())
                .build());

        Order orderEntity = orderRepository.save(Order.builder()
                .invoiceNo("RPT-SHIFT-1")
                .branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.cashier().getId())
                .orderType(OrderType.CASH)
                .paymentMethod("CASH")
                .saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED)
                .subTotal(130.0)
                .billDiscount(10.0)
                .grandTotal(120.0)
                .paidAmount(150.0)
                .dueAmount(0.0)
                .salePaidAmount(120.0)
                .saleDueAmount(0.0)
                .createdAt(LocalDateTime.now().minusMinutes(30))
                .build());

        shiftEntity.setOpenedAt(orderEntity.getCreatedAt().minusMinutes(30));
        shiftEntity.setClosedAt(orderEntity.getCreatedAt().plusMinutes(30));
        cashShiftRepository.save(shiftEntity);

        String today = LocalDate.now().toString();
        JsonNode page = getJson(
                "/api/reports/v2/shift-summary?branchId=" + fixture.mainBranch().getId()
                        + "&from=" + today + "&to=" + today,
                tenantId,
                token
        );

        assertFalse(page.path("items").isEmpty());
        JsonNode shift = page.path("items").get(0);
        assertEquals(120.0, shift.path("cashSales").asDouble(), 0.001);
        assertEquals(10.0, shift.path("totalDiscount").asDouble(), 0.001);
        assertEquals(1, shift.path("orderCount").asLong());
        assertEquals(550.0, shift.path("expectedClosingCash").asDouble(), 0.001);
        assertEquals(-10.0, shift.path("cashDifference").asDouble(), 0.001);

        JsonNode allTimeDefault = getJson("/api/reports/v2/shift-summary", tenantId, token);
        assertFalse(allTimeDefault.path("items").isEmpty());
    }

    @Test
    void ownerCommandCenterComparesEqualLengthPeriods() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-owner"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        LocalDate today = LocalDate.now();

        saveCashOrder(fixture.mainBranch().getId(), fixture.admin().getId(), 300.0,
                "RPT-OWNER-CURRENT", today.atTime(10, 0));
        saveCashOrder(fixture.mainBranch().getId(), fixture.admin().getId(), 100.0,
                "RPT-OWNER-PREVIOUS", today.minusDays(1).atTime(10, 0));

        JsonNode response = getJson(
                "/reports/owner-command-center?branchId=" + fixture.mainBranch().getId()
                        + "&from=" + today + "&to=" + today,
                tenantId,
                token
        );

        assertEquals(today.toString(), response.path("currentPeriod").path("from").asText());
        assertEquals(today.minusDays(1).toString(), response.path("comparisonPeriod").path("from").asText());
        assertEquals(300.0, response.path("current").path("totalSales").asDouble(), 0.001);
        assertEquals(100.0, response.path("comparison").path("totalSales").asDouble(), 0.001);
        assertEquals(300.0, response.path("current").path("averageOrderValue").asDouble(), 0.001);
        assertEquals(0.0, response.path("risks").path("totalReceivables").asDouble(), 0.001);
        assertEquals(0, response.path("risks").path("lowStockItems").asLong());
        assertEquals(0.0, response.path("risks").path("saleReturnAmount").asDouble(), 0.001);
    }

    @Test
    void inventoryValuationUsesBaseQuantityAndWeightedBatchCost() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-valuation"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Valuation Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Valuation Subcategory");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);
        Item item = itemRepository.save(Item.builder()
                .barcode("RPT-VAL-001")
                .name("Valuation Item")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(12))
                .sellingPrice(BigDecimal.valueOf(30))
                .reorderLevel(1)
                .active(true)
                .posVisible(true)
                .build());

        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch()).item(item).batchCode("RPT-VAL-B1")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(10)).sellingPrice(BigDecimal.valueOf(30))
                .quantity(2000).originalQuantity(2000).build());
        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch()).item(item).batchCode("RPT-VAL-B2")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(20)).sellingPrice(BigDecimal.valueOf(30))
                .quantity(1000).originalQuantity(1000).build());

        JsonNode response = getJson(
                "/api/reports/v2/inventory-valuation?branchId=" + fixture.mainBranch().getId(),
                tenantId,
                token
        );

        JsonNode row = response.path("items").get(0);
        assertEquals(3.0, row.path("qtyOnHand").asDouble(), 0.001);
        assertEquals(13.333, row.path("costPrice").asDouble(), 0.001);
        assertEquals(40.0, row.path("stockValue").asDouble(), 0.001);
        assertEquals(90.0, row.path("potentialRevenue").asDouble(), 0.001);
        assertEquals(50.0, row.path("potentialProfit").asDouble(), 0.001);
        assertEquals(40.0, response.path("totalStockValue").asDouble(), 0.001);
    }

    @Test
    void cashFlowReconcilesCashMovementsAndRespectsBranch() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-cash-flow"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        LocalDate today = LocalDate.now();

        JsonNode secondBranch = postJson("/branches", tenantId, token, """
                {"code":"CASH2","name":"Cash Branch Two","address":"Second","phone":"0772000002"}
                """);
        Customer customer = customerRepository.save(Customer.builder()
                .name("Cash Flow Customer").phone("0772000099").dueAmount(0.0).active(true).build());

        saveCashOrder(fixture.mainBranch().getId(), fixture.admin().getId(), 300.0,
                "RPT-CF-CURRENT", today.atTime(9, 0));
        saveCashOrder(secondBranch.path("id").asLong(), fixture.admin().getId(), 900.0,
                "RPT-CF-OTHER", today.atTime(9, 30));

        creditPaymentRepository.save(CreditPayment.builder()
                .customerId(customer.getId()).branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.admin().getId()).amount(100.0)
                .paymentMethod("CASH").paidAt(today.atTime(10, 0)).build());
        creditPaymentRepository.save(CreditPayment.builder()
                .customerId(customer.getId()).branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.admin().getId()).amount(500.0)
                .paymentMethod("BANK").paidAt(today.atTime(10, 30)).build());
        expenseRepository.save(Expense.builder()
                .branchId(fixture.mainBranch().getId()).cashierUserId(fixture.admin().getId())
                .category("OPERATIONS").countInProfitReport(true).amount(40.0)
                .description("Cash flow test expense").createdAt(today.atTime(11, 0)).build());

        JsonNode response = getJson(
                "/api/reports/v2/cash-flow?branchId=" + fixture.mainBranch().getId()
                        + "&from=" + today + "&to=" + today,
                tenantId, token);

        assertEquals(300.0, response.path("cashSales").asDouble(), 0.001);
        assertEquals(100.0, response.path("creditCollections").asDouble(), 0.001);
        assertEquals(400.0, response.path("totalInflows").asDouble(), 0.001);
        assertEquals(40.0, response.path("expenses").asDouble(), 0.001);
        assertEquals(40.0, response.path("totalOutflows").asDouble(), 0.001);
        assertEquals(360.0, response.path("netCashMovement").asDouble(), 0.001);
        assertEquals(1, response.path("dailyMovements").size());
        assertEquals(360.0, response.path("dailyMovements").get(0).path("netMovement").asDouble(), 0.001);
    }

    @Test
    void profitAndLossReconcilesDiscountsCostsExpensesAndBranchScope() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-pnl"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        LocalDate today = LocalDate.now();
        JsonNode secondBranch = postJson("/branches", tenantId, token, """
                {"code":"PNL2","name":"PnL Branch Two","address":"Second","phone":"0773000002"}
                """);

        Order currentOrder = saveProfitOrder(fixture.mainBranch().getId(), fixture.admin().getId(),
                "RPT-PNL-CURRENT", today.atTime(9, 0), 200.0, 20.0);
        saveProfitLine(currentOrder.getId(), 1L, "Costed Item", 200.0, 80.0);
        saveProfitLine(currentOrder.getId(), 2L, "Missing Cost Item", 50.0, 0.0);

        Order previousOrder = saveProfitOrder(fixture.mainBranch().getId(), fixture.admin().getId(),
                "RPT-PNL-PREVIOUS", today.minusDays(1).atTime(9, 0), 100.0, 0.0);
        saveProfitLine(previousOrder.getId(), 3L, "Previous Item", 100.0, 50.0);

        Order otherBranchOrder = saveProfitOrder(secondBranch.path("id").asLong(), fixture.admin().getId(),
                "RPT-PNL-OTHER", today.atTime(10, 0), 900.0, 0.0);
        saveProfitLine(otherBranchOrder.getId(), 4L, "Other Branch Item", 900.0, 400.0);

        expenseRepository.save(Expense.builder()
                .branchId(fixture.mainBranch().getId()).cashierUserId(fixture.admin().getId())
                .category("OPERATIONS").countInProfitReport(true).amount(30.0)
                .description("PnL expense").createdAt(today.atTime(11, 0)).build());
        expenseRepository.save(Expense.builder()
                .branchId(fixture.mainBranch().getId()).cashierUserId(fixture.admin().getId())
                .category("RECOVERABLE").countInProfitReport(false).amount(70.0)
                .description("Excluded expense").createdAt(today.atTime(12, 0)).build());

        JsonNode response = getJson(
                "/api/reports/v2/profit-loss?branchId=" + fixture.mainBranch().getId()
                        + "&from=" + today + "&to=" + today,
                tenantId, token);
        JsonNode current = response.path("current");
        JsonNode comparison = response.path("comparison");

        assertEquals(250.0, current.path("itemRevenue").asDouble(), 0.001);
        assertEquals(20.0, current.path("billDiscounts").asDouble(), 0.001);
        assertEquals(230.0, current.path("netRevenue").asDouble(), 0.001);
        assertEquals(80.0, current.path("costOfGoodsSold").asDouble(), 0.001);
        assertEquals(150.0, current.path("grossProfit").asDouble(), 0.001);
        assertEquals(30.0, current.path("operatingExpenses").asDouble(), 0.001);
        assertEquals(120.0, current.path("netProfit").asDouble(), 0.001);
        assertEquals(2, current.path("revenueLineCount").asLong());
        assertEquals(1, current.path("missingCostLineCount").asLong());
        assertEquals(50.0, current.path("costCoveragePercent").asDouble(), 0.001);
        assertEquals(100.0, comparison.path("netRevenue").asDouble(), 0.001);
        assertEquals(50.0, comparison.path("netProfit").asDouble(), 0.001);
    }

    @Test
    void creditAgingAssignsBucketsPriorityAndCreditLimitWithinBranch() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-aging"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        JsonNode secondBranch = postJson("/branches", tenantId, token, """
                {"code":"AGING2","name":"Aging Branch Two","address":"Second","phone":"0774000002"}
                """);
        Customer customer = customerRepository.save(Customer.builder()
                .name("Aging Customer").phone("0774000099").dueAmount(500.0)
                .creditLimit(400.0).active(true).build());
        LocalDateTime now = LocalDateTime.now();

        saveAgingOrder(fixture.mainBranch().getId(), fixture.admin().getId(), customer.getId(), 50, "RPT-AGE-010", now.minusDays(10));
        saveAgingOrder(fixture.mainBranch().getId(), fixture.admin().getId(), customer.getId(), 100, "RPT-AGE-040", now.minusDays(40));
        saveAgingOrder(fixture.mainBranch().getId(), fixture.admin().getId(), customer.getId(), 150, "RPT-AGE-070", now.minusDays(70));
        saveAgingOrder(fixture.mainBranch().getId(), fixture.admin().getId(), customer.getId(), 200, "RPT-AGE-100", now.minusDays(100));
        saveAgingOrder(secondBranch.path("id").asLong(), fixture.admin().getId(), customer.getId(), 900, "RPT-AGE-OTHER", now.minusDays(120));

        JsonNode response = getJson(
                "/api/reports/v2/credit-aging?branchId=" + fixture.mainBranch().getId(), tenantId, token);
        assertEquals(1, response.size());
        JsonNode aging = response.get(0);
        assertEquals(500.0, aging.path("totalDue").asDouble(), 0.001);
        assertEquals(50.0, aging.path("bucket0to30").asDouble(), 0.001);
        assertEquals(100.0, aging.path("bucket31to60").asDouble(), 0.001);
        assertEquals(150.0, aging.path("bucket61to90").asDouble(), 0.001);
        assertEquals(200.0, aging.path("bucket91plus").asDouble(), 0.001);
        assertEquals(4, aging.path("unpaidInvoiceCount").asLong());
        assertEquals("RPT-AGE-100", aging.path("oldestInvoiceNo").asText());
        assertEquals("CRITICAL", aging.path("priority").asText());
        assertTrue(aging.path("overCreditLimit").asBoolean());
    }

    @Test
    void supplierPayablesAgingAssignsBucketsAndRespectsGrnBranch() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-payables"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        JsonNode secondBranchJson = postJson("/branches", tenantId, token, """
                {"code":"PAY2","name":"Payables Branch Two","address":"Second","phone":"0775000002"}
                """);
        Branch secondBranch = branchRepository.findById(secondBranchJson.path("id").asLong()).orElseThrow();
        Supplier supplier = new Supplier();
        supplier.setName("Aging Supplier"); supplier.setPhone("0775000099"); supplier.setEmail("aging@supplier.test");
        supplier.setDueAmount(BigDecimal.valueOf(1400)); supplier = supplierRepository.save(supplier);
        LocalDateTime now = LocalDateTime.now();

        savePayablePurchase(supplier, fixture.mainBranch(), "PAY-010", 50, now.minusDays(10));
        savePayablePurchase(supplier, fixture.mainBranch(), "PAY-040", 100, now.minusDays(40));
        savePayablePurchase(supplier, fixture.mainBranch(), "PAY-070", 150, now.minusDays(70));
        savePayablePurchase(supplier, fixture.mainBranch(), "PAY-100", 200, now.minusDays(100));
        savePayablePurchase(supplier, secondBranch, "PAY-OTHER", 900, now.minusDays(120));

        JsonNode response = getJson(
                "/api/reports/v2/supplier-payables-aging?branchId=" + fixture.mainBranch().getId(), tenantId, token);
        assertEquals(1, response.size());
        JsonNode aging = response.get(0);
        assertEquals(500.0, aging.path("totalDue").asDouble(), 0.001);
        assertEquals(50.0, aging.path("bucket0to30").asDouble(), 0.001);
        assertEquals(100.0, aging.path("bucket31to60").asDouble(), 0.001);
        assertEquals(150.0, aging.path("bucket61to90").asDouble(), 0.001);
        assertEquals(200.0, aging.path("bucket91plus").asDouble(), 0.001);
        assertEquals(4, aging.path("unpaidPurchaseCount").asLong());
        assertEquals("PAY-100", aging.path("oldestInvoiceNo").asText());
        assertEquals("CRITICAL", aging.path("priority").asText());
    }

    @Test
    void stockMovementReconcilesProcessingAndDisplayUnitsWithinBranch() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-movement"), 1);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        Category category = new Category(); category.setName("Movement Category"); category = categoryRepository.save(category);
        SubCategory sub = new SubCategory(); sub.setName("Movement Sub"); sub.setCategory(category); sub = subCategoryRepository.save(sub);
        Item source = saveMovementItem("MOVE-SRC", "Movement Source", sub);
        Item output = saveMovementItem("MOVE-OUT", "Movement Output", sub);
        StockBatch sourceBatch = stockBatchRepository.save(StockBatch.builder().branch(fixture.mainBranch()).item(source)
                .batchCode("MOVE-BATCH-SRC").sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.TEN).sellingPrice(BigDecimal.valueOf(20)).quantity(3000).originalQuantity(5000).build());
        stockBatchRepository.save(StockBatch.builder().branch(fixture.mainBranch()).item(output)
                .batchCode("MOVE-BATCH-OUT").sourceType(StockBatchSourceType.PROCESSING)
                .costPrice(BigDecimal.TEN).sellingPrice(BigDecimal.valueOf(20)).quantity(1000).originalQuantity(1000).build());
        LocalDateTime processedAt = LocalDateTime.now().minusHours(1);
        StockProcessing processing = stockProcessingRepository.save(StockProcessing.builder()
                .branch(fixture.mainBranch()).sourceItem(source).sourceBatchId(sourceBatch.getId()).sourceBatchCode(sourceBatch.getBatchCode())
                .sourceQty(2000).sourceDisplayQty(BigDecimal.valueOf(2)).sourceQtyUnit(MeasurementUnit.PCS)
                .sourceCost(BigDecimal.valueOf(20)).processedByUserId(fixture.admin().getId()).processedAt(processedAt).build());
        stockProcessingOutputRepository.save(StockProcessingOutput.builder().processingId(processing.getId()).outputItem(output)
                .quantity(1000).displayQty(BigDecimal.ONE).qtyUnit(MeasurementUnit.PCS).waste(false)
                .allocatedCost(BigDecimal.valueOf(20)).build());

        LocalDate today = LocalDate.now();
        JsonNode page = getJson("/api/reports/v2/stock-movement?branchId=" + fixture.mainBranch().getId()
                + "&from=" + today + "&to=" + today + "&page=0&size=100", tenantId, token);
        JsonNode sourceRow = findByItemId(page.path("items"), source.getId());
        JsonNode outputRow = findByItemId(page.path("items"), output.getId());
        assertEquals(5.0, sourceRow.path("openingStock").asDouble(), 0.001);
        assertEquals(2.0, sourceRow.path("processingOut").asDouble(), 0.001);
        assertEquals(3.0, sourceRow.path("closingStock").asDouble(), 0.001);
        assertEquals(0.0, outputRow.path("openingStock").asDouble(), 0.001);
        assertEquals(1.0, outputRow.path("processingIn").asDouble(), 0.001);
        assertEquals(1.0, outputRow.path("closingStock").asDouble(), 0.001);
    }

    @Test
    void stockHealthScopesBranchInventoryAndCalculatesReorderCover() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-stock-health"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode secondBranchJson = postJson("/branches", tenantId, token, """
                {
                  "code": "STK2",
                  "name": "Stock Health Branch Two",
                  "address": "Second address",
                  "phone": "0777000002"
                }
                """);
        Branch secondBranch = branchRepository.findById(secondBranchJson.path("id").asLong()).orElseThrow();

        Category category = new Category();
        category.setName("Stock Health Category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName("Stock Health Subcategory");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Item soldItem = itemRepository.save(Item.builder()
                .barcode("STK-HLTH-SOLD")
                .name("Sold Item")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(10))
                .sellingPrice(BigDecimal.valueOf(15))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());
        Item deadStockItem = itemRepository.save(Item.builder()
                .barcode("STK-HLTH-DEAD")
                .name("Dead Stock Item")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(10))
                .sellingPrice(BigDecimal.valueOf(20))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());
        Item zeroStockItem = itemRepository.save(Item.builder()
                .barcode("STK-HLTH-ZERO")
                .name("Zero Stock Item")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(8))
                .sellingPrice(BigDecimal.valueOf(12))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());
        Item otherBranchItem = itemRepository.save(Item.builder()
                .barcode("STK-HLTH-OTHER")
                .name("Other Branch Item")
                .subCategory(subCategory)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .costPrice(BigDecimal.valueOf(7))
                .sellingPrice(BigDecimal.valueOf(11))
                .reorderLevel(0)
                .active(true)
                .posVisible(true)
                .build());

        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch())
                .item(soldItem)
                .batchCode("STK-HLTH-SOLD-B1")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(10))
                .sellingPrice(BigDecimal.valueOf(15))
                .quantity(1000)
                .originalQuantity(1000)
                .build());
        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch())
                .item(deadStockItem)
                .batchCode("STK-HLTH-DEAD-B1")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(10))
                .sellingPrice(BigDecimal.valueOf(20))
                .quantity(5000)
                .originalQuantity(5000)
                .build());
        stockBatchRepository.save(StockBatch.builder()
                .branch(fixture.mainBranch())
                .item(zeroStockItem)
                .batchCode("STK-HLTH-ZERO-B1")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(8))
                .sellingPrice(BigDecimal.valueOf(12))
                .quantity(0)
                .originalQuantity(1000)
                .build());
        stockBatchRepository.save(StockBatch.builder()
                .branch(secondBranch)
                .item(otherBranchItem)
                .batchCode("STK-HLTH-OTHER-B1")
                .sourceType(StockBatchSourceType.PURCHASE)
                .costPrice(BigDecimal.valueOf(7))
                .sellingPrice(BigDecimal.valueOf(11))
                .quantity(4000)
                .originalQuantity(4000)
                .build());

        LocalDateTime now = LocalDateTime.now();
        Order soldOrder = orderRepository.save(Order.builder()
                .invoiceNo("STK-HLTH-SOLD-1")
                .branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.admin().getId())
                .orderType(OrderType.CASH)
                .paymentMethod("CASH")
                .saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED)
                .subTotal(270.0)
                .billDiscount(0.0)
                .grandTotal(270.0)
                .paidAmount(270.0)
                .dueAmount(0.0)
                .salePaidAmount(270.0)
                .saleDueAmount(0.0)
                .createdAt(now.minusDays(1))
                .build());
        orderItemRepository.save(OrderItem.builder()
                .orderId(soldOrder.getId())
                .itemId(soldItem.getId())
                .barcode(soldItem.getBarcode())
                .itemName(soldItem.getName())
                .itemType(ItemType.NORMAL)
                .qty(18000)
                .displayQty(BigDecimal.valueOf(18))
                .qtyUnit(MeasurementUnit.PCS)
                .costPrice(10.0)
                .unitPrice(15.0)
                .discountType(DiscountType.NONE)
                .discountValue(0.0)
                .finalUnitPrice(15.0)
                .lineCost(180.0)
                .lineTotal(270.0)
                .build());

        Order deadStockOrder = orderRepository.save(Order.builder()
                .invoiceNo("STK-HLTH-DEAD-1")
                .branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.admin().getId())
                .orderType(OrderType.CASH)
                .paymentMethod("CASH")
                .saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED)
                .subTotal(200.0)
                .billDiscount(0.0)
                .grandTotal(200.0)
                .paidAmount(200.0)
                .dueAmount(0.0)
                .salePaidAmount(200.0)
                .saleDueAmount(0.0)
                .createdAt(now.minusDays(91))
                .build());
        orderItemRepository.save(OrderItem.builder()
                .orderId(deadStockOrder.getId())
                .itemId(deadStockItem.getId())
                .barcode(deadStockItem.getBarcode())
                .itemName(deadStockItem.getName())
                .itemType(ItemType.NORMAL)
                .qty(1000)
                .displayQty(BigDecimal.ONE)
                .qtyUnit(MeasurementUnit.PCS)
                .costPrice(10.0)
                .unitPrice(20.0)
                .discountType(DiscountType.NONE)
                .discountValue(0.0)
                .finalUnitPrice(20.0)
                .lineCost(10.0)
                .lineTotal(20.0)
                .build());

        JsonNode response = getJson(
                "/api/reports/v2/stock-health?branchId=" + fixture.mainBranch().getId() + "&targetCoverDays=14",
                tenantId,
                token
        );

        assertEquals(90, response.path("salesWindowDays").asInt());
        assertEquals(14, response.path("targetCoverDays").asInt());
        assertEquals(3, response.path("totalItems").asLong());
        assertEquals(1, response.path("outOfStockItems").asLong());
        assertEquals(0, response.path("negativeStockItems").asLong());
        assertEquals(0, response.path("belowReorderItems").asLong());
        assertEquals(1, response.path("deadStockItems").asLong());
        assertEquals(50.0, response.path("deadStockValue").asDouble(), 0.001);
        assertEquals(18.0, response.path("estimatedReorderCost").asDouble(), 0.001);

        JsonNode soldRow = findByItemId(response.path("items"), soldItem.getId());
        assertEquals("HEALTHY", soldRow.path("status").asText());
        assertEquals(1.0, soldRow.path("qtyOnHand").asDouble(), 0.001);
        assertEquals(18.0, soldRow.path("soldLast90Days").asDouble(), 0.001);
        assertEquals(0.2, soldRow.path("averageDailySales").asDouble(), 0.001);
        assertEquals(5.0, soldRow.path("estimatedDaysOfStock").asDouble(), 0.001);
        assertEquals(1.8, soldRow.path("suggestedReorderQty").asDouble(), 0.001);
        assertEquals(18.0, soldRow.path("estimatedReorderCost").asDouble(), 0.001);

        JsonNode deadRow = findByItemId(response.path("items"), deadStockItem.getId());
        assertEquals("DEAD_STOCK", deadRow.path("status").asText());
        assertEquals(5.0, deadRow.path("qtyOnHand").asDouble(), 0.001);
        assertEquals(0.0, deadRow.path("soldLast90Days").asDouble(), 0.001);
        assertEquals(0.0, deadRow.path("suggestedReorderQty").asDouble(), 0.001);
        assertEquals(50.0, deadRow.path("stockValue").asDouble(), 0.001);
        assertEquals(0.0, deadRow.path("estimatedReorderCost").asDouble(), 0.001);

        JsonNode zeroRow = findByItemId(response.path("items"), zeroStockItem.getId());
        assertEquals("OUT_OF_STOCK", zeroRow.path("status").asText());
        assertEquals(0.0, zeroRow.path("qtyOnHand").asDouble(), 0.001);
        assertEquals(0.0, zeroRow.path("suggestedReorderQty").asDouble(), 0.001);
        assertEquals(0.0, zeroRow.path("estimatedReorderCost").asDouble(), 0.001);

        for (JsonNode row : response.path("items")) {
            assertFalse(row.path("itemId").asLong() == otherBranchItem.getId(), "Other-branch-only item leaked into report");
        }
    }

    @Test
    void grnReportSeparatesReceivedValueFromUniquePurchaseBalancesAndReturns() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-grn"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        JsonNode secondBranchJson = postJson("/branches", tenantId, token, """
                {"code":"GRN2","name":"GRN Branch Two","address":"Second","phone":"0776000002"}
                """);
        Branch secondBranch = branchRepository.findById(secondBranchJson.path("id").asLong()).orElseThrow();
        Supplier supplier = new Supplier(); supplier.setName("GRN Supplier"); supplier.setPhone("0776000099");
        supplier.setEmail("grn@supplier.test"); supplier.setDueAmount(BigDecimal.valueOf(100));
        supplier = supplierRepository.save(supplier);
        LocalDateTime receivedAt = LocalDateTime.now().minusHours(2);
        Purchase purchase = purchaseRepository.save(Purchase.builder().invoiceNo("PUR-GRN-1").supplier(supplier)
                .createdAt(receivedAt).grandTotal(BigDecimal.valueOf(300)).discountAmount(BigDecimal.ZERO)
                .paidAmount(BigDecimal.valueOf(200)).dueAmount(BigDecimal.valueOf(100)).paymentMethod("PARTIAL")
                .cashSource(CashSource.BRANCH_CASH).cashSourceAmount(BigDecimal.valueOf(200))
                .cashSourceBranchId(fixture.mainBranch().getId()).status(PurchaseStatus.COMPLETED).build());
        GRN first = grnRepository.save(GRN.builder().grnNo("GRN-ONE").supplier(supplier).branch(fixture.mainBranch())
                .purchase(purchase).totalAmount(BigDecimal.valueOf(120)).paidAmount(BigDecimal.ZERO)
                .receivedAt(receivedAt).createdByUserId(fixture.admin().getId()).build());
        grnRepository.save(GRN.builder().grnNo("GRN-TWO").supplier(supplier).branch(fixture.mainBranch())
                .purchase(purchase).totalAmount(BigDecimal.valueOf(180)).paidAmount(BigDecimal.ZERO)
                .receivedAt(receivedAt.plusMinutes(10)).createdByUserId(fixture.admin().getId()).build());
        grnRepository.save(GRN.builder().grnNo("GRN-OTHER").supplier(supplier).branch(secondBranch)
                .purchase(purchase).totalAmount(BigDecimal.valueOf(900)).paidAmount(BigDecimal.ZERO)
                .receivedAt(receivedAt).createdByUserId(fixture.admin().getId()).build());
        purchaseReturnRepository.save(PurchaseReturn.builder().debitNoteNo("DBN-GRN-1").purchaseId(purchase.getId())
                .purchaseInvoiceNo(purchase.getInvoiceNo()).supplierId(supplier.getId()).grnId(first.getId())
                .branchId(fixture.mainBranch().getId()).processedByUserId(fixture.admin().getId())
                .status(ReturnStatus.COMPLETED).totalReturnAmount(BigDecimal.valueOf(20)).reason("Damaged")
                .createdAt(receivedAt.plusMinutes(30)).build());

        LocalDate today = LocalDate.now();
        JsonNode summary = getJson("/api/reports/v2/grn?branchId=" + fixture.mainBranch().getId()
                + "&from=" + today + "&to=" + today + "&page=0&size=10", tenantId, token);
        assertEquals(2, summary.path("page").path("totalElements").asLong());
        assertEquals(300.0, summary.path("totalAmount").asDouble(), 0.001);
        assertEquals(200.0, summary.path("totalPaid").asDouble(), 0.001);
        assertEquals(100.0, summary.path("totalDue").asDouble(), 0.001);
        assertEquals(20.0, summary.path("totalReturns").asDouble(), 0.001);
        assertEquals(280.0, summary.path("netReceivedAmount").asDouble(), 0.001);
        assertEquals(1, summary.path("uniquePurchaseCount").asLong());
        JsonNode returnedRow = summary.path("page").path("items").get(1);
        assertEquals("PUR-GRN-1", returnedRow.path("purchaseInvoiceNo").asText());
        assertEquals(100.0, returnedRow.path("purchaseDueAmount").asDouble(), 0.001);
    }

    @Test
    void customerBehaviorGroupsNewAndReturningAndInactiveBucket() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-customer-behavior"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode secondBranchJson = postJson("/branches", tenantId, token, """
                {"code":"CB2","name":"Customer Behavior Branch Two","address":"Second","phone":"0778000002"}
                """);
        Long secondBranchId = secondBranchJson.path("id").asLong();

        LocalDate periodFrom = LocalDate.of(2024, 1, 1);
        LocalDate periodTo = LocalDate.of(2024, 4, 30);

        Customer returningCustomer = customerRepository.save(Customer.builder()
                .name("Returning Customer").phone("0778000001").dueAmount(10.0).active(true).build());
        Customer newCustomer = customerRepository.save(Customer.builder()
                .name("New Customer").phone("0778000002").dueAmount(5.0).active(true).build());
        Customer otherBranchCustomer = customerRepository.save(Customer.builder()
                .name("Other Branch Customer").phone("0778000003").dueAmount(0.0).active(true).build());

        saveBehaviorOrder(fixture.mainBranch().getId(), fixture.admin().getId(),
                returningCustomer.getId(), 100.0, "CB-PRIOR", LocalDateTime.of(2023, 12, 15, 10, 0));
        saveBehaviorOrder(fixture.mainBranch().getId(), fixture.admin().getId(),
                returningCustomer.getId(), 200.0, "CB-RETURN", LocalDateTime.of(2024, 2, 15, 10, 0));
        saveBehaviorOrder(fixture.mainBranch().getId(), fixture.admin().getId(),
                newCustomer.getId(), 200.0, "CB-NEW", LocalDateTime.of(2024, 3, 15, 10, 0));
        saveBehaviorOrder(secondBranchId, fixture.admin().getId(),
                otherBranchCustomer.getId(), 400.0, "CB-OTHER", LocalDateTime.of(2024, 3, 20, 10, 0));
        orderRepository.save(Order.builder()
                .invoiceNo("CB-ANON")
                .branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.admin().getId())
                .orderType(OrderType.CASH)
                .paymentMethod("CASH")
                .saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED)
                .subTotal(999.0)
                .billDiscount(0.0)
                .grandTotal(999.0)
                .paidAmount(999.0)
                .dueAmount(0.0)
                .salePaidAmount(999.0)
                .saleDueAmount(0.0)
                .createdAt(LocalDateTime.of(2024, 3, 12, 10, 0))
                .build());

        JsonNode response = getJson(
                "/api/reports/v2/customer-behavior?branchId=" + fixture.mainBranch().getId()
                        + "&from=" + periodFrom + "&to=" + periodTo,
                tenantId, token);

        assertEquals(2L, response.path("activeCustomersInPeriod").asLong());
        assertEquals(1L, response.path("newCustomers").asLong());
        assertEquals(1L, response.path("returningCustomers").asLong());
        assertEquals(50.0, response.path("repeatRatePercent").asDouble(), 0.001);
        assertEquals(2L, response.path("periodOrders").asLong());
        assertEquals(1.0, response.path("averageOrdersPerActiveCustomer").asDouble(), 0.001);

        boolean sawReturning = false;
        boolean sawNew = false;
        for (JsonNode row : response.path("customers")) {
            String name = row.path("customerName").asText();
            if ("Returning Customer".equals(name)) {
                sawReturning = true;
                assertEquals(1L, row.path("periodOrderCount").asLong());
                assertEquals(2L, row.path("lifetimeOrderCount").asLong());
                assertEquals(200.0, row.path("periodSpend").asDouble(), 0.001);
                assertEquals(300.0, row.path("lifetimeSpend").asDouble(), 0.001);
                assertEquals(10.0, row.path("currentDue").asDouble(), 0.001);
                assertEquals("INACTIVE_61_90", row.path("inactivityBucket").asText());
                assertEquals(75L, row.path("daysSinceLastPurchase").asLong());
                assertFalse(row.path("newCustomer").asBoolean());
            } else if ("New Customer".equals(name)) {
                sawNew = true;
                assertEquals(1L, row.path("periodOrderCount").asLong());
                assertEquals(1L, row.path("lifetimeOrderCount").asLong());
                assertEquals(200.0, row.path("periodSpend").asDouble(), 0.001);
                assertEquals(200.0, row.path("lifetimeSpend").asDouble(), 0.001);
                assertEquals(5.0, row.path("currentDue").asDouble(), 0.001);
                assertTrue(row.path("newCustomer").asBoolean());
            }
            assertFalse("Other Branch Customer".equals(name), "Other-branch customer leaked into report");
        }
        assertTrue(sawReturning, "Returning customer row missing");
        assertTrue(sawNew, "New customer row missing");
    }

    @Test
    void branchComparisonSeparatesSalesAndProfitReportExpenses() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("report-branch-comparison"), 2);
        String tenantId = fixture.tenantId();
        String token = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        JsonNode secondBranch = postJson("/branches", tenantId, token, """
                {"code":"CMP2","name":"Comparison Branch Two","address":"Second","phone":"0777000002"}
                """);
        LocalDate today = LocalDate.now();
        saveCashOrder(fixture.mainBranch().getId(), fixture.admin().getId(), 300.0, "RPT-CMP-1", today.atTime(9, 0));
        saveCashOrder(secondBranch.path("id").asLong(), fixture.admin().getId(), 100.0, "RPT-CMP-2", today.atTime(10, 0));
        expenseRepository.save(Expense.builder().branchId(fixture.mainBranch().getId()).cashierUserId(fixture.admin().getId())
                .category("OPERATIONS").countInProfitReport(true).amount(40.0).description("Included")
                .createdAt(today.atTime(11, 0)).build());
        expenseRepository.save(Expense.builder().branchId(fixture.mainBranch().getId()).cashierUserId(fixture.admin().getId())
                .category("RECOVERABLE").countInProfitReport(false).amount(90.0).description("Excluded")
                .createdAt(today.atTime(12, 0)).build());

        JsonNode rows = getJson("/api/reports/v2/branch-comparison?from=" + today + "&to=" + today, tenantId, token);
        assertEquals(2, rows.size());
        assertEquals(fixture.mainBranch().getId().longValue(), rows.get(0).path("branchId").asLong());
        assertEquals(300.0, rows.get(0).path("totalSales").asDouble(), 0.001);
        assertEquals(1, rows.get(0).path("orderCount").asLong());
        assertEquals(40.0, rows.get(0).path("operatingExpenses").asDouble(), 0.001);
        assertEquals(100.0, rows.get(1).path("totalSales").asDouble(), 0.001);
    }

    private void saveBehaviorOrder(Long branchId, Long cashierId, Long customerId, double total,
                                   String invoiceNo, LocalDateTime createdAt) {
        orderRepository.save(Order.builder()
                .invoiceNo(invoiceNo)
                .branchId(branchId)
                .cashierUserId(cashierId)
                .customerId(customerId)
                .orderType(OrderType.CASH)
                .paymentMethod("CASH")
                .saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED)
                .subTotal(total)
                .billDiscount(0.0)
                .grandTotal(total)
                .paidAmount(total)
                .dueAmount(0.0)
                .salePaidAmount(total)
                .saleDueAmount(0.0)
                .createdAt(createdAt)
                .build());
    }

    private void saveCreditOrder(Long branchId, Long cashierId, Long customerId, double due, String invoiceNo) {
        orderRepository.save(Order.builder()
                .invoiceNo(invoiceNo)
                .branchId(branchId)
                .cashierUserId(cashierId)
                .customerId(customerId)
                .orderType(OrderType.CREDIT)
                .paymentMethod("CREDIT")
                .saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED)
                .subTotal(due)
                .billDiscount(0.0)
                .grandTotal(due)
                .paidAmount(0.0)
                .dueAmount(due)
                .salePaidAmount(0.0)
                .saleDueAmount(due)
                .createdAt(LocalDateTime.now().minusDays(5))
                .build());
    }

    private void saveCashOrder(Long branchId, Long cashierId, double total, String invoiceNo, LocalDateTime createdAt) {
        orderRepository.save(Order.builder()
                .invoiceNo(invoiceNo)
                .branchId(branchId)
                .cashierUserId(cashierId)
                .orderType(OrderType.CASH)
                .paymentMethod("CASH")
                .saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED)
                .subTotal(total)
                .billDiscount(0.0)
                .grandTotal(total)
                .paidAmount(total)
                .dueAmount(0.0)
                .salePaidAmount(total)
                .saleDueAmount(0.0)
                .createdAt(createdAt)
                .build());
    }

    private Order saveProfitOrder(Long branchId, Long cashierId, String invoiceNo,
                                  LocalDateTime createdAt, double subTotal, double billDiscount) {
        return orderRepository.save(Order.builder()
                .invoiceNo(invoiceNo).branchId(branchId).cashierUserId(cashierId)
                .orderType(OrderType.CASH).paymentMethod("CASH").saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED).subTotal(subTotal).billDiscount(billDiscount)
                .grandTotal(subTotal - billDiscount).paidAmount(subTotal - billDiscount)
                .dueAmount(0.0).salePaidAmount(subTotal - billDiscount).saleDueAmount(0.0)
                .createdAt(createdAt).build());
    }

    private void saveProfitLine(Long orderId, Long itemId, String name, double revenue, double cost) {
        orderItemRepository.save(OrderItem.builder()
                .orderId(orderId).itemId(itemId).barcode("PNL-" + itemId).itemName(name)
                .itemType(ItemType.NORMAL).qty(1000).displayQty(BigDecimal.ONE)
                .qtyUnit(MeasurementUnit.PCS).costPrice(cost).unitPrice(revenue)
                .discountType(DiscountType.NONE).discountValue(0.0).finalUnitPrice(revenue)
                .lineCost(cost).lineTotal(revenue).build());
    }

    private Item saveForecastItem(TenantFixture fixture, String barcode, int stock, double cost, double selling) {
        Category category = new Category();
        category.setName(barcode + " category");
        category = categoryRepository.save(category);
        SubCategory subCategory = new SubCategory();
        subCategory.setName(barcode + " sub");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);
        Item item = itemRepository.save(Item.builder().barcode(barcode).name(barcode).subCategory(subCategory)
                .costPrice(BigDecimal.valueOf(cost)).sellingPrice(BigDecimal.valueOf(selling)).reorderLevel(3000)
                .itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS).active(true).build());
        stockBatchRepository.save(StockBatch.builder().branch(fixture.mainBranch()).item(item).batchCode(barcode + "-B1")
                .sourceType(StockBatchSourceType.PURCHASE).costPrice(BigDecimal.valueOf(cost))
                .sellingPrice(BigDecimal.valueOf(selling)).quantity(stock * 1000).originalQuantity(stock * 1000).build());
        return item;
    }

    private void saveForecastSale(TenantFixture fixture, Item item, int baseQty, int daysAgo, String invoiceNo) {
        double displayedQty = baseQty / 1000.0;
        Order order = orderRepository.save(Order.builder().invoiceNo(invoiceNo).branchId(fixture.mainBranch().getId())
                .cashierUserId(fixture.admin().getId()).orderType(OrderType.CASH).paymentMethod("CASH")
                .saleMode(SaleMode.TAKEAWAY).status(OrderStatus.COMPLETED).subTotal(displayedQty * item.getSellingPrice().doubleValue())
                .billDiscount(0.0).grandTotal(displayedQty * item.getSellingPrice().doubleValue())
                .paidAmount(displayedQty * item.getSellingPrice().doubleValue()).dueAmount(0.0)
                .salePaidAmount(displayedQty * item.getSellingPrice().doubleValue()).saleDueAmount(0.0)
                .createdAt(LocalDateTime.now().minusDays(daysAgo)).build());
        orderItemRepository.save(OrderItem.builder().orderId(order.getId()).itemId(item.getId()).barcode(item.getBarcode())
                .itemName(item.getName()).itemType(ItemType.NORMAL).qty(baseQty).displayQty(BigDecimal.valueOf(displayedQty))
                .qtyUnit(MeasurementUnit.PCS).costPrice(item.getCostPrice().doubleValue()).unitPrice(item.getSellingPrice().doubleValue())
                .discountType(DiscountType.NONE).discountValue(0.0).finalUnitPrice(item.getSellingPrice().doubleValue())
                .lineCost(displayedQty * item.getCostPrice().doubleValue()).lineTotal(displayedQty * item.getSellingPrice().doubleValue()).build());
    }

    private void saveAgingOrder(Long branchId, Long cashierId, Long customerId, double due,
                                String invoiceNo, LocalDateTime createdAt) {
        orderRepository.save(Order.builder()
                .invoiceNo(invoiceNo).branchId(branchId).cashierUserId(cashierId).customerId(customerId)
                .orderType(OrderType.CREDIT).paymentMethod("CREDIT").saleMode(SaleMode.TAKEAWAY)
                .status(OrderStatus.COMPLETED).subTotal(due).billDiscount(0.0).grandTotal(due)
                .paidAmount(0.0).dueAmount(due).salePaidAmount(0.0).saleDueAmount(due)
                .createdAt(createdAt).build());
    }

    private void savePayablePurchase(Supplier supplier, Branch branch, String invoiceNo,
                                     double due, LocalDateTime createdAt) {
        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .invoiceNo(invoiceNo).supplier(supplier).createdAt(createdAt)
                .grandTotal(BigDecimal.valueOf(due)).discountAmount(BigDecimal.ZERO)
                .paidAmount(BigDecimal.ZERO).dueAmount(BigDecimal.valueOf(due))
                .paymentMethod("CREDIT").cashSource(CashSource.NONE).cashSourceAmount(BigDecimal.ZERO)
                .status(PurchaseStatus.COMPLETED).build());
        grnRepository.save(GRN.builder().grnNo("GRN-" + invoiceNo).supplier(supplier).branch(branch)
                .purchase(purchase).totalAmount(BigDecimal.valueOf(due)).paidAmount(BigDecimal.ZERO)
                .receivedAt(createdAt).build());
    }

    private Item saveMovementItem(String barcode, String name, SubCategory subCategory) {
        return itemRepository.save(Item.builder().barcode(barcode).name(name).subCategory(subCategory)
                .itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS).costPrice(BigDecimal.TEN)
                .sellingPrice(BigDecimal.valueOf(20)).reorderLevel(1).active(true).posVisible(true).build());
    }

    private JsonNode findByItemId(JsonNode items, Long itemId) {
        for (JsonNode item : items) if (item.path("itemId").asLong() == itemId) return item;
        throw new AssertionError("Item not found in report: " + itemId);
    }
}
