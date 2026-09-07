package com.chala.posapp.service;

import com.chala.posapp.entity.LoyaltyAccount;
import com.chala.posapp.entity.LoyaltySettings;
import com.chala.posapp.entity.LoyaltyTransaction;
import com.chala.posapp.entity.PromotionRedemption;
import com.chala.posapp.repository.LoyaltyAccountRepository;
import com.chala.posapp.repository.LoyaltySettingsRepository;
import com.chala.posapp.repository.LoyaltyTierRepository;
import com.chala.posapp.repository.LoyaltyTransactionRepository;
import com.chala.posapp.repository.PromotionCodeRepository;
import com.chala.posapp.repository.PromotionRedemptionRepository;
import com.chala.posapp.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Returning goods used to reverse nothing: a capped campaign with a normal rate of returns ran
 * out early and nothing said why, and points earned on goods that came back stayed in the
 * customer's balance.
 *
 * <p>These pin the two things that decide whether the books stay right — a partial return gives
 * back exactly its share and no more, and repeated partials that together take the whole line
 * give back the whole discount rather than overshooting it.
 */
class PromotionReturnReversalTest {

    private PromotionRepository promotionRepository;
    private PromotionRedemptionRepository redemptionRepository;
    private PromotionRedemptionService promotions;

    private LoyaltyAccountRepository accountRepository;
    private LoyaltyTransactionRepository loyaltyTransactionRepository;
    private LoyaltyService loyalty;

    @BeforeEach
    void setUp() {
        promotionRepository = mock(PromotionRepository.class);
        redemptionRepository = mock(PromotionRedemptionRepository.class);
        promotions = new PromotionRedemptionService(
                promotionRepository, mock(PromotionCodeRepository.class), redemptionRepository);

        LoyaltySettingsRepository settingsRepository = mock(LoyaltySettingsRepository.class);
        LoyaltyTierRepository tierRepository = mock(LoyaltyTierRepository.class);
        accountRepository = mock(LoyaltyAccountRepository.class);
        loyaltyTransactionRepository = mock(LoyaltyTransactionRepository.class);
        loyalty = new LoyaltyService(settingsRepository, tierRepository, accountRepository, loyaltyTransactionRepository);
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(
                LoyaltySettings.builder().enabled(true)
                        .pointsPerCurrency(BigDecimal.ONE).currencyPerPoint(BigDecimal.ONE).build()));
        when(tierRepository.findByActiveTrueOrderByMinLifetimePointsAsc()).thenReturn(List.of());
        when(accountRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(redemptionRepository.reversedSoFar(anyLong())).thenReturn(BigDecimal.ZERO);
    }

    private PromotionRedemption redemption(long id, long promotionId, long orderItemId, String amount) {
        return PromotionRedemption.builder()
                .id(id).promotionId(promotionId).orderId(500L).orderItemId(orderItemId).itemId(7L)
                .customerId(42L).branchId(1L).level(PromotionRedemption.Level.LINE)
                .discountAmount(new BigDecimal(amount)).redeemedAt(LocalDateTime.now()).build();
    }

