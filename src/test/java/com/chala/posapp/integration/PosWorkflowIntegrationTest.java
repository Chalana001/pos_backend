package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.ExpenseCategory;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PosWorkflowIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void customerOrderShiftPaymentDashboardAndReportApisWork() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("ops"), 5);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Beverages");
        category.setTenantId(tenantId);
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setTenantId(tenantId);
        subCategory.setName("Tea");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setTenantId(tenantId);
        supplier.setName("Ops Supplier");
        supplier.setPhone("0111111111");
        supplier.setEmail("ops@supplier.local");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "90001",
                  "name": "Milk Tea",
                  "subCategoryId": %d,
                  "costPrice": 50,
                  "sellingPrice": 80,
                  "reorderLevel": 15
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        JsonNode purchase = postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "INV-OPS-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 10,
                          "costPrice": 50,
                          "sellingPrice": 80
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), itemId)
        );
        assertEquals(500.0, purchase.path("grandTotal").asDouble(), 0.001);
        long batchId = firstBatchFor(fixture.mainBranch().getId(), itemId).getId();

        JsonNode customer = postJson(
                "/customers",
                tenantId,
                cashierToken,
                """
                {
                  "name": "Nimal Perera",
                  "phone": "0712345678",
                  "address": "Kandy",
                  "creditLimit": 500
                }
                """
        );
        long customerId = customer.path("id").asLong();

        JsonNode customerById = getJson("/customers/" + customerId, tenantId, cashierToken);
        assertEquals("Nimal Perera", customerById.path("name").asText());

        JsonNode customerByPhone = getJson("/customers/phone/0712345678", tenantId, cashierToken);
        assertEquals(customerId, customerByPhone.path("id").asLong());

        JsonNode customerList = getJson("/customers?activeOnly=true", tenantId, cashierToken);
        assertEquals(1, customerList.size());

        JsonNode customerSearch = getJson("/customers/search?name=Nimal", tenantId, cashierToken);
        assertEquals(1, customerSearch.size());

        JsonNode updatedCustomer = putJson(
                "/customers/" + customerId,
                tenantId,
                adminToken,
                """
                {
                  "name": "Nimal P.",
                  "phone": "0712345678",
                  "address": "Colombo",
                  "creditLimit": 800,
                  "active": true
                }
                """
        );
        assertEquals("Nimal P.", updatedCustomer.path("name").asText());

        JsonNode createdNote = postJson(
                "/customers/" + customerId + "/notes",
                tenantId,
                adminToken,
                """
                {
                  "note": "Prefers evening delivery"
                }
                """
        );
        long noteId = createdNote.path("id").asLong();

        JsonNode listedNotes = getJson("/customers/" + customerId + "/notes?page=0&size=10", tenantId, adminToken);
        assertEquals(1, listedNotes.path("items").size());

        JsonNode updatedNote = putJson(
                "/customer-notes/" + noteId,
                tenantId,
                adminToken,
                """
                {
                  "note": "Prefers evening delivery and SMS"
                }
                """
        );
        assertTrue(updatedNote.path("note").asText().contains("SMS"));

        JsonNode adminShift = postJson(
                "/shifts/branch/open?branchId=" + fixture.mainBranch().getId(),
                tenantId,
                adminToken,
                """
                {
                  "openingCash": 3000,
                  "assignedCashierId": 0,
                  "note": "Admin counter shift"
                }
                """
        );
        long adminShiftId = adminShift.path("id").asLong();

        JsonNode cashierShift = postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 5000,
                  "note": "Morning shift"
                }
                """
        );
        long cashierShiftId = cashierShift.path("id").asLong();
        assertEquals("OPEN", cashierShift.path("status").asText());

        JsonNode myShift = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(cashierShiftId, myShift.path("id").asLong());

        JsonNode adminCurrentShift = getJson("/shifts/admin-current?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(adminShiftId, adminCurrentShift.path("id").asLong());

        JsonNode activeShifts = getJson("/shifts/active?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(2, activeShifts.size());

        JsonNode allShifts = getJson("/shifts/all?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, allShifts.path("content").size());

        JsonNode createdExpense = postJson(
                "/expenses",
                tenantId,
                adminToken,
                """
                {
                  "category": "%s",
                  "amount": 100,
                  "branchId": %d,
                  "fromDrawer": true,
                  "description": "Tea counter electricity"
                }
                """.formatted(ExpenseCategory.TEA.name(), fixture.mainBranch().getId())
        );
        assertEquals(100.0, createdExpense.path("amount").asDouble(), 0.001);

        JsonNode nonDrawerExpense = postJson(
                "/expenses",
                tenantId,
                adminToken,
                """
                {
                  "category": "%s",
                  "amount": 200,
                  "branchId": %d,
                  "isFromDrawer": false,
                  "description": "200"
                }
                """.formatted(ExpenseCategory.OTHER.name(), fixture.mainBranch().getId())
        );
        assertEquals(200.0, nonDrawerExpense.path("amount").asDouble(), 0.001);

        JsonNode adminShiftAfterExpenses = getJson("/shifts/admin-current?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(100.0, adminShiftAfterExpenses.path("totalExpenses").asDouble(), 0.001);

        JsonNode cashierCashDropShift = postJson(
                "/shifts/cashdrop",
                tenantId,
                cashierToken,
                """
                {
                  "amount": 200,
                  "reason": "Bank deposit"
                }
                """
        );
        assertEquals(200.0, cashierCashDropShift.path("totalCashDrops").asDouble(), 0.001);

        JsonNode adminCashDropShift = postJson(
                "/shifts/" + adminShiftId + "/cashdrop",
                tenantId,
                adminToken,
                """
                {
                  "amount": 150,
                  "reason": "Admin drawer transfer"
                }
                """
        );
        assertEquals(150.0, adminCashDropShift.path("totalCashDrops").asDouble(), 0.001);

        JsonNode expensePage = getJson("/expenses?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, expensePage.path("content").size());

        JsonNode cashDropPage = getJson("/cash-drops?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, cashDropPage.path("content").size());

        JsonNode cashOrder = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 2,
                      "unitPrice": 80,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 160,
                  "note": "Cash order"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId)
        );
        String cashInvoice = cashOrder.path("invoiceNo").asText();
        assertEquals(160.0, cashOrder.path("grandTotal").asDouble(), 0.001);

        JsonNode shiftAfterCashOrder = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(160.0, shiftAfterCashOrder.path("cashSales").asDouble(), 0.001);
        assertEquals(200.0, shiftAfterCashOrder.path("totalCashDrops").asDouble(), 0.001);

        JsonNode creditOrder = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "customerId": %d,
                  "orderType": "CREDIT",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 3,
                      "unitPrice": 80,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 0,
                  "note": "Credit order"
                }
                """.formatted(fixture.mainBranch().getId(), customerId, itemId, batchId)
        );
        String creditInvoice = creditOrder.path("invoiceNo").asText();
        assertEquals(240.0, creditOrder.path("dueAmount").asDouble(), 0.001);

        JsonNode fetchedCashOrder = getJson("/orders/" + cashInvoice, tenantId, cashierToken);
        assertEquals(cashInvoice, fetchedCashOrder.path("invoiceNo").asText());
        assertEquals(1, fetchedCashOrder.path("items").size());

        JsonNode ordersPage = getJson("/orders?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, ordersPage.path("content").size());

        JsonNode customerOrders = getJson("/customers/" + customerId + "/orders?orderType=ALL&page=0&size=10", tenantId, adminToken);
        assertEquals(1, customerOrders.path("items").size());
        assertEquals(creditInvoice, customerOrders.path("items").get(0).path("invoiceNo").asText());

        JsonNode customerPayment = postJson(
                "/customers/" + customerId + "/payments",
                tenantId,
                cashierToken,
                """
                {
                  "amount": 50,
                  "paymentMethod": "CASH",
                  "note": "Customer part payment"
                }
                """
        );
        assertEquals(190.0, customerPayment.path("dueAmount").asDouble(), 0.001);

        JsonNode creditSettlement = postJson(
                "/credit/settle",
                tenantId,
                cashierToken,
                """
                {
                  "customerId": %d,
                  "amount": 40,
                  "note": "Credit desk settlement"
                }
                """.formatted(customerId)
        );
        assertEquals(40.0, creditSettlement.path("amount").asDouble(), 0.001);

        JsonNode creditHistory = getJson("/credit/payments/" + customerId, tenantId, adminToken);
        assertEquals(1, creditHistory.size());

        JsonNode canceledOrder = postJson(
                "/orders/" + cashInvoice + "/cancel",
                tenantId,
                adminToken,
                """
                {
                  "reason": "Customer changed mind"
                }
                """
        );
        assertEquals("CANCELED", canceledOrder.path("status").asText());
        assertEquals(1, stockBatchRepository.findByBranchIdAndItemId(fixture.mainBranch().getId(), itemId).size());
        assertEquals(7, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());

        JsonNode shiftAfterCancel = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(0.0, shiftAfterCancel.path("cashSales").asDouble(), 0.001);
        assertEquals(200.0, shiftAfterCancel.path("totalCashDrops").asDouble(), 0.001);

        JsonNode closedCashierShift = postJson(
                "/shifts/close",
                tenantId,
                cashierToken,
                """
                {
                  "countedCash": 5040,
                  "note": "Cashier closed"
                }
                """
        );
        assertEquals("CLOSED", closedCashierShift.path("status").asText());

        JsonNode closedAdminShift = postJson(
                "/shifts/" + adminShiftId + "/close",
                tenantId,
                adminToken,
                """
                {
                  "countedCash": 2750,
                  "note": "Admin shift closed"
                }
                """
        );
        assertEquals("CLOSED", closedAdminShift.path("status").asText());

        String today = LocalDate.now().toString();

        JsonNode dashboardKpis = getJson("/dashboard/kpis?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertTrue(dashboardKpis.path("todaySales").asDouble() >= 240.0);
        assertTrue(dashboardKpis.path("todayExpenses").asDouble() >= 100.0);

        JsonNode dailyChart = getJson("/dashboard/charts/daily?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertFalse(dailyChart.isEmpty());

        JsonNode monthlyChart = getJson("/dashboard/charts/monthly?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertFalse(monthlyChart.isEmpty());

        JsonNode stockAfterOrders = getJson("/stock/branch/" + fixture.mainBranch().getId() + "?page=0&size=10", tenantId, adminToken);
        assertEquals(itemId, stockAfterOrders.path("content").get(0).path("itemId").asLong());

        JsonNode salesSummary = getJson("/reports/sales-summary?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertEquals(240.0, salesSummary.path("creditSales").asDouble(), 0.001);

        JsonNode profitSummary = getJson("/reports/profit-summary?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertTrue(profitSummary.path("totalExpenses").asDouble() >= 100.0);

        JsonNode topSelling = getJson("/reports/top-selling?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&limit=10", tenantId, adminToken);
        assertEquals(itemId, topSelling.get(0).path("itemId").asLong());

        JsonNode profitReport = getJson("/reports/profit?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&limit=10", tenantId, adminToken);
        assertFalse(profitReport.isEmpty());

        JsonNode salesTrend = getJson("/reports/sales-trend?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&type=DAILY", tenantId, adminToken);
        assertFalse(salesTrend.isEmpty());

        JsonNode salesByCategory = getJson("/reports/sales-by-category?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertEquals("Beverages", salesByCategory.get(0).path("categoryName").asText());

        JsonNode recentOrders = getJson("/reports/recent-orders?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertFalse(recentOrders.isEmpty());

        JsonNode lowStock = getJson("/reports/low-stock?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertFalse(lowStock.isEmpty());

        JsonNode creditDue = getJson("/reports/credit-due", tenantId, adminToken);
        assertFalse(creditDue.isEmpty());

        JsonNode topCustomers = getJson("/reports/top-customers?branchId=" + fixture.mainBranch().getId() + "&limit=10", tenantId, adminToken);
        assertFalse(topCustomers.isEmpty());

        JsonNode topSuppliers = getJson("/reports/top-suppliers?branchId=" + fixture.mainBranch().getId() + "&limit=10", tenantId, adminToken);
        assertFalse(topSuppliers.isEmpty());

        deleteExpectingStatus("/customer-notes/" + noteId, tenantId, adminToken, 204);
        JsonNode notesAfterDelete = getJson("/customers/" + customerId + "/notes?page=0&size=10", tenantId, adminToken);
        assertTrue(notesAfterDelete.path("items").isEmpty());
    }

    @Test
    void serviceItemsUseServiceUnitAndCanBeSoldWithoutStock() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("svc"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        JsonNode secondBranch = postJson(
                "/branches",
                tenantId,
                adminToken,
                """
                {
                  "code": "SVC2",
                  "name": "Service Branch 2",
                  "address": "Branch 2",
                  "phone": "0771234567"
                }
                """
        );
        long secondBranchId = secondBranch.path("id").asLong();

        Category category = new Category();
        category.setName("Services");
        category.setTenantId(tenantId);
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setTenantId(tenantId);
        subCategory.setName("Delivery");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        JsonNode serviceItem = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "91001",
                  "name": "Home Delivery",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 100,
                  "itemType": "SERVICE",
                  "branchIds": [%d]
                }
                """.formatted(subCategory.getId(), fixture.mainBranch().getId())
        );
        long serviceItemId = serviceItem.path("id").asLong();
        assertEquals("SERVICE", serviceItem.path("itemType").asText());
        assertEquals("SERVICE", serviceItem.path("defaultUnit").asText());
        assertEquals(1, serviceItem.path("branchIds").size());
        assertEquals(fixture.mainBranch().getId(), serviceItem.path("branchIds").get(0).asLong());

        JsonNode fetchedServiceItem = getJson("/items/" + serviceItemId, tenantId, cashierToken);
        assertEquals("SERVICE", fetchedServiceItem.path("defaultUnit").asText());

        JsonNode posResults = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + fixture.mainBranch().getId(),
                tenantId,
                cashierToken
        );
        assertEquals(1, posResults.size());
        assertEquals("SERVICE", posResults.get(0).path("defaultUnit").asText());

        JsonNode hiddenInOtherBranch = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + secondBranchId,
                tenantId,
                managerToken
        );
        assertTrue(hiddenInOtherBranch.isEmpty());

        JsonNode cashierShift = postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Service counter"
                }
                """
        );
        assertEquals("OPEN", cashierShift.path("status").asText());

        JsonNode serviceOrder = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 2,
                      "qtyUnit": "SERVICE",
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 200,
                  "note": "Service order"
                }
                """.formatted(fixture.mainBranch().getId(), serviceItemId)
        );
        assertEquals(200.0, serviceOrder.path("grandTotal").asDouble(), 0.001);
        assertEquals("SERVICE", serviceOrder.path("items").get(0).path("qtyUnit").asText());

        JsonNode updatedServiceItem = putJson(
                "/items/" + serviceItemId,
                tenantId,
                adminToken,
                """
                {
                  "branchIds": [%d]
                }
                """.formatted(secondBranchId)
        );
        assertEquals(1, updatedServiceItem.path("branchIds").size());
        assertEquals(secondBranchId, updatedServiceItem.path("branchIds").get(0).asLong());

        JsonNode removedFromMainBranch = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + fixture.mainBranch().getId(),
                tenantId,
                cashierToken
        );
        assertTrue(removedFromMainBranch.isEmpty());

        JsonNode visibleInSecondBranch = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + secondBranchId,
                tenantId,
                managerToken
        );
        assertEquals(1, visibleInSecondBranch.size());

        JsonNode blockedOrder = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "qtyUnit": "SERVICE",
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "note": "Blocked service order"
                }
                """.formatted(fixture.mainBranch().getId(), serviceItemId),
                400
        );
        assertTrue(blockedOrder.path("message").asText().contains("not assigned to this branch"));
    }
}
