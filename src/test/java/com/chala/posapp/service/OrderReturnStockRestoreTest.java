package com.chala.posapp.service;

import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.OrderItemStockUsage;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.repository.*;
import com.chala.posapp.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderReturnStockRestoreTest {

    private OrderItemStockUsageRepository usageRepository;
    private StockBatchRepository stockBatchRepository;
    private ItemRepository itemRepository;
    private OrderReturnService service;

    private StockBatch firstBatch;
    private StockBatch secondBatch;
    private OrderItemStockUsage firstUsage;
    private OrderItemStockUsage secondUsage;
    private OrderItem soldLine;

    @BeforeEach
    void setUp() {
        usageRepository = mock(OrderItemStockUsageRepository.class);
        stockBatchRepository = mock(StockBatchRepository.class);
        itemRepository = mock(ItemRepository.class);
        service = new OrderReturnService(
                mock(OrderRepository.class), mock(OrderItemRepository.class), usageRepository,
                mock(OrderReturnRepository.class), mock(OrderReturnItemRepository.class),
                mock(SecurityUtils.class), mock(CustomerRepository.class), stockBatchRepository,
                itemRepository, mock(CashShiftRepository.class), mock(WarrantyRepository.class),
                mock(UserRepository.class), mock(ReportCacheInvalidator.class),
                mock(PromotionRedemptionService.class), mock(LoyaltyService.class));

        soldLine = OrderItem.builder().id(10L).itemId(20L).qty(3000).build();
        firstUsage = OrderItemStockUsage.builder()
                .id(1L).orderItemId(10L).itemId(20L).batchId(101L).quantity(1000).build();
        secondUsage = OrderItemStockUsage.builder()
                .id(2L).orderItemId(10L).itemId(20L).batchId(102L).quantity(2000).build();
        firstBatch = StockBatch.builder().id(101L).quantity(0).build();
        secondBatch = StockBatch.builder().id(102L).quantity(0).build();

        when(itemRepository.findById(20L)).thenReturn(Optional.of(Item.builder()
                .id(20L).itemType(ItemType.NORMAL).build()));
        when(usageRepository.findByOrderItemId(10L)).thenReturn(List.of(firstUsage, secondUsage));
        when(usageRepository.sumRestoredQtyByOrderItemId(10L)).thenAnswer(call ->
                firstUsage.getRestoredQuantity() + secondUsage.getRestoredQuantity());
        when(stockBatchRepository.findById(101L)).thenReturn(Optional.of(firstBatch));
        when(stockBatchRepository.findById(102L)).thenReturn(Optional.of(secondBatch));
    }

    @Test
    @DisplayName("a line split across batches restores exactly what it took, across partials")
    void multiBatchPartialReturnsRestoreExactly() {
        restore(1000);
        restore(1000);
        restore(1000);

        assertThat(firstBatch.getQuantity()).isEqualTo(1000);
        assertThat(secondBatch.getQuantity()).isEqualTo(2000);
        assertThat(firstUsage.getRestoredQuantity()).isEqualTo(1000);
        assertThat(secondUsage.getRestoredQuantity()).isEqualTo(2000);
    }

    private void restore(int normalizedReturnQty) {
        ReflectionTestUtils.invokeMethod(service, "reverseStockForReturnItem",
                soldLine, normalizedReturnQty, 1L, false);
    }
}
