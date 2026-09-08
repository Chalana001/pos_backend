package com.chala.posapp.service;

import com.chala.posapp.dto.loyalty.LoyaltySettingsDto;
import com.chala.posapp.entity.LoyaltyAccount;
import com.chala.posapp.entity.LoyaltySettings;
import com.chala.posapp.entity.LoyaltyTier;
import com.chala.posapp.entity.LoyaltyTransaction;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.LoyaltyAccountRepository;
import com.chala.posapp.repository.LoyaltySettingsRepository;
import com.chala.posapp.repository.LoyaltyTierRepository;
import com.chala.posapp.repository.LoyaltyTransactionRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The points maths. Every case here is one a shop would eventually notice on its own books —
 * paying points on money that never arrived, letting a redemption exceed a balance, or a
 * cancelled sale leaving the customer better off than before it.
 */
class LoyaltyServiceTest {

    private LoyaltySettingsRepository settingsRepository;
    private LoyaltyTierRepository tierRepository;
    private LoyaltyAccountRepository accountRepository;
    private LoyaltyTransactionRepository transactionRepository;
    private LoyaltyService service;

    @BeforeEach
    void setUp() {
        settingsRepository = mock(LoyaltySettingsRepository.class);
        tierRepository = mock(LoyaltyTierRepository.class);
        accountRepository = mock(LoyaltyAccountRepository.class);
        transactionRepository = mock(LoyaltyTransactionRepository.class);
        service = new LoyaltyService(settingsRepository, tierRepository, accountRepository, transactionRepository);
        when(tierRepository.findByActiveTrueOrderByMinLifetimePointsAsc()).thenReturn(List.of());
        when(accountRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void scheme(double earnRate, double pointValue, Integer minRedeem, Double maxPercent) {
        LoyaltySettings settings = LoyaltySettings.builder()
                .enabled(true)
                .pointsPerCurrency(BigDecimal.valueOf(earnRate))
                .currencyPerPoint(BigDecimal.valueOf(pointValue))
                .minRedemptionPoints(minRedeem == null ? 0 : minRedeem)
                .maxRedemptionPercent(maxPercent == null ? null : BigDecimal.valueOf(maxPercent))
                .roundEarnedDown(true)
                .build();
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(settings));
    }

    private void balance(long customerId, int points, int lifetime) {
        when(accountRepository.findByCustomerId(customerId)).thenReturn(Optional.of(
                LoyaltyAccount.builder().id(1L).customerId(customerId)
                        .pointsBalance(points).lifetimePoints(lifetime)
                        .updatedAt(LocalDateTime.now()).build()));
    }

    @Nested
    @DisplayName("redeeming")
    class Redeeming {

        @Test
        @DisplayName("points convert at the spend rate, not the earn rate")
        void spendRate() {
            // Earning 1 point per rupee and spending them back at 0.25 is a 25% return. The two
            // rates are separate precisely so this cannot be confused.
            scheme(1.0, 0.25, null, null);
            balance(42L, 1000, 1000);

            LoyaltyService.Redemption result = service.quoteRedemption(42L, 400, 5000);

            assertThat(result.points()).isEqualTo(400);
            assertThat(result.amount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("spending more points than the customer has is refused, not trimmed")
        void insufficientBalance() {
            scheme(1.0, 1.0, null, null);
            balance(42L, 300, 300);

            assertThatThrownBy(() -> service.quoteRedemption(42L, 500, 5000))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("has 300 points, not 500");
        }

        @Test
        @DisplayName("below the scheme minimum is refused with the minimum named")
        void belowMinimum() {
            scheme(1.0, 1.0, 100, null);
            balance(42L, 500, 500);

            assertThatThrownBy(() -> service.quoteRedemption(42L, 50, 5000))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("At least 100 points");
        }

        @Test
        @DisplayName("the percentage ceiling clamps the value, and only the points it used are charged")
        void percentCeiling() {
            // 1000 points are worth 1000, but only half of a 500 bill may be paid with points.
            scheme(1.0, 1.0, null, 50.0);
            balance(42L, 1000, 1000);

            LoyaltyService.Redemption result = service.quoteRedemption(42L, 1000, 500);

            assertThat(result.amount()).isEqualByComparingTo("250.00");
            // Charging all 1000 for 250 of value would quietly take 750 points for nothing.
            assertThat(result.points()).isEqualTo(250);
        }

        @Test
        @DisplayName("points cannot take more off than the bill is worth")
        void cannotExceedBill() {
            scheme(1.0, 1.0, null, null);
            balance(42L, 5000, 5000);

            assertThat(service.quoteRedemption(42L, 5000, 300).amount()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("a scheme that is switched off redeems nothing rather than failing")
        void disabled() {
            when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

            assertThat(service.quoteRedemption(42L, 500, 5000).isNothing()).isTrue();
        }

        @Test
        @DisplayName("a walk-in has no account to spend from")
        void walkIn() {
            scheme(1.0, 1.0, null, null);

            assertThat(service.quoteRedemption(null, 500, 5000).isNothing()).isTrue();
        }
    }

    @Nested
    @DisplayName("earning")
    class Earning {

        @Test
        @DisplayName("points are earned on what was actually paid, and rounded down")
        void earnsOnPaid() {
            scheme(0.5, 1.0, null, null);
            balance(42L, 0, 0);

            int earned = service.applyToSale(42L, 100L, 7L, 0, 999).earned();

            // 999 * 0.5 = 499.5 -> 499. Rounding up would pay for money that did not arrive.
            assertThat(earned).isEqualTo(499);
        }

        @Test
        @DisplayName("a tier multiplies what is earned")
        void tierMultiplier() {
            scheme(1.0, 1.0, null, null);
            when(accountRepository.findByCustomerId(42L)).thenReturn(Optional.of(
                    LoyaltyAccount.builder().id(1L).customerId(42L).pointsBalance(0)
                            .lifetimePoints(10_000).tierId(9L).updatedAt(LocalDateTime.now()).build()));
            when(tierRepository.findById(9L)).thenReturn(Optional.of(LoyaltyTier.builder()
                    .id(9L).name("Gold").minLifetimePoints(5000)
                    .earnMultiplier(BigDecimal.valueOf(1.5)).active(true).build()));

            assertThat(service.applyToSale(42L, 100L, 7L, 0, 1000).earned()).isEqualTo(1500);
        }

        @Test
        @DisplayName("spending and earning on the same sale write one row each and leave the right balance")
        void spendAndEarn() {
            scheme(1.0, 1.0, null, null);
            balance(42L, 500, 500);

            LoyaltyService.SaleOutcome outcome = service.applyToSale(42L, 100L, 7L, 200, 800);

            ArgumentCaptor<LoyaltyTransaction> rows = ArgumentCaptor.forClass(LoyaltyTransaction.class);
            verify(transactionRepository, org.mockito.Mockito.times(2)).save(rows.capture());
            assertThat(rows.getAllValues()).extracting(LoyaltyTransaction::getType, LoyaltyTransaction::getPoints)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(LoyaltyTransaction.Type.REDEEM, -200),
                            org.assertj.core.groups.Tuple.tuple(LoyaltyTransaction.Type.EARN, 800));
            // 500 - 200 + 800
            assertThat(rows.getAllValues().get(1).getBalanceAfter()).isEqualTo(1100);
            // And the same figure comes back to the caller, which is what the receipt prints.
            assertThat(outcome.balanceAfter()).isEqualTo(1100);
            assertThat(outcome.earned()).isEqualTo(800);
        }

        @Test
        @DisplayName("lifetime points climb with earning and are what the tier reads")
        void lifetimeDrivesTier() {
            scheme(1.0, 1.0, null, null);
            balance(42L, 100, 100);
            when(tierRepository.findByActiveTrueOrderByMinLifetimePointsAsc()).thenReturn(List.of(
                    LoyaltyTier.builder().id(1L).name("Bronze").minLifetimePoints(0).earnMultiplier(BigDecimal.ONE).active(true).build(),
                    LoyaltyTier.builder().id(2L).name("Gold").minLifetimePoints(500).earnMultiplier(BigDecimal.ONE).active(true).build()));

            service.applyToSale(42L, 100L, 7L, 0, 900);

            ArgumentCaptor<LoyaltyAccount> account = ArgumentCaptor.forClass(LoyaltyAccount.class);
            verify(accountRepository).save(account.capture());
            assertThat(account.getValue().getLifetimePoints()).isEqualTo(1000);
            assertThat(account.getValue().getTierId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a scheme that is off earns nothing and touches no account")
        void disabledEarnsNothing() {
            when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

            assertThat(service.applyToSale(42L, 100L, 7L, 0, 1000).earned()).isZero();
            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("cancelling a sale")
    class Reversal {

        @Test
        @DisplayName("earned points are taken back, spent points are returned, and the rows stay")
        void reverses() {
            scheme(1.0, 1.0, null, null);
            balance(42L, 1100, 1000);
            when(transactionRepository.findByOrderIdAndReversedAtIsNull(100L)).thenReturn(List.of(
                    LoyaltyTransaction.builder().id(1L).customerId(42L).orderId(100L)
                            .type(LoyaltyTransaction.Type.REDEEM).points(-200).balanceAfter(300).at(LocalDateTime.now()).build(),
                    LoyaltyTransaction.builder().id(2L).customerId(42L).orderId(100L)
                            .type(LoyaltyTransaction.Type.EARN).points(800).balanceAfter(1100).at(LocalDateTime.now()).build()));

            LoyaltyService.ReversalOutcome moved = service.reverseForOrder(100L, 7L);

            // Two directions, reported separately: netting them would tell a customer whose
            // return moved 800 out and 200 back that nothing happened to their points.
            assertThat(moved.takenBack()).isEqualTo(800);
            assertThat(moved.givenBack()).isEqualTo(200);
            // 1100 + 200 - 800
            assertThat(moved.balanceAfter()).isEqualTo(500);

            ArgumentCaptor<LoyaltyTransaction> saved = ArgumentCaptor.forClass(LoyaltyTransaction.class);
            verify(transactionRepository, org.mockito.Mockito.atLeast(4)).save(saved.capture());
            // The originals are marked rather than deleted — a balance history that edits itself
            // is not a history.
            assertThat(saved.getAllValues()).filteredOn(row -> row.getId() != null)
                    .allSatisfy(row -> assertThat(row.getReversedAt()).isNotNull());
            assertThat(saved.getAllValues()).filteredOn(row -> row.getType() == LoyaltyTransaction.Type.REVERSAL)
                    .extracting(LoyaltyTransaction::getPoints)
                    .containsExactly(200, -800);
        }

        @Test
        @DisplayName("an order with no loyalty movements is left alone")
        void nothingToReverse() {
            when(transactionRepository.findByOrderIdAndReversedAtIsNull(100L)).thenReturn(List.of());

            assertThat(service.reverseForOrder(100L, 7L).movedNothing()).isTrue();

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("a partial return takes back what came back and hands nothing over")
        void partialReturnTakesBackOnly() {
            scheme(1.0, 1.0, null, null);
            balance(42L, 1100, 1000);
            when(transactionRepository.findByOrderIdAndReversedAtIsNull(100L)).thenReturn(List.of(
                    LoyaltyTransaction.builder().id(1L).customerId(42L).orderId(100L)
                            .type(LoyaltyTransaction.Type.REDEEM).points(-200).balanceAfter(300).at(LocalDateTime.now()).build(),
                    LoyaltyTransaction.builder().id(2L).customerId(42L).orderId(100L)
                            .type(LoyaltyTransaction.Type.EARN).points(800).balanceAfter(1100).at(LocalDateTime.now()).build()));

            // A quarter of the sale came back.
            LoyaltyService.ReversalOutcome moved =
                    service.clawBackForReturn(100L, 7L, BigDecimal.valueOf(0.25));

            assertThat(moved.takenBack()).isEqualTo(200);
            // The points they spent stay spent: they paid with them and kept most of the goods.
            assertThat(moved.givenBack()).isZero();
            assertThat(moved.balanceAfter()).isEqualTo(900);
        }
    }

    @Test
    @DisplayName("a negative rate is refused rather than stored")
    void settingsValidated() {
        LoyaltySettingsDto request = LoyaltySettingsDto.builder()
                .enabled(true)
                .pointsPerCurrency(BigDecimal.valueOf(-1))
                .currencyPerPoint(BigDecimal.ONE)
                .build();

        assertThatThrownBy(() -> service.updateSettings(request, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be negative");
    }
}
