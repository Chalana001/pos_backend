package com.chala.posapp.service;

import com.chala.posapp.dto.promotion.PromotionCheckResponse;
import com.chala.posapp.dto.promotion.PromotionItemLine;
import com.chala.posapp.dto.promotion.PromotionRequest;
import com.chala.posapp.dto.promotion.PromotionSimulationRequest;
import com.chala.posapp.dto.promotion.PromotionSimulationResponse;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.StackingMode;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.promotion.engine.PromotionSnapshot;
import com.chala.posapp.promotion.engine.TargetSnapshot;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.OrderItemRepository;
import com.chala.posapp.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The replay: the engine over real sales, on its own. If these numbers are right, "what will
 * this cost" has an answer before anyone switches it on.
 */
class PromotionSimulationServiceTest {

    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private ItemRepository itemRepository;
    private PromotionSnapshotCache cache;
    private PromotionLifecycleService lifecycle;
    private PromotionSimulationService service;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        itemRepository = mock(ItemRepository.class);
        cache = mock(PromotionSnapshotCache.class);
        lifecycle = mock(PromotionLifecycleService.class);
        service = new PromotionSimulationService(orderRepository, orderItemRepository, itemRepository, cache, lifecycle);
        when(cache.candidates()).thenReturn(List.of());
    }

    private Item item(long id, double price, double cost) {
        Category c = new Category(); c.setId(2L);
        SubCategory s = new SubCategory(); s.setId(3L); s.setCategory(c);
        return Item.builder().id(id).name("Item " + id).itemType(ItemType.NORMAL).defaultUnit(MeasurementUnit.PCS)
                .sellingPrice(BigDecimal.valueOf(price)).costPrice(BigDecimal.valueOf(cost)).subCategory(s).build();
    }

    private Order order(long id, int daysAgo) {
        return Order.builder().id(id).branchId(1L).status(OrderStatus.COMPLETED).createdAt(now.minusDays(daysAgo)).build();
    }

    private OrderItem line(long orderId, long itemId, double unitPrice, int pieces, double cost) {
        return OrderItem.builder().orderId(orderId).itemId(itemId).itemName("Item " + itemId)
                .unitPrice(unitPrice).qty(pieces * 1000).costPrice(cost).build();
    }

    private PromotionRequest tenPercentOnItem7() {
        PromotionRequest r = new PromotionRequest();
        r.setName("Ten off");
        r.setScope(PromotionScope.ITEM);
        r.setDiscountType(DiscountType.PERCENT);
        r.setDiscountValue(10);
        r.setStartAt(now.plusDays(1));
        r.setEndAt(now.plusDays(30));
        r.setItems(List.of(PromotionItemLine.builder().id(7L).build()));
        r.setAllowBelowCost(true);
        return r;
    }

    @Test
    @DisplayName("sums what the promotion would have taken off, over the orders it would have touched")
    void projectsDiscount() {
        // Order 1: two of item 7 at 100 -> 20 off. Order 2: item 8 only -> untouched. Order 3: one of 7 -> 10 off.
        when(orderRepository.findForReplay(eq(OrderStatus.COMPLETED), eq(0L), any(), any(), any()))
                .thenReturn(List.of(order(1, 1), order(2, 2), order(3, 3)));
        when(orderItemRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(
                line(1, 7, 100, 2, 60), line(2, 8, 50, 1, 20), line(3, 7, 100, 1, 60)));
        when(itemRepository.findWithCategoryByIdIn(anyCollection())).thenReturn(List.of(item(7, 100, 60), item(8, 50, 20)));

        PromotionSimulationRequest request = new PromotionSimulationRequest();
        request.setPromotion(tenPercentOnItem7());
        request.setDays(30);

        PromotionSimulationResponse result = service.simulate(request);

        assertThat(result.getOrdersScanned()).isEqualTo(3);
        assertThat(result.getOrdersAffected()).isEqualTo(2);
        assertThat(result.getProjectedDiscount()).isEqualByComparingTo("30.00");
        assertThat(result.getMaxOrderDiscount()).isEqualByComparingTo("20.00");
        assertThat(result.getAverageDiscountPerAffectedOrder()).isEqualByComparingTo("15.00");
        assertThat(result.getProjectedDailyDiscount()).isEqualByComparingTo("1.00");
        // margin on affected lines: (200-120) + (100-60) = 120 before, 90 after
        assertThat(result.getGrossMarginBefore()).isEqualByComparingTo("120.00");
        assertThat(result.getGrossMarginAfter()).isEqualByComparingTo("90.00");
        assertThat(result.getTopItems()).singleElement().satisfies(top -> {
            assertThat(top.getItemId()).isEqualTo(7L);
            assertThat(top.getTimesDiscounted()).isEqualTo(2);
            assertThat(top.getDiscount()).isEqualByComparingTo("30.00");
        });
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("the promotion's own start date does not gate the replay, but its weekday schedule does")
    void ignoresDatesKeepsSchedules() {
        when(orderRepository.findForReplay(any(), anyLong(), any(), any(), any())).thenReturn(List.of(order(1, 1)));
        when(orderItemRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(line(1, 7, 100, 1, 60)));
        when(itemRepository.findWithCategoryByIdIn(anyCollection())).thenReturn(List.of(item(7, 100, 60)));

        PromotionRequest terms = tenPercentOnItem7(); // starts tomorrow: must still replay
        PromotionSimulationRequest request = new PromotionSimulationRequest();
        request.setPromotion(terms);
        assertThat(service.simulate(request).getOrdersAffected()).isEqualTo(1);

        // A schedule for a day the order was not on excludes it.
        int orderDayBit = 1 << (now.minusDays(1).getDayOfWeek().getValue() - 1);
        int everyOtherDay = 0b1111111 & ~orderDayBit;
        terms.setSchedules(List.of(new com.chala.posapp.dto.promotion.PromotionScheduleDto(everyOtherDay, null, null)));
        assertThat(service.simulate(request).getOrdersAffected()).isZero();
    }

    @Test
    @DisplayName("budget days is the budget over the projected daily spend")
    void budgetDays() {
        when(orderRepository.findForReplay(any(), anyLong(), any(), any(), any())).thenReturn(List.of(order(1, 1)));
        when(orderItemRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(line(1, 7, 100, 3, 60)));
        when(itemRepository.findWithCategoryByIdIn(anyCollection())).thenReturn(List.of(item(7, 100, 60)));

        PromotionRequest terms = tenPercentOnItem7();
        terms.setBudgetAmount(BigDecimal.valueOf(300));
        PromotionSimulationRequest request = new PromotionSimulationRequest();
        request.setPromotion(terms);
        request.setDays(30);

        // 30 off over 30 days = 1/day; 300 budget lasts 300 days
        assertThat(service.simulate(request).getBudgetDaysRemaining()).isEqualByComparingTo("300.0");
    }

    @Test
    @DisplayName("the pre-save check names running promotions that share targets and dates")
    void overlapWarning() {
        PromotionSnapshot running = new PromotionSnapshot(9L, "Old sale", PromotionScope.ITEM, DiscountType.PERCENT,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, now.minusDays(5), now.plusDays(5), null, 0, null, false,
                PromotionEffectType.DISCOUNT, null, null, StackingMode.BEST_ONLY, true,
                List.of(new TargetSnapshot(7L, null, null, null, null, null, null)), List.of(), List.of());
        when(cache.candidates()).thenReturn(List.of(running));
        when(itemRepository.findById(anyLong())).thenReturn(Optional.of(item(7, 100, 60)));

        PromotionCheckResponse result = service.check(tenPercentOnItem7(), null);

        assertThat(result.getWarnings()).singleElement().satisfies(w -> {
            assertThat(w.getCode()).isEqualTo("OVERLAP");
            assertThat(w.getPromotionId()).isEqualTo(9L);
            assertThat(w.getMessage()).contains("Old sale").contains("larger discount wins");
        });
    }

    @Test
    @DisplayName("a promotion that has already ended is flagged")
    void alreadyEnded() {
        PromotionRequest terms = tenPercentOnItem7();
        terms.setStartAt(now.minusDays(10));
        terms.setEndAt(now.minusDays(1));

        assertThat(service.check(terms, null).getWarnings())
                .extracting(PromotionCheckResponse.Warning::getCode).contains("ALREADY_ENDED");
    }
}
