package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderPayment;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.repository.OrderPaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real MySQL: the rollback case needs the V58 CHECK/length constraints and
// Flyway-created order_payments table to behave exactly as production does.
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class InitialSalePaymentIntegrationTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            // pos_db must exist before the app boots: raw connections anchor on
            // it and the tenant migration chain runs over it.
            .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        // root, not the container user: the app provisions tenant databases at
        // runtime and the URL carries createDatabaseIfNotExist, both of which
        // need server-wide rights the per-database app user does not have.
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private OrderPaymentRepository orderPaymentRepository;

    private TenantFixture fixture;
    private String tenantId;
    private String adminToken;
    private String cashierToken;
    private long itemId;
    private long batchId;
    private long customerId;

    @BeforeEach
    void seedShopAndSellableStock() throws Exception {
        fixture = seedTenantShop(uniqueKey("salepay"), 2);
        tenantId = fixture.tenantId();
        adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Payment Goods " + uniqueKey("cat"));
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Payment Stock " + uniqueKey("sub"));
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName("Payment Supplier " + uniqueKey("sup"));
        supplier.setPhone("0770000000");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "PAYS-%s",
                  "name": "Payment Item",
                  "subCategoryId": %d,
                  "costPrice": 50,
                  "sellingPrice": 100,
                  "reorderLevel": 2
                }
                """.formatted(uniqueKey("barcode"), subCategory.getId())
        );
        itemId = item.path("id").asLong();

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "INV-PAYS-%s",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 20,
                          "costPrice": 50,
                          "sellingPrice": 100
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), uniqueKey("purchase"), fixture.mainBranch().getId(), itemId)
        );
        batchId = firstBatchFor(fixture.mainBranch().getId(), itemId).getId();

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
        customerId = customer.path("id").asLong();
    }

    private JsonNode sale(String method, String extraFields) throws Exception {
        return postJson(
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
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paymentMethod": "%s"%s
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId, method, extraFields)
        );
    }

    @Test
    void fullCashPaymentCreatesOneOrderPaymentAndRecordsChange() throws Exception {
        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Payment drawer"
                }
                """
        );

        JsonNode order = sale("CASH", """
                ,
                "tenderedAmount": 100,
                "note": "Exact cash"
                """
        );

        assertSaleTotals(order, 100.0, 0.0);
        assertEquals(100.0, order.path("tenderedAmount").asDouble(), 0.001);
        assertEquals(0.0, order.path("changeAmount").asDouble(), 0.001);

        OrderPayment payment = paymentFor(order.path("id").asLong());
        assertEquals("CASH", payment.getPaymentMethod());
        assertEquals(100.0, payment.getAmount(), 0.001);
        assertEquals(100.0, payment.getTenderedAmount(), 0.001);
        assertEquals(0.0, payment.getChangeAmount(), 0.001);
        assertNull(payment.getShortfallAmount());
        assertEquals("Exact cash", payment.getNote());

        JsonNode shift = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(100.0, shift.path("cashSales").asDouble(), 0.001);
    }

    @Test
    void partialCashBecomesCreditWithoutCreditPaymentLedgerRow() throws Exception {
        JsonNode order = sale("CASH", """
                ,
                "customerId": %d,
                "tenderedAmount": 40,
                "note": "Cash and credit"
                """.formatted(customerId)
        );

        assertSaleTotals(order, 40.0, 60.0);
        assertEquals("CREDIT", order.path("orderType").asText());

        OrderPayment payment = paymentFor(order.path("id").asLong());
        assertEquals("CASH", payment.getPaymentMethod());
        assertEquals(40.0, payment.getAmount(), 0.001);
        assertEquals(40.0, payment.getTenderedAmount(), 0.001);
        assertEquals(0.0, payment.getChangeAmount(), 0.001);
        assertEquals(60.0, payment.getShortfallAmount(), 0.001);

        JsonNode customer = getJson("/customers/" + customerId, tenantId, cashierToken);
        assertEquals(60.0, customer.path("dueAmount").asDouble(), 0.001);
        assertTrue(creditPaymentRepository.findAll().isEmpty());
    }

    @Test
    void cashOverpaymentRecordsChangeAndDrawerAppliedAmount() throws Exception {
        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Overpayment drawer"
                }
                """
        );

        JsonNode order = sale("CASH", """
                ,
                "tenderedAmount": 125
                """
        );

        assertSaleTotals(order, 100.0, 0.0);
        assertEquals(125.0, order.path("tenderedAmount").asDouble(), 0.001);
        assertEquals(25.0, order.path("changeAmount").asDouble(), 0.001);

        OrderPayment payment = paymentFor(order.path("id").asLong());
        assertEquals(100.0, payment.getAmount(), 0.001);
        assertEquals(125.0, payment.getTenderedAmount(), 0.001);
        assertEquals(25.0, payment.getChangeAmount(), 0.001);

        JsonNode shift = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(100.0, shift.path("cashSales").asDouble(), 0.001);
    }

    @Test
    void fullCardPaymentStoresNoCardMetadata() throws Exception {
        JsonNode order = sale("CARD", """
                ,
                "paidAmount": 100,
                "cardNumber": "4111111111111111",
                "cvv": "123",
                "expiryDate": "12/30",
                "authorizationCode": "AUTH-123"
                """
        );

        assertSaleTotals(order, 100.0, 0.0);

        OrderPayment payment = paymentFor(order.path("id").asLong());
        assertEquals("CARD", payment.getPaymentMethod());
        assertEquals(100.0, payment.getAmount(), 0.001);
        assertNull(payment.getTenderedAmount());
        assertNull(payment.getChangeAmount());
        assertNull(payment.getBankReference());
    }

    @Test
    void bankPaymentStoresRequiredReference() throws Exception {
        JsonNode order = sale("BANK", """
                ,
                "paidAmount": 100,
                "bankReference": "TRN-7789",
                "note": "Bank transfer"
                """
        );

        assertSaleTotals(order, 100.0, 0.0);

        OrderPayment payment = paymentFor(order.path("id").asLong());
        assertEquals("BANK", payment.getPaymentMethod());
        assertEquals(100.0, payment.getAmount(), 0.001);
        assertEquals("TRN-7789", payment.getBankReference());
        assertEquals("Bank transfer", payment.getNote());
    }

    @Test
    void bankPaymentWithoutReferenceIsRejectedBeforeOrderCreation() throws Exception {
        long orderCountBefore = orderRepository.count();
        JsonNode error = jsonRequest(
                HttpMethod.POST,
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
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paymentMethod": "BANK",
                  "paidAmount": 100
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId),
                400
        );
        assertTrue(error.path("message").asText().contains("Bank Reference is required for Bank Transfer payments"));
        assertEquals(orderCountBefore, orderRepository.count());
    }

    @Test
    void nonCashPartialPaymentIsRejectedBeforeOrderCreation() throws Exception {
        long orderPaymentCountBefore = orderPaymentRepository.count();
        JsonNode error = jsonRequest(
                HttpMethod.POST,
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "customerId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paymentMethod": "CARD",
                  "paidAmount": 40
                }
                """.formatted(fixture.mainBranch().getId(), customerId, itemId, batchId),
                400
        );
        assertTrue(error.path("message").asText().contains("must cover the full grand total"));
        assertEquals(orderPaymentCountBefore, orderPaymentRepository.count());
    }

    @Test
    void pureCreditSaleCreatesZeroPaymentAndCustomerDebt() throws Exception {
        JsonNode order = postJson(
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
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 0,
                  "paymentMethod": "CREDIT"
                }
                """.formatted(fixture.mainBranch().getId(), customerId, itemId, batchId)
        );

        assertSaleTotals(order, 0.0, 100.0);
        assertEquals("CREDIT", order.path("orderType").asText());

        OrderPayment payment = paymentFor(order.path("id").asLong());
        assertEquals("CREDIT", payment.getPaymentMethod());
        assertEquals(0.0, payment.getAmount(), 0.001);
        assertEquals(100.0, payment.getShortfallAmount(), 0.001);
        assertNull(payment.getTenderedAmount());

        JsonNode customer = getJson("/customers/" + customerId, tenantId, cashierToken);
        assertEquals(100.0, customer.path("dueAmount").asDouble(), 0.001);
    }

    @Test
    void laterSettlementCreatesCreditPaymentOnly() throws Exception {
        JsonNode order = sale("CASH", """
                ,
                "customerId": %d,
                "tenderedAmount": 40
                """.formatted(customerId)
        );
        long orderId = order.path("id").asLong();

        JsonNode settlement = postJson(
                "/credit/settle",
                tenantId,
                cashierToken,
                """
                {
                  "customerId": %d,
                  "amount": 40,
                  "paymentMethod": "CASH",
                  "note": "Later settlement"
                }
                """.formatted(customerId)
        );
        assertEquals(40.0, settlement.path("amount").asDouble(), 0.001);

        OrderPayment payment = paymentFor(orderId);
        assertEquals(40.0, payment.getAmount(), 0.001);
        assertEquals(1, orderPaymentRepository.count());
        assertEquals(1, creditPaymentRepository.count());

        JsonNode customer = getJson("/customers/" + customerId, tenantId, cashierToken);
        assertEquals(20.0, customer.path("dueAmount").asDouble(), 0.001);
    }

    @Test
    void chequeSalesAreRejected() throws Exception {
        long orderCountBefore = orderRepository.count();
        JsonNode error = jsonRequest(
                HttpMethod.POST,
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
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "paymentMethod": "CHEQUE"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId),
                400
        );
        assertTrue(error.path("message").asText().contains("CHEQUE"));
        assertTrue(error.path("message").asText().contains("not supported"));
        assertEquals(orderCountBefore, orderRepository.count());
    }

    @Test
    void nonCashOverpaymentIsRejected() throws Exception {
        JsonNode error = jsonRequest(
                HttpMethod.POST,
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
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 120,
                  "paymentMethod": "CARD"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId),
                400
        );
    }
    @Test
    void offlineImportRejectsNonCashPaymentMethods() throws Exception {
        assertOfflineImportRejected("BANK", null, "Offline import supports CASH payments only");
        assertOfflineImportRejected("CARD", null, "Offline import supports CASH payments only");
    }
    @Test
    void offlineImportNormalizesLowercaseCash() throws Exception {
        // An offline sale's cash must land in the drawer of the cashier who made it, and the
        // import refuses to run without an open shift for them. The till flow opens one
        // before selling; this test imports, so it opens one first.
        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Lowercase cash drawer"
                }
                """
        );

        String clientSaleId = uniqueKey("offline-cash-lower");
        JsonNode imported = postJson(
                "/orders/offline-import",
                tenantId,
                cashierToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T12:00:00",
                  "branchId": %d,
                  "orderType": "CASH",
                  "saleMode": "TAKEAWAY",
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "paymentMethod": "cash",
                  "tenderedAmount": 100,
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]
                }
                """.formatted(clientSaleId, fixture.mainBranch().getId(), itemId, batchId)
        );

        assertTrue(imported.path("success").asBoolean());
        OrderPayment payment = paymentFor(imported.path("orderId").asLong());
        assertEquals("CASH", payment.getPaymentMethod());
        assertEquals(100.0, payment.getAmount(), 0.001);
    }

    @Test
    void offlineImportRejectsPartialPayment() throws Exception {
        // Partial-payment validation happens after the shift guard, so the shift has to be
        // open for the request to reach the branch of the flow the message belongs to.
        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Partial payment drawer"
                }
                """
        );

        long orderCountBefore = orderRepository.count();
        JsonNode error = jsonRequest(
                HttpMethod.POST,
                "/orders/offline-import",
                tenantId,
                cashierToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T12:00:00",
                  "branchId": %d,
                  "customerId": %d,
                  "orderType": "CASH",
                  "saleMode": "TAKEAWAY",
                  "billDiscount": 0,
                  "paidAmount": 40,
                  "paymentMethod": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]
                }
                """.formatted(
                        uniqueKey("offline-partial"),
                        fixture.mainBranch().getId(),
                        customerId,
                        itemId,
                        batchId
                ),
                400
        );

        assertTrue(error.path("message").asText().contains(
                "Paid amount cannot be less than the grand total for offline import"));
        assertEquals(orderCountBefore, orderRepository.count());
    }

    @Test
    void explicitCreditRequiresCustomerEvenForZeroTotalSale() throws Exception {
        long orderCountBefore = orderRepository.count();
        JsonNode error = jsonRequest(
                HttpMethod.POST,
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
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 100,
                  "paymentMethod": "CREDIT",
                  "paidAmount": 0
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId),
                400
        );

        assertTrue(error.path("message").asText().contains("Customer is required for CREDIT sales"));
        assertEquals(orderCountBefore, orderRepository.count());
    }

    @Test
    void orderSettlementPersistsBankReference() throws Exception {
        JsonNode order = sale("CASH", """
                ,
                "customerId": %d,
                "tenderedAmount": 40
                """.formatted(customerId)
        );
        long orderId = order.path("id").asLong();

        JsonNode settlement = postJson(
                "/orders/" + order.path("invoiceNo").asText() + "/payments",
                tenantId,
                cashierToken,
                """
                {
                  "amount": 40,
                  "paymentMethod": "BANK",
                  "bankReference": "SETTLE-TRN-1001",
                  "note": "Bank settlement"
                }
                """
        );

        assertTotals(settlement, 80.0, 20.0);
        assertEquals(1, orderPaymentRepository.count());

        com.chala.posapp.entity.CreditPayment settlementPayment = creditPaymentRepository.findAll().getFirst();
        assertEquals("BANK", settlementPayment.getPaymentMethod());
        assertEquals("SETTLE-TRN-1001", settlementPayment.getBankReference());
        paymentFor(orderId);
    }

    @Test
    void directCreditSettlementPersistsBankReferenceAndValidatesMethod() throws Exception {
        JsonNode order = sale("CASH", """
                ,
                "customerId": %d,
                "tenderedAmount": 40
                """.formatted(customerId)
        );

        JsonNode error = jsonRequest(
                HttpMethod.POST,
                "/credit/settle",
                tenantId,
                cashierToken,
                """
                {
                  "customerId": %d,
                  "amount": 10,
                  "paymentMethod": "CHEQUE",
                  "note": "Invalid cheque"
                }
                """.formatted(customerId),
                400
        );
        assertTrue(error.path("message").asText().contains("CHEQUE"));
        assertTrue(error.path("message").asText().contains("not supported"));

        JsonNode settlement = postJson(
                "/credit/settle",
                tenantId,
                cashierToken,
                """
                {
                  "customerId": %d,
                  "amount": 40,
                  "paymentMethod": "BANK",
                  "bankReference": "DIRECT-TRN-2002",
                  "note": "Direct bank settlement"
                }
                """.formatted(customerId)
        );

        assertEquals("DIRECT-TRN-2002", settlement.path("bankReference").asText());
        assertEquals(1, orderPaymentRepository.count());
        assertEquals(1, creditPaymentRepository.count());
        com.chala.posapp.entity.CreditPayment settlementPayment = creditPaymentRepository.findAll().getFirst();
        assertEquals("DIRECT-TRN-2002", settlementPayment.getBankReference());

        // History is manager-and-above by design; a cashier who settles must not be able to
        // trawl every customer's payment record. The settle calls above stay cashier because
        // that is the till flow; this read is the audit view.
        jsonRequest(HttpMethod.GET, "/credit/payments/" + customerId, tenantId, cashierToken, null, 403);
        JsonNode history = getJson("/credit/payments/" + customerId, tenantId, adminToken);
        assertTrue(history.isArray());
        assertEquals(1, history.size());
        assertEquals("DIRECT-TRN-2002", history.get(0).path("bankReference").asText());
    }


    @Test
    void offlineImportRejectsBankReferences() throws Exception {
        assertOfflineImportRejected("CASH", "BANK-REF-100", "Offline import does not support bank references");
    }

    @Test
    void orderSettlementUsesTheOrderPaymentRouteWithoutAddingInitialPaymentRows() throws Exception {
        JsonNode order = sale("CASH", """
                ,
                "customerId": %d,
                "tenderedAmount": 40
                """.formatted(customerId)
        );
        long orderId = order.path("id").asLong();

        JsonNode settlement = postJson(
                "/orders/" + order.path("invoiceNo").asText() + "/payments",
                tenantId,
                cashierToken,
                """
                {
                  "amount": 40,
                  "paymentMethod": "CASH",
                  "note": "Later settlement"
                }
                """
        );

        assertTotals(settlement, 80.0, 20.0);
        assertEquals("CASH", settlement.path("paymentMethod").asText());

        OrderPayment initialPayment = paymentFor(orderId);
        assertEquals("CASH", initialPayment.getPaymentMethod());
        assertEquals(40.0, initialPayment.getAmount(), 0.001);
        assertEquals(1, orderPaymentRepository.count());
        assertEquals(1, creditPaymentRepository.count());
    }

    @Test
    void initialPaymentPersistenceFailureRollsBackWholeSale() throws Exception {
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        double dueBefore = customer.getDueAmount();
        int stockBefore = firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity();
        long orderCountBefore = orderRepository.count();
        long orderItemCountBefore = orderItemRepository.count();
        long orderPaymentCountBefore = orderPaymentRepository.count();
        String overlongBankReference = "R".repeat(121);

        jsonRequest(
                HttpMethod.POST,
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
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "paymentMethod": "BANK",
                  "bankReference": "%s"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId, overlongBankReference),
                400
        );

        customer = customerRepository.findById(customerId).orElseThrow();
        assertEquals(orderCountBefore, orderRepository.count());
        assertEquals(orderItemCountBefore, orderItemRepository.count());
        assertEquals(orderPaymentCountBefore, orderPaymentRepository.count());
        assertEquals(dueBefore, customer.getDueAmount(), 0.001);
        assertEquals(stockBefore, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());
    }

    private void assertTotals(JsonNode order, double paid, double due) {
        assertEquals(100.0, order.path("grandTotal").asDouble(), 0.001);
        assertEquals(paid, order.path("paidAmount").asDouble(), 0.001);
        assertEquals(due, order.path("dueAmount").asDouble(), 0.001);
    }

    /**
     * The sale-time half of the totals: {@code salePaidAmount} and {@code saleDueAmount} are
     * frozen at checkout (what the customer handed over at the till), while {@code paidAmount}
     * and {@code dueAmount} keep moving as later settlements arrive. This is the initial-sale
     * assertion; settlement responses must use {@link #assertTotals}, not this.
     */
    private void assertSaleTotals(JsonNode order, double paid, double due) {
        assertTotals(order, paid, due);
        assertEquals(paid, order.path("salePaidAmount").asDouble(), 0.001);
        assertEquals(due, order.path("saleDueAmount").asDouble(), 0.001);
    }
    private OrderPayment paymentFor(long orderId) {
        OrderPayment payment = orderPaymentRepository.findByOrderId(orderId).orElse(null);
        assertNotNull(payment);
        return payment;
    }

    private void assertOfflineImportRejected(String paymentMethod, String bankReference, String message) throws Exception {
        long orderCountBefore = orderRepository.count();
        String clientSaleId = uniqueKey("offline-" + paymentMethod.toLowerCase());
        String bankReferenceJson = bankReference == null
                ? ""
                : ",\n  \"bankReference\": \"%s\"".formatted(bankReference);

        JsonNode error = jsonRequest(
                HttpMethod.POST,
                "/orders/offline-import",
                tenantId,
                cashierToken,
                """
                {
                  "clientSaleId": "%s",
                  "offlineSoldAt": "2026-05-04T12:00:00",
                  "branchId": %d,
                  "orderType": "CASH",
                  "saleMode": "TAKEAWAY",
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "paymentMethod": "%s",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1,
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ]%s
                }
                """.formatted(
                        clientSaleId,
                        fixture.mainBranch().getId(),
                        paymentMethod,
                        itemId,
                        batchId,
                        bankReferenceJson
                ),
                400
        );

        assertTrue(error.path("message").asText().contains(message), error.path("message").asText());
        assertEquals(orderCountBefore, orderRepository.count());
    }

}