    @SuppressWarnings("unchecked")
    private List<PromotionRedemption> savedRows() {
        ArgumentCaptor<List<PromotionRedemption>> captor = ArgumentCaptor.forClass(List.class);
        verify(redemptionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("promotion redemptions")
    class Promotions {

        @Test
        @DisplayName("returning a third of a line gives back a third of its discount")
        void proportional() {
            when(redemptionRepository.findLiveForOrderItem(10L))
                    .thenReturn(List.of(redemption(1, 100L, 10L, "90.00")));

            promotions.reverseForReturn(500L, 900L, 7L,
                    List.of(new PromotionRedemptionService.ReturnedLine(10L, new BigDecimal("0.333333"))));

            assertThat(savedRows()).singleElement().satisfies(row -> {
                assertThat(row.getDiscountAmount()).isEqualByComparingTo("-30.00");
                assertThat(row.getReversalOfId()).isEqualTo(1L);
                assertThat(row.getOrderReturnId()).isEqualTo(900L);
            });
            verify(promotionRepository).releaseBudget(100L, new BigDecimal("30.00"));
        }

        @Test
        @DisplayName("the budget comes back but the redemption count does not")
        void countIsNotReleased() {
            // The customer kept two of three items — they did have the promotion, and a
            // per-customer cap must go on saying so.
            when(redemptionRepository.findLiveForOrderItem(10L))
                    .thenReturn(List.of(redemption(1, 100L, 10L, "90.00")));

            promotions.reverseForReturn(500L, 900L, 7L,
                    List.of(new PromotionRedemptionService.ReturnedLine(10L, new BigDecimal("0.333333"))));

            verify(promotionRepository).releaseBudget(anyLong(), any());
            verify(promotionRepository, never()).release(anyLong(), any());
        }

        @Test
        @DisplayName("a second partial return only gives back what the first one left")
        void neverOvershoots() {
            // 60 of a 90 discount is already back; returning "the whole line" must give 30, not 90.
            when(redemptionRepository.findLiveForOrderItem(10L))
                    .thenReturn(List.of(redemption(1, 100L, 10L, "90.00")));
            when(redemptionRepository.reversedSoFar(1L)).thenReturn(new BigDecimal("-60.00"));

            promotions.reverseForReturn(500L, 901L, 7L,
                    List.of(new PromotionRedemptionService.ReturnedLine(10L, BigDecimal.ONE)));

            assertThat(savedRows()).singleElement()
                    .satisfies(row -> assertThat(row.getDiscountAmount()).isEqualByComparingTo("-30.00"));
            verify(promotionRepository).releaseBudget(100L, new BigDecimal("30.00"));
        }

        @Test
        @DisplayName("a line already given back in full is left alone")
        void nothingLeft() {
            when(redemptionRepository.findLiveForOrderItem(10L))
                    .thenReturn(List.of(redemption(1, 100L, 10L, "90.00")));
            when(redemptionRepository.reversedSoFar(1L)).thenReturn(new BigDecimal("-90.00"));

            promotions.reverseForReturn(500L, 902L, 7L,
                    List.of(new PromotionRedemptionService.ReturnedLine(10L, BigDecimal.ONE)));

            verify(redemptionRepository, never()).saveAll(any());
            verify(promotionRepository, never()).releaseBudget(anyLong(), any());
        }

        @Test
        @DisplayName("two promotions stacked on one line each give back their own share")
        void stackedLine() {
            when(redemptionRepository.findLiveForOrderItem(10L)).thenReturn(List.of(
                    redemption(1, 100L, 10L, "40.00"),
                    redemption(2, 200L, 10L, "10.00")));

            promotions.reverseForReturn(500L, 900L, 7L,
                    List.of(new PromotionRedemptionService.ReturnedLine(10L, new BigDecimal("0.500000"))));

            assertThat(savedRows()).extracting(PromotionRedemption::getPromotionId, PromotionRedemption::getDiscountAmount)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(100L, new BigDecimal("-20.00")),
                            org.assertj.core.groups.Tuple.tuple(200L, new BigDecimal("-5.00")));
            verify(promotionRepository).releaseBudget(100L, new BigDecimal("20.00"));
            verify(promotionRepository).releaseBudget(200L, new BigDecimal("5.00"));
        }

        @Test
        @DisplayName("a line with no promotion on it produces nothing")
        void noPromotion() {
            when(redemptionRepository.findLiveForOrderItem(10L)).thenReturn(List.of());

            promotions.reverseForReturn(500L, 900L, 7L,
                    List.of(new PromotionRedemptionService.ReturnedLine(10L, BigDecimal.ONE)));

            verify(redemptionRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("loyalty points")
    class Points {

        @Test
        @DisplayName("points earned come back in proportion to the refund")
        void clawsBackEarned() {
            when(loyaltyTransactionRepository.findByOrderIdAndReversedAtIsNull(500L)).thenReturn(List.of(
                    LoyaltyTransaction.builder().id(1L).customerId(42L).orderId(500L)
                            .type(LoyaltyTransaction.Type.EARN).points(1000).balanceAfter(1000)
                            .at(LocalDateTime.now()).build()));
            when(accountRepository.findByCustomerId(42L)).thenReturn(Optional.of(
                    LoyaltyAccount.builder().id(1L).customerId(42L).pointsBalance(1000).lifetimePoints(1000)
                            .updatedAt(LocalDateTime.now()).build()));

            loyalty.clawBackForReturn(500L, 7L, new BigDecimal("0.250000"));

            ArgumentCaptor<LoyaltyTransaction> row = ArgumentCaptor.forClass(LoyaltyTransaction.class);
            verify(loyaltyTransactionRepository).save(row.capture());
            assertThat(row.getValue().getType()).isEqualTo(LoyaltyTransaction.Type.REVERSAL);
            assertThat(row.getValue().getPoints()).isEqualTo(-250);
            assertThat(row.getValue().getBalanceAfter()).isEqualTo(750);
        }

        @Test
        @DisplayName("points the customer spent are left alone — the refund already gives that money back")
        void spentPointsUntouched() {
            when(loyaltyTransactionRepository.findByOrderIdAndReversedAtIsNull(500L)).thenReturn(List.of(
                    LoyaltyTransaction.builder().id(1L).customerId(42L).orderId(500L)
                            .type(LoyaltyTransaction.Type.REDEEM).points(-200).balanceAfter(300)
                            .at(LocalDateTime.now()).build()));

            loyalty.clawBackForReturn(500L, 7L, BigDecimal.ONE);

            verify(loyaltyTransactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("lifetime points drop too, so a tier earned on returned goods is not kept")
        void lifetimeDropsWithIt() {
            when(loyaltyTransactionRepository.findByOrderIdAndReversedAtIsNull(500L)).thenReturn(List.of(
                    LoyaltyTransaction.builder().id(1L).customerId(42L).orderId(500L)
                            .type(LoyaltyTransaction.Type.EARN).points(1000).balanceAfter(5000)
                            .at(LocalDateTime.now()).build()));
            when(accountRepository.findByCustomerId(42L)).thenReturn(Optional.of(
                    LoyaltyAccount.builder().id(1L).customerId(42L).pointsBalance(5000).lifetimePoints(5000)
                            .updatedAt(LocalDateTime.now()).build()));

            loyalty.clawBackForReturn(500L, 7L, BigDecimal.ONE);

            ArgumentCaptor<LoyaltyAccount> account = ArgumentCaptor.forClass(LoyaltyAccount.class);
            verify(accountRepository).save(account.capture());
            assertThat(account.getValue().getPointsBalance()).isEqualTo(4000);
            assertThat(account.getValue().getLifetimePoints()).isEqualTo(4000);
        }

        @Test
        @DisplayName("an order that earned nothing needs nothing given back")
        void nothingEarned() {
            when(loyaltyTransactionRepository.findByOrderIdAndReversedAtIsNull(500L)).thenReturn(List.of());

            loyalty.clawBackForReturn(500L, 7L, BigDecimal.ONE);

            verify(accountRepository, never()).save(any());
        }
    }
}
