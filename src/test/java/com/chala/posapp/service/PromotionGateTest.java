package com.chala.posapp.service;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.Promotion;
import com.chala.posapp.entity.PromotionCode;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionRedemption;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.StackingMode;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.promotion.engine.LineDecision;
import com.chala.posapp.promotion.engine.LineDecision.Outcome;
import com.chala.posapp.promotion.engine.PromotionSnapshot;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate in front of the engine, codes, caps, budgets, per-customer limits, and the ledger
 * writer behind it. Both are where a promotion becomes safe to leave running unattended.
 */
class PromotionGateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 12, 0);

    private PromotionRepository promotionRepository;
    private PromotionCodeRepository codeRepository;
    private PromotionRedemptionRepository redemptionRepository;
    private PromotionGate gate;
    private CustomerSegmentService segmentService;
    private PromotionRedemptionService ledger;

    @BeforeEach
    void setUp() {
        promotionRepository = mock(PromotionRepository.class);
        codeRepository = mock(PromotionCodeRepository.class);
        redemptionRepository = mock(PromotionRedemptionRepository.class);
        segmentService = mock(CustomerSegmentService.class);
        when(segmentService.segmentIdsForCustomer(any())).thenReturn(java.util.Set.of());
        gate = new PromotionGate(promotionRepository, codeRepository, redemptionRepository, segmentService);
        ledger = new PromotionRedemptionService(promotionRepository, codeRepository, redemptionRepository);
    }

    private static PromotionSnapshot snapshot(long id, String name) {
        return new PromotionSnapshot(id, name, PromotionScope.BILL, DiscountType.PERCENT, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, NOW.minusDays(1), NOW.plusDays(1), null, 0, null, false,
                PromotionEffectType.DISCOUNT, null, null, StackingMode.BEST_ONLY, true,
                List.of(), List.of(), List.of());
    }

    /** Row shape of {@code limitStateRaw}: id, maxTotal, maxPerCustomer, budget, timesRedeemed, budgetConsumed, codeCount. */
    private void limits(long id, Integer maxTotal, Integer perCustomer, Double budget, int used, double consumed, long codes) {
        when(promotionRepository.limitStateRaw(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{id, maxTotal, perCustomer, budget == null ? null : BigDecimal.valueOf(budget),
                        used, BigDecimal.valueOf(consumed), codes}));
    }

    private PromotionCode code(long promotionId, String value, Integer maxUses, int used, boolean active) {
        Promotion promotion = Promotion.builder().id(promotionId).name("Promo " + promotionId).build();
        PromotionCode code = PromotionCode.builder()
                .id(100L).promotion(promotion).code(value).maxRedemptions(maxUses).active(active).build();
        code.setRedemptionsUsed(used);
        return code;
    }

    @Nested
    @DisplayName("caps and budgets")
    class Caps {

        @Test
        @DisplayName("a promotion under its caps passes through untouched")
        void underCaps() {
            limits(1, 100, null, 5000.0, 10, 500, 0);

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), null, null, NOW);

            assertThat(result.eligible()).hasSize(1);
            assertThat(result.excluded()).isEmpty();
            assertThat(result.codeStatus()).isNull();
        }

        @Test
        @DisplayName("a promotion redeemed as often as it allows is out, and says why")
        void limitReached() {
            limits(1, 100, null, null, 100, 0, 0);

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), null, null, NOW);

            assertThat(result.eligible()).isEmpty();
            assertThat(result.excluded()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.LIMIT_REACHED);
        }

        @Test
        @DisplayName("a promotion that has spent its budget is out")
        void budgetExhausted() {
            limits(1, null, null, 5000.0, 3, 5000, 0);

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), null, null, NOW);

            assertThat(result.excluded()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.BUDGET_EXHAUSTED);
        }

        @Test
        @DisplayName("a per-customer cap is enforced when a customer is on the sale")
        void perCustomer() {
            limits(1, null, 1, null, 0, 0, 0);
            when(redemptionRepository.countOrdersForCustomer(1L, 42L)).thenReturn(1L);

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), null, 42L, NOW);

            assertThat(result.excluded()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.CUSTOMER_LIMIT_REACHED);
        }

        @Test
        @DisplayName("a per-customer cap cannot be enforced on a walk-in, so it does not refuse them")
        void perCustomerWalkIn() {
            limits(1, null, 1, null, 0, 0, 0);

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), null, null, NOW);

            assertThat(result.eligible()).hasSize(1);
            verify(redemptionRepository, never()).countOrdersForCustomer(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("codes")
    class Codes {

        @Test
        @DisplayName("a code-gated promotion is out unless its code is presented")
        void codeRequired() {
            limits(1, null, null, null, 0, 0, 3);

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), null, null, NOW);

            assertThat(result.excluded()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.CODE_REQUIRED);
        }

        @Test
        @DisplayName("presenting a valid code lets its promotion through and reports it as valid")
        void validCode() {
            limits(1, null, null, null, 0, 0, 3);
            when(codeRepository.findByCodeIgnoreCase("WELCOME10")).thenReturn(Optional.of(code(1, "WELCOME10", 100, 5, true)));

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), "welcome10", null, NOW);

            assertThat(result.eligible()).hasSize(1);
            assertThat(result.code()).isNotNull();
            assertThat(result.codeStatus().isValid()).isTrue();
            assertThat(result.codeStatus().getCode()).isEqualTo("WELCOME10");
            assertThat(result.codeStatus().getPromotionName()).isEqualTo("Promo 1");
        }

        @Test
        @DisplayName("a code for a different promotion does not unlock this one")
        void wrongPromotionsCode() {
            limits(1, null, null, null, 0, 0, 3);
            when(codeRepository.findByCodeIgnoreCase("OTHER")).thenReturn(Optional.of(code(9, "OTHER", null, 0, true)));

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), "OTHER", null, NOW);

            assertThat(result.eligible()).isEmpty();
            assertThat(result.codeStatus().isValid()).isFalse();
            assertThat(result.codeStatus().getMessage()).contains("not valid right now");
        }

        @Test
        @DisplayName("an unknown code is refused with a plain message")
        void unknownCode() {
            limits(1, null, null, null, 0, 0, 0);
            when(codeRepository.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), "NOPE", null, NOW);

            assertThat(result.codeStatus().isValid()).isFalse();
            assertThat(result.codeStatus().getMessage()).isEqualTo("Unknown code");
            // An automatic promotion is unaffected by a bad code being typed.
            assertThat(result.eligible()).hasSize(1);
        }

        @Test
        @DisplayName("a fully redeemed code says so rather than 'invalid'")
        void exhaustedCode() {
            limits(1, null, null, null, 0, 0, 1);
            when(codeRepository.findByCodeIgnoreCase("LAST")).thenReturn(Optional.of(code(1, "LAST", 1, 1, true)));

            PromotionGate.Result result = gate.gate(List.of(snapshot(1, "A")), "LAST", null, NOW);

            assertThat(result.codeStatus().getMessage()).isEqualTo("This code has been fully redeemed");
        }

        @Test
        @DisplayName("a switched-off code is refused whatever the dates say")
        void inactiveCode() {
            limits(1, null, null, null, 0, 0, 1);
            when(codeRepository.findByCodeIgnoreCase("OFF")).thenReturn(Optional.of(code(1, "OFF", null, 0, false)));

            assertThat(gate.gate(List.of(snapshot(1, "A")), "OFF", null, NOW).codeStatus().getMessage())
                    .isEqualTo("This code has been switched off");
        }

        @Test
        @DisplayName("a once-per-customer code refuses the second use by the same customer")
        void perCustomerCode() {
            limits(1, null, null, null, 0, 0, 1);
            PromotionCode once = code(1, "ONCE", null, 0, true);
            once.setCodeType(PromotionCode.CodeType.PER_CUSTOMER);
            when(codeRepository.findByCodeIgnoreCase("ONCE")).thenReturn(Optional.of(once));
            when(redemptionRepository.countOrdersForCodeAndCustomer(100L, 42L)).thenReturn(1L);

            assertThat(gate.gate(List.of(snapshot(1, "A")), "ONCE", 42L, NOW).codeStatus().getMessage())
                    .isEqualTo("This customer has already used this code");
            assertThat(gate.gate(List.of(snapshot(1, "A")), "ONCE", null, NOW).codeStatus().getMessage())
                    .isEqualTo("This code needs a customer on the sale");
        }
    }

    @Nested
    @DisplayName("segments")
    class Segments {

        private PromotionSnapshot segmentPromo(long id, long segmentId) {
            return new PromotionSnapshot(id, "Loyal customers", PromotionScope.CUSTOMER, DiscountType.PERCENT,
                    BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, NOW.minusDays(1), NOW.plusDays(1), null, 0,
                    null, false, PromotionEffectType.DISCOUNT, null, null, StackingMode.BEST_ONLY, true,
                    List.of(new com.chala.posapp.promotion.engine.TargetSnapshot(
                            null, null, null, null, null, null, null, segmentId)),
                    List.of(), List.of());
        }

        @Test
        @DisplayName("a segment the customer is in is rewritten to name them, so the engine matches on an id")
        void resolvedToCustomer() {
            limits(1, null, null, null, 0, 0, 0);
            when(segmentService.segmentIdsForCustomer(42L)).thenReturn(java.util.Set.of(5L));

            PromotionGate.Result result = gate.gate(List.of(segmentPromo(1, 5L)), null, 42L, NOW);

            assertThat(result.eligible()).singleElement().satisfies(promotion ->
                    assertThat(promotion.targets()).singleElement().satisfies(target -> {
                        assertThat(target.customerId()).isEqualTo(42L);
                        assertThat(target.segmentId()).isEqualTo(5L);
                    }));
        }

        @Test
        @DisplayName("a segment the customer is not in is left alone, so it simply fails to match")
        void notAMember() {
            limits(1, null, null, null, 0, 0, 0);
            when(segmentService.segmentIdsForCustomer(42L)).thenReturn(java.util.Set.of(9L));

            PromotionGate.Result result = gate.gate(List.of(segmentPromo(1, 5L)), null, 42L, NOW);

            assertThat(result.eligible()).singleElement().satisfies(promotion ->
                    assertThat(promotion.targets()).singleElement()
                            .satisfies(target -> assertThat(target.customerId()).isNull()));
        }

        @Test
        @DisplayName("a walk-in has no segments, so nothing is rewritten")
        void walkIn() {
            limits(1, null, null, null, 0, 0, 0);

            PromotionGate.Result result = gate.gate(List.of(segmentPromo(1, 5L)), null, null, NOW);

            assertThat(result.eligible()).singleElement().satisfies(promotion ->
                    assertThat(promotion.targets()).singleElement()
                            .satisfies(target -> assertThat(target.customerId()).isNull()));
        }
    }

    @Nested
    @DisplayName("the ledger")
    class Ledger {

        private final Order order = Order.builder().id(500L).customerId(42L).branchId(1L).cashierUserId(7L).build();

        private List<LineDecision> applied(long promotionId, String amount) {
            return List.of(new LineDecision(promotionId, "Promo " + promotionId, Outcome.APPLIED, new BigDecimal(amount)));
        }

        @Test
        @DisplayName("writes one row per applied promotion per line and consumes each cap once per order")
        void writesRowsAndConsumes() {
            when(promotionRepository.consume(anyLong(), any())).thenReturn(1);

            ledger.record(order, List.of(
                    new PromotionRedemptionService.LineRedemption(1L, 10L, applied(1, "20.00")),
                    new PromotionRedemptionService.LineRedemption(2L, 11L, applied(1, "30.00"))),
                    applied(2, "100.00"), null);

            verify(promotionRepository).consume(1L, new BigDecimal("50.00"));
            verify(promotionRepository).consume(2L, new BigDecimal("100.00"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PromotionRedemption>> rows = ArgumentCaptor.forClass(List.class);
            verify(redemptionRepository).saveAll(rows.capture());
            assertThat(rows.getValue()).hasSize(3);
            assertThat(rows.getValue()).filteredOn(r -> r.getLevel() == PromotionRedemption.Level.BILL).hasSize(1);
            assertThat(rows.getValue()).allSatisfy(r -> {
                assertThat(r.getOrderId()).isEqualTo(500L);
                assertThat(r.getCustomerId()).isEqualTo(42L);
                assertThat(r.getUserId()).isEqualTo(7L);
            });
        }

        @Test
        @DisplayName("a cap taken by another till since the preview refuses the sale rather than overshooting")
        void consumeRefused() {
            when(promotionRepository.consume(anyLong(), any())).thenReturn(0);

            assertThatThrownBy(() -> ledger.record(order,
                    List.of(new PromotionRedemptionService.LineRedemption(1L, 10L, applied(1, "20.00"))), List.of(), null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Promo 1");
            verify(redemptionRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("the presented code is consumed only if its promotion actually applied")
        void codeConsumedOnlyWhenUsed() {
            when(promotionRepository.consume(anyLong(), any())).thenReturn(1);
            when(codeRepository.consume(100L)).thenReturn(1);
            PromotionCode code = code(1, "WELCOME10", 10, 0, true);

            ledger.record(order, List.of(new PromotionRedemptionService.LineRedemption(1L, 10L, applied(2, "20.00"))),
                    List.of(), code);
            verify(codeRepository, never()).consume(anyLong());

            ledger.record(order, List.of(new PromotionRedemptionService.LineRedemption(1L, 10L, applied(1, "20.00"))),
                    List.of(), code);
            verify(codeRepository).consume(100L);
        }

        @Test
        @DisplayName("a refund reverses the rows and releases the caps and the code")
        void reversal() {
            PromotionRedemption a = PromotionRedemption.builder().promotionId(1L).promotionCodeId(100L)
                    .discountAmount(new BigDecimal("20.00")).build();
            PromotionRedemption b = PromotionRedemption.builder().promotionId(1L).promotionCodeId(100L)
                    .discountAmount(new BigDecimal("30.00")).build();
            when(redemptionRepository.findByOrderIdAndReversedAtIsNull(500L)).thenReturn(List.of(a, b));

            ledger.reverseForOrder(500L, 500L);

            verify(promotionRepository).release(1L, new BigDecimal("50.00"));
            verify(codeRepository).release(100L);
            verify(redemptionRepository).reverseForOrder(eq(500L), eq(500L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("nothing applied means nothing written and nothing consumed")
        void noOp() {
            ledger.record(order, List.of(new PromotionRedemptionService.LineRedemption(1L, 10L,
                    List.of(new LineDecision(1L, "A", Outcome.LOST_TO_BETTER, BigDecimal.TEN)))), List.of(), null);

            verify(promotionRepository, never()).consume(anyLong(), any());
            verify(redemptionRepository, never()).saveAll(any());
        }
    }
}
