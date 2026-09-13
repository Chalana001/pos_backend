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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mechanics added in Phase 2, buy-X-get-Y, fixed price, tiers, bundles, cheapest-free,
 * schedules and stacking, on the pure engine. Every existing pricing test still passes
 * unchanged; these pin what is new.
 */
class PromotionMechanicsTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 23, 59);

    /** A small builder so a fixture reads as what it is rather than twenty positional arguments. */
    private static final class Promo {
        long id = 1;
        String name = "Promo";
        PromotionScope scope = PromotionScope.ITEM;
        DiscountType type = DiscountType.PERCENT;
        BigDecimal value = BigDecimal.TEN;
        BigDecimal minBill = BigDecimal.ZERO;
        BigDecimal cap = BigDecimal.ZERO;
        PromotionEffectType effect = PromotionEffectType.DISCOUNT;
        BigDecimal buy;
        BigDecimal get;
        StackingMode stacking = StackingMode.BEST_ONLY;
        boolean manualOk = true;
        boolean belowCostOk = true;
        List<TargetSnapshot> targets = new ArrayList<>();
        List<TierSnapshot> tiers = new ArrayList<>();
        List<ScheduleSnapshot> schedules = new ArrayList<>();

        Promo id(long v) { id = v; return this; }
        Promo name(String v) { name = v; return this; }
        Promo scope(PromotionScope v) { scope = v; return this; }
        Promo rate(DiscountType t, double v) { type = t; value = BigDecimal.valueOf(v); return this; }
        Promo value(double v) { value = BigDecimal.valueOf(v); return this; }
        Promo cap(double v) { cap = BigDecimal.valueOf(v); return this; }
        Promo effect(PromotionEffectType v) { effect = v; return this; }
        Promo buy(double v) { buy = BigDecimal.valueOf(v); return this; }
        Promo get(double v) { get = BigDecimal.valueOf(v); return this; }
        Promo stacking(StackingMode v) { stacking = v; return this; }
        Promo noManual() { manualOk = false; return this; }
        Promo item(long itemId) { targets.add(new TargetSnapshot(itemId, null, null, null, null, null, null)); return this; }
        Promo category(long categoryId) { targets.add(new TargetSnapshot(null, categoryId, null, null, null, null, null)); return this; }
        Promo qtyTier(double minQty, DiscountType t, double v) { tiers.add(new TierSnapshot(BigDecimal.valueOf(minQty), null, t, BigDecimal.valueOf(v))); return this; }
        Promo amountTier(double minAmount, DiscountType t, double v) { tiers.add(new TierSnapshot(null, BigDecimal.valueOf(minAmount), t, BigDecimal.valueOf(v))); return this; }
        Promo schedule(int days, LocalTime from, LocalTime to) { schedules.add(new ScheduleSnapshot(days, from, to)); return this; }

        PromotionSnapshot build() {
            return new PromotionSnapshot(id, name, scope, type, value, minBill, cap, START, END, null, 0,
                    null, belowCostOk, effect, buy, get, stacking, manualOk,
                    List.copyOf(targets), List.copyOf(tiers), List.copyOf(schedules));
        }
    }

    private static PricingLine line(long itemId, long categoryId, double unitPrice, int pieces) {
        return new PricingLine(itemId, ItemType.NORMAL, 3L, categoryId,
                BigDecimal.valueOf(unitPrice), BigDecimal.ONE, pieces * 1000,
                DiscountType.NONE, BigDecimal.ZERO);
    }

    private static PricingLine lineWithManual(long itemId, double unitPrice, int pieces, DiscountType t, double v) {
        return new PricingLine(itemId, ItemType.NORMAL, 3L, 2L,
                BigDecimal.valueOf(unitPrice), BigDecimal.ONE, pieces * 1000, t, BigDecimal.valueOf(v));
    }

    private static PromotionApplication priceLine(PricingLine line, PromotionSnapshot... promos) {
        return PromotionEvaluator.evaluateLine(line, 1L, BigDecimal.valueOf(1_000_000), List.of(promos)).application();
    }

    private static LineEvaluation evaluate(PricingLine line, PromotionSnapshot... promos) {
        return PromotionEvaluator.evaluateLine(line, 1L, BigDecimal.valueOf(1_000_000), List.of(promos));
    }

    // ── buy X get Y free ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buy X get Y free")
    class Bogo {

        private final PromotionSnapshot buyTwoGetOne = new Promo().name("Buy 2 get 1")
                .effect(PromotionEffectType.BUY_X_GET_Y_FREE).buy(2).get(1).item(7).build();

        @Test
        @DisplayName("five units: one full group of three, one free")
        void oneFree() {
            PromotionApplication result = priceLine(line(7, 2, 100, 5), buyTwoGetOne);

            assertThat(result.promotionApplied()).isTrue();
            assertThat(result.promotionDiscountAmount()).isEqualTo(100.0);
            assertThat(result.finalLineTotal()).isEqualTo(400.0);
        }

        @Test
        @DisplayName("six units: two groups, two free")
        void twoFree() {
            assertThat(priceLine(line(7, 2, 100, 6), buyTwoGetOne).promotionDiscountAmount()).isEqualTo(200.0);
        }

        @Test
        @DisplayName("two units do not make a group, so nothing is given")
        void notEnoughForAGroup() {
            LineEvaluation result = evaluate(line(7, 2, 100, 2), buyTwoGetOne);

            assertThat(result.application().promotionApplied()).isFalse();
            assertThat(result.decisions()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.NO_DISCOUNT);
        }

        @Test
        @DisplayName("reports as a flat reduction the caller can replay")
        void replays() {
            PromotionApplication result = priceLine(line(7, 2, 100, 5), buyTwoGetOne);

            assertThat(result.discountType()).isEqualTo(DiscountType.FIXED);
            double replayed = (100 - result.discountValue()) * 5;
            assertThat(replayed).isCloseTo(result.finalLineTotal(), org.assertj.core.data.Offset.offset(0.01));
        }
    }

    // ── fixed price ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fixed price")
    class FixedPrice {

        private final PromotionSnapshot drinksAt100 = new Promo().name("All drinks 100")
                .scope(PromotionScope.CATEGORY).effect(PromotionEffectType.FIXED_PRICE).value(100).category(2).build();

        @Test
        @DisplayName("everything in the category sells at the fixed price")
        void categoryAtOnePrice() {
            assertThat(priceLine(line(7, 2, 150, 2), drinksAt100).finalLineTotal()).isEqualTo(200.0);
            assertThat(priceLine(line(8, 2, 450, 1), drinksAt100).finalLineTotal()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("an item already cheaper than the fixed price is left alone")
        void neverRaisesAPrice() {
            LineEvaluation result = evaluate(line(9, 2, 80, 1), drinksAt100);

            assertThat(result.application().promotionApplied()).isFalse();
            assertThat(result.application().finalLineTotal()).isEqualTo(80.0);
        }
    }

    // ── tiers ───────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("tiers")
    class Tiers {

        private final PromotionSnapshot qtyBreaks = new Promo().name("Bulk").effect(PromotionEffectType.TIERED)
                .qtyTier(3, DiscountType.PERCENT, 10)
                .qtyTier(6, DiscountType.PERCENT, 20)
                .item(7).build();

        @Test
        @DisplayName("the highest quantity break reached applies to the whole line")
        void quantityBreaks() {
            assertThat(priceLine(line(7, 2, 100, 4), qtyBreaks).promotionDiscountAmount()).isEqualTo(40.0);
            assertThat(priceLine(line(7, 2, 100, 7), qtyBreaks).promotionDiscountAmount()).isEqualTo(140.0);
        }

        @Test
        @DisplayName("below the lowest break is its own reason, not a zero discount")
        void belowLowestBreak() {
            LineEvaluation result = evaluate(line(7, 2, 100, 2), qtyBreaks);

            assertThat(result.application().promotionApplied()).isFalse();
            assertThat(result.decisions()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.NO_TIER_REACHED);
        }

        @Test
        @DisplayName("spend ladders at bill level take the highest step the bill reaches")
        void spendLadder() {
            PromotionSnapshot ladder = new Promo().name("Spend & save").scope(PromotionScope.BILL)
                    .effect(PromotionEffectType.TIERED)
                    .amountTier(5_000, DiscountType.FIXED, 500)
                    .amountTier(10_000, DiscountType.FIXED, 1_200)
                    .build();

            assertThat(order(7_000, ladder).application().promotionDiscountAmount()).isEqualTo(500.0);
            assertThat(order(12_000, ladder).application().promotionDiscountAmount()).isEqualTo(1_200.0);
            assertThat(order(3_000, ladder).decisions()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.NO_TIER_REACHED);
        }

        private OrderEvaluation order(double total, PromotionSnapshot promo) {
            return PromotionEvaluator.evaluateOrder(1L, null, BigDecimal.valueOf(total), BigDecimal.ZERO,
                    List.of(), false, List.of(promo));
        }
    }

    // ── cart-level: bundles and cheapest free ───────────────────────────────────────────

    @Nested
    @DisplayName("bundles and cheapest-free")
    class CartLevel {

        // A: 450 x2, B: 280 x2, C (other category): 1000 x1
        private final List<PricingLine> cart = List.of(
                line(7, 2, 450, 2), line(8, 2, 280, 2), line(9, 5, 1_000, 1));

        private OrderEvaluation order(PromotionSnapshot promo) {
            BigDecimal total = BigDecimal.valueOf(450 * 2 + 280 * 2 + 1_000);
            return PromotionEvaluator.evaluateOrder(1L, null, total, BigDecimal.ZERO, cart, false, List.of(promo));
        }

        @Test
        @DisplayName("any three for 1,000: the dearest three make the bundle, the fourth is left")
        void bundle() {
            PromotionSnapshot anyThree = new Promo().name("Any 3 for 1000").scope(PromotionScope.CATEGORY)
                    .effect(PromotionEffectType.BUNDLE).buy(3).value(1_000).category(2).build();

            OrderEvaluation result = order(anyThree);

            // dearest three: 450 + 450 + 280 = 1180, charged 1000 -> 180 off
            assertThat(result.application().promotionApplied()).isTrue();
            assertThat(result.application().promotionDiscountAmount()).isEqualTo(180.0);
        }

        @Test
        @DisplayName("buy three, cheapest free: the cheapest of the dearest three")
        void cheapestFree() {
            PromotionSnapshot promo = new Promo().name("3 for 2").scope(PromotionScope.CATEGORY)
                    .effect(PromotionEffectType.CHEAPEST_FREE).buy(3).category(2).build();

            assertThat(order(promo).application().promotionDiscountAmount()).isEqualTo(280.0);
        }

        @Test
        @DisplayName("lines outside the targets are not counted, however dear")
        void ineligibleLinesIgnored() {
            PromotionSnapshot onlyItem7 = new Promo().name("2 for 800").scope(PromotionScope.ITEM)
                    .effect(PromotionEffectType.BUNDLE).buy(2).value(800).item(7).build();

            // 450 + 450 = 900 -> 100 off; the 1000 line is not in the set
            assertThat(order(onlyItem7).application().promotionDiscountAmount()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("a bundle priced above what the items cost gives nothing")
        void bundleNeverCostsMore() {
            PromotionSnapshot badBundle = new Promo().name("2 for 2000").scope(PromotionScope.ITEM)
                    .effect(PromotionEffectType.BUNDLE).buy(2).value(2_000).item(7).build();

            assertThat(order(badBundle).application().promotionApplied()).isFalse();
        }

        @Test
        @DisplayName("a cart-level effect with item targets is asked about at bill level, not per line")
        void cartLevelIsWrongScopeOnALine() {
            PromotionSnapshot bundle = new Promo().effect(PromotionEffectType.BUNDLE).buy(2).value(800).item(7).build();

            assertThat(evaluate(line(7, 2, 450, 2), bundle).decisions()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.WRONG_SCOPE);
        }
    }

    // ── schedules ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("schedules")
    class Schedules {

        private static final int WEEKDAYS = 0b0011111; // Mon..Fri

        private final PromotionSnapshot lunch = new Promo().name("Weekday lunch")
                .schedule(WEEKDAYS, LocalTime.of(12, 0), LocalTime.of(14, 0)).item(7).build();

        @Test
        @DisplayName("runs inside the window on a scheduled day")
        void insideWindow() {
            assertThat(lunch.isRunningAt(LocalDateTime.of(2026, 6, 2, 13, 0))).isTrue(); // Tuesday
        }

        @Test
        @DisplayName("does not run on an unscheduled day or outside the hours")
        void outsideWindow() {
            assertThat(lunch.isRunningAt(LocalDateTime.of(2026, 6, 6, 13, 0))).isFalse(); // Saturday
            assertThat(lunch.isRunningAt(LocalDateTime.of(2026, 6, 2, 15, 0))).isFalse(); // Tuesday, too late
        }

        @Test
        @DisplayName("an overnight window covers late evening and the small hours")
        void overnight() {
            PromotionSnapshot lateNight = new Promo().name("Late night")
                    .schedule(0, LocalTime.of(22, 0), LocalTime.of(2, 0)).item(7).build();

            assertThat(lateNight.isRunningAt(LocalDateTime.of(2026, 6, 2, 23, 0))).isTrue();
            assertThat(lateNight.isRunningAt(LocalDateTime.of(2026, 6, 3, 1, 0))).isTrue();
            assertThat(lateNight.isRunningAt(LocalDateTime.of(2026, 6, 3, 12, 0))).isFalse();
        }

        @Test
        @DisplayName("no schedule means the date range alone decides")
        void noSchedule() {
            assertThat(new Promo().item(7).build().isRunningAt(LocalDateTime.of(2026, 6, 6, 3, 0))).isTrue();
        }
    }

    // ── stacking ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("stacking")
    class Stacking {

        @Test
        @DisplayName("a STACKABLE promotion adds to the winner; the discounts add, they do not compound")
        void stackableAdds() {
            PromotionSnapshot twenty = new Promo().id(1).name("20% off").rate(DiscountType.PERCENT, 20).item(7).build();
            PromotionSnapshot fiveEach = new Promo().id(2).name("5 off each").rate(DiscountType.FIXED, 5)
                    .stacking(StackingMode.STACKABLE).item(7).build();

            PromotionApplication result = priceLine(line(7, 2, 100, 2), twenty, fiveEach);

            // 40 (20% of 200) + 10 (5 x 2) = 50, not 20% then 5 off the reduced price
            assertThat(result.promotionDiscountAmount()).isEqualTo(50.0);
            assertThat(result.finalLineTotal()).isEqualTo(150.0);
            assertThat(result.appliedPromotionIds()).containsExactly(1L, 2L);
            assertThat(result.promotionId()).isEqualTo(1L);
            assertThat(result.promotionName()).isEqualTo("20% off + 5 off each");
            assertThat(result.discountType()).isEqualTo(DiscountType.FIXED);
        }

        @Test
        @DisplayName("stackables apply even when no BEST_ONLY promotion qualifies")
        void stackableAlone() {
            PromotionSnapshot fiveEach = new Promo().id(2).name("5 off each").rate(DiscountType.FIXED, 5)
                    .stacking(StackingMode.STACKABLE).item(7).build();

            assertThat(priceLine(line(7, 2, 100, 2), fiveEach).promotionDiscountAmount()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("an EXCLUSIVE winner stands alone and blocks the bill")
        void exclusiveWins() {
            PromotionSnapshot exclusive = new Promo().id(1).name("Members only").rate(DiscountType.PERCENT, 10)
                    .stacking(StackingMode.EXCLUSIVE).item(7).build();
            PromotionSnapshot smaller = new Promo().id(2).name("5%").rate(DiscountType.PERCENT, 5).item(7).build();
            PromotionSnapshot stackable = new Promo().id(3).name("5 off").rate(DiscountType.FIXED, 5)
                    .stacking(StackingMode.STACKABLE).item(7).build();

            LineEvaluation result = evaluate(line(7, 2, 100, 2), exclusive, smaller, stackable);

            assertThat(result.application().exclusive()).isTrue();
            assertThat(result.application().promotionDiscountAmount()).isEqualTo(20.0);
            assertThat(result.decisions()).extracting(LineDecision::promotionId, LineDecision::outcome)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(1L, Outcome.APPLIED),
                            org.assertj.core.groups.Tuple.tuple(2L, Outcome.LOST_TO_BETTER),
                            org.assertj.core.groups.Tuple.tuple(3L, Outcome.BLOCKED_BY_EXCLUSIVE));

            OrderEvaluation bill = PromotionEvaluator.evaluateOrder(1L, null, BigDecimal.valueOf(180), BigDecimal.ZERO,
                    List.of(), true, List.of(new Promo().id(9).scope(PromotionScope.BILL).rate(DiscountType.PERCENT, 5).build()));
            assertThat(bill.application().promotionApplied()).isFalse();
            assertThat(bill.decisions()).singleElement()
                    .extracting(LineDecision::outcome).isEqualTo(Outcome.BLOCKED_BY_EXCLUSIVE);
        }

        @Test
        @DisplayName("an EXCLUSIVE promotion that is beaten does not block anything")
        void exclusiveLoses() {
            PromotionSnapshot exclusive = new Promo().id(1).name("Members only").rate(DiscountType.PERCENT, 5)
                    .stacking(StackingMode.EXCLUSIVE).item(7).build();
            PromotionSnapshot bigger = new Promo().id(2).name("20%").rate(DiscountType.PERCENT, 20).item(7).build();
            PromotionSnapshot stackable = new Promo().id(3).name("5 off").rate(DiscountType.FIXED, 5)
                    .stacking(StackingMode.STACKABLE).item(7).build();

            PromotionApplication result = priceLine(line(7, 2, 100, 2), exclusive, bigger, stackable);

            assertThat(result.exclusive()).isFalse();
            assertThat(result.appliedPromotionIds()).containsExactly(2L, 3L);
            assertThat(result.promotionDiscountAmount()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("a promotion that forbids manual stacking drops the cashier's discount and says so")
        void manualBlocked() {
            PromotionSnapshot strict = new Promo().id(1).name("No extras").rate(DiscountType.PERCENT, 10).noManual().item(7).build();

            LineEvaluation result = evaluate(lineWithManual(7, 100, 2, DiscountType.PERCENT, 10), strict);

            assertThat(result.application().finalLineTotal()).isEqualTo(180.0);
            assertThat(result.application().manualDiscountAmount()).isZero();
            assertThat(result.decisions()).extracting(LineDecision::outcome)
                    .containsExactly(Outcome.APPLIED, Outcome.MANUAL_BLOCKED);
        }

        @Test
        @DisplayName("bill-level stackables add to the bill winner too")
        void billStacking() {
            PromotionSnapshot ten = new Promo().id(1).scope(PromotionScope.BILL).rate(DiscountType.PERCENT, 10).build();
            PromotionSnapshot flat = new Promo().id(2).scope(PromotionScope.BILL).rate(DiscountType.FIXED, 100)
                    .stacking(StackingMode.STACKABLE).build();

            OrderEvaluation result = PromotionEvaluator.evaluateOrder(1L, null, BigDecimal.valueOf(1_000), BigDecimal.ZERO,
                    List.of(), false, List.of(ten, flat));

            assertThat(result.application().promotionDiscountAmount()).isEqualTo(200.0);
            assertThat(result.application().appliedPromotionIds()).containsExactly(1L, 2L);
        }
    }
}
