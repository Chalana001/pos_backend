package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.StackingMode;
import com.chala.posapp.promotion.engine.LineDecision.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine on its own, no entities, no service, no mocks. The pricing outcomes themselves are
 * covered by {@code PromotionServiceTest}, which drives the same code through the entity mapping;
 * this pins the parts that are new with the extraction: the reason trace and the snapshot's own
 * date and branch checks.
 */
class PromotionEvaluatorTest {

    private static final int TWO_PIECES = 2000;

    private static PricingLine line(long itemId, double unitPrice, double cost) {
        return new PricingLine(itemId, ItemType.NORMAL, 3L, 2L,
                BigDecimal.valueOf(unitPrice), BigDecimal.valueOf(cost), TWO_PIECES,
                DiscountType.NONE, BigDecimal.ZERO);
    }

    private static PromotionSnapshot itemPromo(long id, String name, double percent, long itemId, Long branchId) {
        return new PromotionSnapshot(id, name, PromotionScope.ITEM, DiscountType.PERCENT,
                BigDecimal.valueOf(percent), BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59),
                branchId, 0, null, false,
                PromotionEffectType.DISCOUNT, null, null, StackingMode.BEST_ONLY, true,
                List.of(new TargetSnapshot(itemId, null, null, null, null, null, null)), List.of(), List.of());
    }

    private static PromotionSnapshot billPromo(long id, double percent) {
        return new PromotionSnapshot(id, "Bill " + id, PromotionScope.BILL, DiscountType.PERCENT,
                BigDecimal.valueOf(percent), BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59),
                null, 0, null, false,
                PromotionEffectType.DISCOUNT, null, null, StackingMode.BEST_ONLY, true,
                List.of(), List.of(), List.of());
    }

    private static Map<Long, LineDecision> byPromotion(List<LineDecision> decisions) {
        return decisions.stream().collect(Collectors.toMap(LineDecision::promotionId, Function.identity()));
    }

    @Nested
    @DisplayName("the trace names every promotion and says what happened to it")
    class Trace {

        @Test
        @DisplayName("winner, runner-up, wrong branch, wrong scope and below cost are all told apart")
        void oneDecisionPerCandidate() {
            PromotionSnapshot winner = itemPromo(1, "30% off", 30, 7L, null);
            PromotionSnapshot runnerUp = itemPromo(2, "10% off", 10, 7L, null);
            PromotionSnapshot otherBranch = itemPromo(3, "Branch 9", 50, 7L, 9L);
            PromotionSnapshot tooDeep = itemPromo(4, "Half price", 50, 7L, null);
            PromotionSnapshot bill = billPromo(5, 5);

            // cost 60: 30% off 100 leaves 70 (fine); 50% leaves 50 (below cost).
            LineEvaluation result = PromotionEvaluator.evaluateLine(
                    line(7, 100, 60), 1L, BigDecimal.valueOf(200),
                    List.of(winner, runnerUp, otherBranch, tooDeep, bill));

            Map<Long, LineDecision> trace = byPromotion(result.decisions());
            assertThat(trace).hasSize(5);
            assertThat(trace.get(1L).outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(trace.get(1L).discount()).isEqualByComparingTo("60.00");
            assertThat(trace.get(2L).outcome()).isEqualTo(Outcome.LOST_TO_BETTER);
            assertThat(trace.get(2L).discount()).isEqualByComparingTo("20.00");
            assertThat(trace.get(3L).outcome()).isEqualTo(Outcome.BRANCH_MISMATCH);
            assertThat(trace.get(4L).outcome()).isEqualTo(Outcome.BELOW_COST);
            assertThat(trace.get(5L).outcome()).isEqualTo(Outcome.WRONG_SCOPE);

            assertThat(result.application().promotionId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a promotion that led and was then beaten is re-marked as the loser, not left as applied")
        void earlierLeaderIsDemoted() {
            PromotionSnapshot first = itemPromo(1, "10% off", 10, 7L, null);
            PromotionSnapshot second = itemPromo(2, "20% off", 20, 7L, null);

            LineEvaluation result = PromotionEvaluator.evaluateLine(
                    line(7, 100, 10), 1L, BigDecimal.valueOf(200), List.of(first, second));

            Map<Long, LineDecision> trace = byPromotion(result.decisions());
            assertThat(trace.get(1L).outcome()).isEqualTo(Outcome.LOST_TO_BETTER);
            assertThat(trace.get(2L).outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(result.decisions()).filteredOn(d -> d.outcome() == Outcome.APPLIED).hasSize(1);
        }

        @Test
        @DisplayName("below the minimum bill is its own reason")
        void minimumBill() {
            PromotionSnapshot promo = new PromotionSnapshot(1L, "Spend 5000", PromotionScope.ITEM,
                    DiscountType.PERCENT, BigDecimal.TEN, BigDecimal.valueOf(5000), BigDecimal.ZERO,
                    LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59),
                    null, 0, null, false,
                    PromotionEffectType.DISCOUNT, null, null, StackingMode.BEST_ONLY, true,
                    List.of(new TargetSnapshot(7L, null, null, null, null, null, null)), List.of(), List.of());

            LineEvaluation result = PromotionEvaluator.evaluateLine(
                    line(7, 100, 10), 1L, BigDecimal.valueOf(200), List.of(promo));

            assertThat(result.decisions()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.BELOW_MIN_BILL);
            assertThat(result.application().promotionApplied()).isFalse();
        }

        @Test
        @DisplayName("at bill level a promotion beaten by the manual discount says so")
        void lostToManual() {
            OrderEvaluation result = PromotionEvaluator.evaluateOrder(
                    1L, null, BigDecimal.valueOf(10_000), BigDecimal.valueOf(2_000), List.of(), false, List.of(billPromo(1, 10)));

            assertThat(result.application().promotionApplied()).isFalse();
            assertThat(result.application().appliedDiscountAmount()).isEqualTo(2_000.0);
            assertThat(result.decisions()).singleElement()
                    .satisfies(d -> {
                        assertThat(d.outcome()).isEqualTo(Outcome.LOST_TO_MANUAL);
                        assertThat(d.discount()).isEqualByComparingTo("1000.00");
                    });
        }

        @Test
        @DisplayName("an empty candidate list gives an empty trace and list price")
        void nothingToSay() {
            LineEvaluation result = PromotionEvaluator.evaluateLine(
                    line(7, 100, 10), 1L, BigDecimal.valueOf(200), List.of());

            assertThat(result.decisions()).isEmpty();
            assertThat(result.application().finalLineTotal()).isEqualTo(200.0);
        }
    }

    @Nested
    @DisplayName("snapshot date and branch checks")
    class SnapshotChecks {

        private final PromotionSnapshot promo = itemPromo(1, "Jan-Dec", 10, 7L, 4L);

        @Test
        @DisplayName("running is inclusive at both ends of the window")
        void inclusiveWindow() {
            assertThat(promo.isRunningAt(LocalDateTime.of(2026, 1, 1, 0, 0))).isTrue();
            assertThat(promo.isRunningAt(LocalDateTime.of(2026, 12, 31, 23, 59))).isTrue();
            assertThat(promo.isRunningAt(LocalDateTime.of(2025, 12, 31, 23, 59))).isFalse();
            assertThat(promo.isRunningAt(LocalDateTime.of(2027, 1, 1, 0, 0))).isFalse();
        }

        @Test
        @DisplayName("a branch-pinned promotion covers only that branch; an unpinned one covers all")
        void branchCoverage() {
            assertThat(promo.coversBranch(4L)).isTrue();
            assertThat(promo.coversBranch(5L)).isFalse();
            assertThat(itemPromo(2, "Everywhere", 10, 7L, null).coversBranch(5L)).isTrue();
        }
    }

    @Test
    @DisplayName("money arithmetic is exact where double was not")
    void bigDecimalArithmetic() {
        // 0.1 + 0.2 territory: three lines at 33.33 with 10% off must come to a clean total.
        PricingLine l = new PricingLine(7L, ItemType.NORMAL, 3L, 2L,
                BigDecimal.valueOf(33.33), BigDecimal.ONE, 3000, DiscountType.NONE, BigDecimal.ZERO);
        LineEvaluation result = PromotionEvaluator.evaluateLine(
                l, 1L, BigDecimal.valueOf(99.99), List.of(itemPromo(1, "10%", 10, 7L, null)));

        assertThat(result.application().baseLineTotal()).isEqualTo(99.99);
        assertThat(result.application().promotionDiscountAmount()).isEqualTo(10.0);
        assertThat(result.application().finalLineTotal()).isEqualTo(89.99);
    }
}
