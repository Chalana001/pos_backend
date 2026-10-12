package com.chala.posapp.service;

import com.chala.posapp.dto.returns.CreateReturnRequest;
import com.chala.posapp.dto.returns.ReturnItemRequest;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.OrderItemStockUsage;
import com.chala.posapp.entity.OrderReturn;
import com.chala.posapp.entity.OrderReturnItem;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import com.chala.posapp.entity.PaymentCategory;
import com.chala.posapp.entity.PaymentMethod;
import com.chala.posapp.entity.ReferencePolicy;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.*;
import com.chala.posapp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STORE_CREDIT is only a promise this system can keep on a credit sale. There is no customer
 * wallet and no credit-note ledger in the schema, so the one thing a store-credit refund can
 * do is reduce what the customer already owes. A cash sale refunded as store credit would
 * write the method onto the return and print it on the slip while recording the credit
 * nowhere, money conjured from a label.
 *
 * <p>These pin both halves of that rule: refused on anything that is not a credit sale with a
 * customer, and still working on the sales where the credit does exist.
 */
class OrderReturnServiceTest {

    private static final String INVOICE_NO = "INV-2026-06-B1-000001";

    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private OrderItemStockUsageRepository orderItemStockUsageRepository;
    private OrderReturnRepository orderReturnRepository;
    private OrderReturnItemRepository orderReturnItemRepository;
    private StockBatchRepository stockBatchRepository;
    private SecurityUtils securityUtils;
    private CustomerRepository customerRepository;
    private ItemRepository itemRepository;
    private LoyaltyService loyaltyService;
    private OrderReturnService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        orderItemStockUsageRepository = mock(OrderItemStockUsageRepository.class);
        orderReturnRepository = mock(OrderReturnRepository.class);
        orderReturnItemRepository = mock(OrderReturnItemRepository.class);
        securityUtils = mock(SecurityUtils.class);
        customerRepository = mock(CustomerRepository.class);
        stockBatchRepository = mock(StockBatchRepository.class);
        itemRepository = mock(ItemRepository.class);
        CashShiftRepository cashShiftRepository = mock(CashShiftRepository.class);
        WarrantyRepository warrantyRepository = mock(WarrantyRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ReportCacheInvalidator reportCacheInvalidator = mock(ReportCacheInvalidator.class);
        PromotionRedemptionService promotionRedemptionService = mock(PromotionRedemptionService.class);
        loyaltyService = mock(LoyaltyService.class);
        PaymentMethodService paymentMethodService = mock(PaymentMethodService.class);
        // Mirror the seeded methods: the code resolves to a config whose credit flag
        // matches the real seed (only CREDIT defers; STORE_CREDIT does not).
        when(paymentMethodService.resolveActiveForUse(anyString())).thenAnswer(call -> {
            String code = call.getArgument(0, String.class).trim().toUpperCase();
            return PaymentMethod.builder()
                    .code(code)
                    .displayName(code)
                    .category(PaymentCategory.OTHER)
                    .referencePolicy(ReferencePolicy.NONE)
                    .includeInExpectedCash("CASH".equals(code))
                    .allowsCredit("CREDIT".equals(code))
                    .active(true)
                    .build();
        });

        service = new OrderReturnService(
                orderRepository, orderItemRepository, orderItemStockUsageRepository,
                orderReturnRepository, orderReturnItemRepository, securityUtils,
                customerRepository, stockBatchRepository, itemRepository, cashShiftRepository,
                warrantyRepository, userRepository, reportCacheInvalidator,
                promotionRedemptionService, loyaltyService, paymentMethodService);

        User user = User.builder().id(1L).build();
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(securityUtils.isAdminLike(user)).thenReturn(true);

        // Nothing else is stubbed unless a test needs it, so a request that reaches further
        // than it should fails loudly rather than passing on a permissive mock.
        when(loyaltyService.clawBackForReturn(anyLong(), anyLong(), any(), any()))
                .thenReturn(LoyaltyService.ReversalOutcome.NONE);
        when(orderReturnItemRepository.save(any()))
                .thenAnswer(call -> call.getArgument(0));
        when(warrantyRepository.findByOrderId(anyLong())).thenReturn(List.of());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void givenOrder(OrderType orderType, Long customerId) {
        Order order = Order.builder()
                .id(10L)
                .invoiceNo(INVOICE_NO)
                .branchId(1L)
                .customerId(customerId)
                .orderType(orderType)
                .status(OrderStatus.COMPLETED)
                .subTotal(1000.0)
                .grandTotal(1000.0)
                .build();
        when(orderRepository.findByInvoiceNo(INVOICE_NO)).thenReturn(Optional.of(order));
    }

    /**
     * The one sale line the request asks to return. Leaving it unstubbed is deliberate: with
     * no line at all, reaching the line loop produces the "does not belong to order" message,
     * which is how the guard's ordering is proved.
     */
    private void givenReturnableLine() {
        OrderItem line = OrderItem.builder()
                .id(100L)
                .orderId(10L)
                .itemId(7L)
                .itemName("Widget")
                .qty(1000)
                .displayQty(BigDecimal.ONE)
                .qtyUnit(MeasurementUnit.PCS)
                .unitPrice(100.0)
                .finalUnitPrice(100.0)
                .build();
        when(orderItemRepository.findByOrderId(anyLong())).thenReturn(List.of(line));
        when(itemRepository.findById(7L)).thenReturn(Optional.of(Item.builder()
                .id(7L).itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS).build()));
        when(orderReturnRepository.save(any(OrderReturn.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    /**
     * A WEIGHT/VOLUME line sold in {@code qtyUnit}, with one stock batch and its usage row behind
     * it. Returns the mutable batch and usage so a test can assert what the reversal did to them.
     */
    private MeasuredFixture givenMeasuredLine(ItemType itemType, MeasurementUnit qtyUnit, int qty,
                                              BigDecimal displayQty, double finalUnitPrice,
                                              int batchQty, int originalBatchQty) {
        OrderItem line = OrderItem.builder()
                .id(100L)
                .orderId(10L)
                .itemId(7L)
                .itemName("Rice")
                .qty(qty)
                .displayQty(displayQty)
                .qtyUnit(qtyUnit)
                .unitPrice(finalUnitPrice)
                .finalUnitPrice(finalUnitPrice)
                .build();
        when(orderItemRepository.findByOrderId(anyLong())).thenReturn(List.of(line));
        when(itemRepository.findById(7L)).thenReturn(Optional.of(Item.builder()
                .id(7L).itemType(itemType).defaultUnit(qtyUnit).build()));

        StockBatch batch = StockBatch.builder()
                .id(30L)
                .item(Item.builder().id(7L).itemType(itemType).build())
                .quantity(batchQty)
                .originalQuantity(originalBatchQty)
                .costPrice(new BigDecimal("100.00"))
                .sellingPrice(BigDecimal.valueOf(finalUnitPrice))
                .build();
        OrderItemStockUsage usage = OrderItemStockUsage.builder()
                .id(40L)
                .orderItemId(100L)
                .itemId(7L)
                .batchId(30L)
                .quantity(qty)
                .restoredQuantity(0)
                .build();
        when(orderItemStockUsageRepository.findByOrderItemId(100L)).thenReturn(List.of(usage));
        when(stockBatchRepository.findById(30L)).thenReturn(Optional.of(batch));
        when(orderItemStockUsageRepository.sumRestoredQtyByOrderItemId(100L)).thenReturn(0);
        when(orderReturnRepository.save(any(OrderReturn.class)))
                .thenAnswer(call -> call.getArgument(0));

        return new MeasuredFixture(batch, usage);
    }

    private record MeasuredFixture(StockBatch batch, OrderItemStockUsage usage) { }

    private OrderReturnItem savedReturnItem() {
        ArgumentCaptor<OrderReturnItem> captor = ArgumentCaptor.forClass(OrderReturnItem.class);
        verify(orderReturnItemRepository).save(captor.capture());
        return captor.getValue();
    }

    private CreateReturnRequest request(String refundMethod) {
        ReturnItemRequest item = new ReturnItemRequest();
        item.setOrderItemId(100L);
        item.setReturnQty(BigDecimal.ONE);

        CreateReturnRequest request = new CreateReturnRequest();
        request.setRefundMethod(refundMethod);
        request.setReason("Damaged on opening");
        request.setItems(List.of(item));
        return request;
    }

    /**
     * The wire payload is the source of truth for the request type. The service itself can be
     * called with an already-built DTO, which would hide a JSON deserialization failure, so
     * this keeps the RED state honest: 0.7 either fails to bind or arrives truncated.
     */
    private CreateReturnRequest requestFromJson(String refundMethod, String itemJson) {
        try {
            String json = """
                    {
                      "refundMethod": "%s",
                      "reason": "Damaged on opening",
                      "items": [%s]
                    }
                    """.formatted(refundMethod, itemJson);
            return new ObjectMapper().readValue(json, CreateReturnRequest.class);
        } catch (Exception e) {
            throw new AssertionError("Failed to deserialize return request", e);
        }
    }

    private OrderReturn savedReturn() {
        ArgumentCaptor<OrderReturn> captor = ArgumentCaptor.forClass(OrderReturn.class);
        verify(orderReturnRepository).save(captor.capture());
        return captor.getValue();
    }

    // ── the guard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("STORE_CREDIT is refused on a sale that was not a credit sale")
    void storeCreditRefusedOnCashSale() {
        givenOrder(OrderType.CASH, 20L);

        assertThatThrownBy(() -> service.processReturn(INVOICE_NO, request("STORE_CREDIT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Store credit can only be refunded on a credit sale");

        // Refused before anything is written, so no half-processed return is left behind.
        verify(orderReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("STORE_CREDIT is refused on a credit sale with no customer to credit")
    void storeCreditRefusedOnCreditSaleWithoutCustomer() {
        givenOrder(OrderType.CREDIT, null);

        assertThatThrownBy(() -> service.processReturn(INVOICE_NO, request("STORE_CREDIT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Store credit can only be refunded on a credit sale");

        verify(orderReturnRepository, never()).save(any());
        verify(customerRepository, never()).save(any());
    }

    @Test
    @DisplayName("the guard runs before the return lines are validated")
    void storeCreditGuardRunsBeforeLineValidation() {
        // No order items are stubbed, so falling into the line loop would fail on
        // "Order item id 100 does not belong to order ...". Getting the guard's message back,
        // in lower case too, is what proves where the check sits.
        givenOrder(OrderType.CASH, 20L);

        assertThatThrownBy(() -> service.processReturn(INVOICE_NO, request("store_credit")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Store credit can only be refunded on a credit sale")
                .hasMessageNotContaining("does not belong");
    }

    @Test
    @DisplayName("cash, bank and card are untouched by the store-credit rule")
    void cashBankAndCardAreNotBlocked() {
        givenOrder(OrderType.CASH, 20L);

        for (String method : List.of("CASH", "BANK", "CARD")) {
            // Each gets past the guard and stops on the unstubbed line lookup, which is the
            // point: the store-credit rule is the only thing that changed for these.
            assertThatThrownBy(() -> service.processReturn(INVOICE_NO, request(method)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("does not belong");
        }
        verify(customerRepository, never()).save(any());
    }

    // ── the case the rule exists to allow ────────────────────────────────────

    @Test
    @DisplayName("STORE_CREDIT on a credit sale still reduces the customer's due")
    void storeCreditReducesDueOnCreditSale() {
        givenOrder(OrderType.CREDIT, 20L);
        givenReturnableLine();
        Customer customer = Customer.builder().id(20L).name("Nimal").dueAmount(5000.0).build();
        when(customerRepository.findById(20L)).thenReturn(Optional.of(customer));

        service.processReturn(INVOICE_NO, request("STORE_CREDIT"));

        OrderReturn saved = savedReturn();
        assertThat(saved.getRefundMethod()).isEqualTo("STORE_CREDIT");
        assertThat(saved.getCustomerId()).isEqualTo(20L);
        assertThat(saved.getTotalRefundAmount()).isEqualTo(100.0);
        assertThat(customer.getDueAmount()).isEqualTo(4900.0);
        verify(customerRepository).save(customer);
    }

    @Test
    @DisplayName("a due smaller than the refund floors at zero instead of going negative")
    void storeCreditDueFloorsAtZero() {
        givenOrder(OrderType.CREDIT, 20L);
        givenReturnableLine();
        Customer customer = Customer.builder().id(20L).name("Nimal").dueAmount(50.0).build();
        when(customerRepository.findById(20L)).thenReturn(Optional.of(customer));

        service.processReturn(INVOICE_NO, request("STORE_CREDIT"));

        assertThat(customer.getDueAmount()).isZero();
    }

    @Test
    @DisplayName("a fractional WEIGHT return persists 0.7 KG, restores 700 base units and refunds 0.7 x price")
    void fractionalWeightReturnPersistsRestoresAndPrices() {
        givenOrder(OrderType.CASH, null);

        OrderItem line = OrderItem.builder()
                .id(100L)
                .orderId(10L)
                .itemId(7L)
                .itemName("Rice")
                .qty(2000)
                .displayQty(new BigDecimal("2"))
                .qtyUnit(MeasurementUnit.KG)
                .unitPrice(500.0)
                .finalUnitPrice(500.0)
                .build();
        when(orderItemRepository.findByOrderId(anyLong())).thenReturn(List.of(line));
        when(itemRepository.findById(7L)).thenReturn(Optional.of(Item.builder()
                .id(7L).itemType(ItemType.WEIGHT).defaultUnit(MeasurementUnit.KG).build()));

        StockBatch batch = StockBatch.builder()
                .id(30L)
                .item(Item.builder().id(7L).itemType(ItemType.WEIGHT).build())
                .quantity(1000)
                .originalQuantity(3000)
                .costPrice(new BigDecimal("100.00"))
                .sellingPrice(new BigDecimal("500.00"))
                .build();
        OrderItemStockUsage usage = OrderItemStockUsage.builder()
                .id(40L)
                .orderItemId(100L)
                .itemId(7L)
                .batchId(30L)
                .quantity(2000)
                .restoredQuantity(0)
                .build();
        when(orderItemStockUsageRepository.findByOrderItemId(100L)).thenReturn(List.of(usage));
        when(stockBatchRepository.findById(30L)).thenReturn(Optional.of(batch));
        when(orderItemStockUsageRepository.sumRestoredQtyByOrderItemId(100L)).thenReturn(0);
        when(orderReturnRepository.save(any(OrderReturn.class)))
                .thenAnswer(call -> call.getArgument(0));

        service.processReturn(INVOICE_NO,
                requestFromJson("CASH", "{\"orderItemId\":100,\"returnQty\":0.7}"));

        ArgumentCaptor<OrderReturnItem> returnItemCaptor =
                ArgumentCaptor.forClass(OrderReturnItem.class);
        verify(orderReturnItemRepository).save(returnItemCaptor.capture());
        OrderReturnItem savedItem = returnItemCaptor.getValue();
        assertThat(savedItem.getReturnQty()).isEqualByComparingTo(new BigDecimal("0.7"));
        assertThat(savedItem.getRefundLineAmount()).isEqualTo(350.0);

        assertThat(batch.getQuantity()).isEqualTo(1700);
        assertThat(usage.getRestoredQuantity()).isEqualTo(700);
        verify(stockBatchRepository).saveAll(any());
        verify(orderItemStockUsageRepository).saveAll(any());

        OrderReturn saved = savedReturn();
        assertThat(saved.getTotalRefundAmount()).isEqualTo(350.0);
    }

    // ── unit selection on measured lines ─────────────────────────────────────

    @Test
    @DisplayName("a return entered in G against a KG line converts once and refunds its own share")
    void gramsAgainstKilogramLine() {
        givenOrder(OrderType.CASH, null);
        MeasuredFixture fixture = givenMeasuredLine(
                ItemType.WEIGHT, MeasurementUnit.KG, 2000, new BigDecimal("2"), 500.0, 1000, 3000);

        service.processReturn(INVOICE_NO,
                requestFromJson("CASH", "{\"orderItemId\":100,\"returnQty\":250,\"unit\":\"G\"}"));

        OrderReturnItem savedItem = savedReturnItem();
        // Persisted in the line's own unit, not the cashier's: 250 G is 0.25 KG.
        assertThat(savedItem.getReturnQty()).isEqualByComparingTo(new BigDecimal("0.25"));
        // 0.25 KG at 500 per KG - not 250 x 500, the thousandfold bug this fixes.
        assertThat(savedItem.getRefundLineAmount()).isEqualTo(125.0);
        assertThat(fixture.batch().getQuantity()).isEqualTo(1250);
        assertThat(fixture.usage().getRestoredQuantity()).isEqualTo(250);
        assertThat(savedReturn().getTotalRefundAmount()).isEqualTo(125.0);
    }

    @Test
    @DisplayName("a return entered in KG against a G line converts back up to the sale unit")
    void kilogramsAgainstGramLine() {
        givenOrder(OrderType.CASH, null);
        MeasuredFixture fixture = givenMeasuredLine(
                ItemType.WEIGHT, MeasurementUnit.G, 2000, new BigDecimal("2000"), 500.0, 1000, 3000);

        service.processReturn(INVOICE_NO,
                requestFromJson("CASH", "{\"orderItemId\":100,\"returnQty\":0.25,\"unit\":\"KG\"}"));

        OrderReturnItem savedItem = savedReturnItem();
        assertThat(savedItem.getReturnQty()).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(savedItem.getRefundLineAmount()).isEqualTo(125.0);
        assertThat(fixture.batch().getQuantity()).isEqualTo(1250);
        assertThat(fixture.usage().getRestoredQuantity()).isEqualTo(250);
    }

    @Test
    @DisplayName("the cap is checked in the line's unit, so 2500 G is rejected against a 2 KG line")
    void capRejectedAcrossUnits() {
        givenOrder(OrderType.CASH, null);
        givenMeasuredLine(ItemType.WEIGHT, MeasurementUnit.KG, 2000, new BigDecimal("2"), 500.0, 1000, 3000);

        // 250 G of the 2 KG line passes (proved by gramsAgainstKilogramLine); 2500 G does not.
        assertThatThrownBy(() -> service.processReturn(INVOICE_NO,
                requestFromJson("CASH", "{\"orderItemId\":100,\"returnQty\":2500,\"unit\":\"G\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("exceeds returnable qty");

        verify(orderReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("ML on a WEIGHT item is a bad request, not a conversion")
    void volumeUnitOnWeightItemIsRejected() {
        givenOrder(OrderType.CASH, null);
        givenMeasuredLine(ItemType.WEIGHT, MeasurementUnit.KG, 2000, new BigDecimal("2"), 500.0, 1000, 3000);

        assertThatThrownBy(() -> service.processReturn(INVOICE_NO,
                requestFromJson("CASH", "{\"orderItemId\":100,\"returnQty\":250,\"unit\":\"ML\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("weight");

        verify(orderReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("no unit on the request still returns in the line's own unit")
    void nullUnitDefaultsToTheLineUnit() {
        givenOrder(OrderType.CASH, null);
        givenMeasuredLine(ItemType.WEIGHT, MeasurementUnit.G, 2000, new BigDecimal("2000"), 500.0, 1000, 3000);

        service.processReturn(INVOICE_NO,
                requestFromJson("CASH", "{\"orderItemId\":100,\"returnQty\":250}"));

        OrderReturnItem savedItem = savedReturnItem();
        assertThat(savedItem.getReturnQty()).isEqualByComparingTo(new BigDecimal("250"));
        // A G line's price is per KG, so 250 G is a quarter of it.
        assertThat(savedItem.getRefundLineAmount()).isEqualTo(125.0);
    }

    @Test
    @DisplayName("a full return of a G line refunds exactly the line's own value")
    void fullGramLineReturnMatchesLineTotal() {
        givenOrder(OrderType.CASH, null);
        givenMeasuredLine(ItemType.WEIGHT, MeasurementUnit.G, 2000, new BigDecimal("2000"), 500.0, 1000, 3000);

        service.processReturn(INVOICE_NO,
                requestFromJson("CASH", "{\"orderItemId\":100,\"returnQty\":2000,\"unit\":\"G\"}"));

        // 2000 G at 500 per KG: the same 1000.0 the line was sold for, not a thousand times it.
        double lineTotal = new BigDecimal("2000")
                .multiply(BigDecimal.valueOf(500.0))
                .multiply(new BigDecimal("0.001"))
                .doubleValue();
        assertThat(savedReturnItem().getRefundLineAmount()).isEqualTo(lineTotal);
        assertThat(savedReturn().getTotalRefundAmount()).isEqualTo(1000.0);
    }

}