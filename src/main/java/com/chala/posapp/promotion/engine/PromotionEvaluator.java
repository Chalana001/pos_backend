package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.StackingMode;
import com.chala.posapp.promotion.engine.LineDecision.Outcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides what promotions do to a cart. Pure: no Spring, no repositories, no clock — every input
 * arrives as an argument, so the same call gives the same answer anywhere it runs.
 *
 * <p>Two passes. {@link #evaluateLine} prices one line: the line-level candidates compete, the
 * winner and any STACKABLE ones apply, then the cashier's manual discount goes on top.
 * {@link #evaluateOrder} then prices the bill with the lines already priced: bill/customer
 * promotions and the cart-level effects (bundles, cheapest-free) compete, the result is taken
 * over the manual bill discount if larger. Deliberately different semantics per level — stacked
 * on the line, best-of on the bill — and pinned by tests so it stays a decision, not an accident.
 *
 * <p>Stacking adds; it does not compound. Two stacked promotions worth 10 and 5 take 15 off,
 * which is what the receipt will say. Each is capped on its own first.
 */
public final class PromotionEvaluator {

    private PromotionEvaluator() {
    }

    /** A promotion that cleared every gate for this line or bill, waiting on selection. */
    private record Applicable(PromotionSnapshot promotion, TargetSnapshot target,
                              BigDecimal discount, BigDecimal uncapped, int decisionIndex) {
    }

    private record Selection(List<Applicable> chosen, boolean exclusive) {
    }

    // ── line ────────────────────────────────────────────────────────────────────────────

    /**
     * @param cartBaseSubtotal the whole cart at list price, before any discount. A promotion's
     *                         minimum bill is judged against this rather than a post-discount
     *                         figure, which would be circular.
     */
    public static LineEvaluation evaluateLine(PricingLine line, Long branchId, BigDecimal cartBaseSubtotal,
                                              List<PromotionSnapshot> promotions) {
        BigDecimal unitPrice = MoneyOps.nz(line.unitPrice());
        BigDecimal baseLineTotal = MoneyOps.lineTotal(line.itemType(), unitPrice, line.normalizedQty());
        BigDecimal safeSubtotal = MoneyOps.nonNegative(cartBaseSubtotal);
        DiscountType manualType = line.manualDiscountType() == null ? DiscountType.NONE : line.manualDiscountType();
        BigDecimal manualValue = MoneyOps.nz(line.manualDiscountValue());

        List<LineDecision> decisions = new ArrayList<>();
        List<Applicable> applicable = new ArrayList<>();

        for (PromotionSnapshot promotion : promotions == null ? List.<PromotionSnapshot>of() : promotions) {
            if (!promotion.isLineLevel()) {
                decisions.add(LineDecision.of(promotion, Outcome.WRONG_SCOPE));
                continue;
            }
            if (!promotion.coversBranch(branchId)) {
                decisions.add(LineDecision.of(promotion, Outcome.BRANCH_MISMATCH));
                continue;
            }
            if (safeSubtotal.compareTo(MoneyOps.nonNegative(promotion.minBillAmount())) < 0) {
                decisions.add(LineDecision.of(promotion, Outcome.BELOW_MIN_BILL));
                continue;
            }
            TargetSnapshot target = matchingTarget(promotion, line);
            if (target == null) {
                decisions.add(LineDecision.of(promotion, Outcome.NO_TARGET_MATCH));
                continue;
            }
            BigDecimal uncapped = lineEffectDiscount(promotion, target, line, unitPrice, baseLineTotal);
            if (uncapped == null) {
                decisions.add(LineDecision.of(promotion, Outcome.NO_TIER_REACHED));
                continue;
            }
            if (uncapped.signum() <= 0) {
                decisions.add(LineDecision.of(promotion, Outcome.NO_DISCOUNT));
                continue;
            }
            BigDecimal effectiveUnit = MoneyOps.unitPriceForLineDiscount(unitPrice, baseLineTotal, uncapped);
            Outcome marginVerdict = marginVerdict(promotion, line.costPrice(), effectiveUnit);
            if (marginVerdict != null) {
                decisions.add(LineDecision.of(promotion, marginVerdict));
                continue;
            }
            BigDecimal capped = capDiscount(promotion, uncapped);
            applicable.add(new Applicable(promotion, target, capped, uncapped, decisions.size()));
            decisions.add(null);
        }

        Selection selection = select(applicable);
        fillDecisions(decisions, applicable, selection);

        if (selection.chosen().isEmpty()) {
            BigDecimal manualUnit = MoneyOps.finalUnitPrice(unitPrice, manualType, manualValue);
            BigDecimal manualLineTotal = MoneyOps.lineTotal(line.itemType(), manualUnit, line.normalizedQty());
            BigDecimal manualDiscount = MoneyOps.round2(baseLineTotal.subtract(manualLineTotal));
            return new LineEvaluation(new PromotionApplication(
                    null, null, manualType,
                    manualType == DiscountType.NONE ? 0.0 : MoneyOps.nonNegative(manualValue).doubleValue(),
                    manualDiscount.doubleValue(), 0.0, manualDiscount.doubleValue(),
                    baseLineTotal.doubleValue(), manualLineTotal.doubleValue(),
                    false, List.of(), false
            ), List.copyOf(decisions));
        }

        BigDecimal promoDiscount = selection.chosen().stream()
                .map(Applicable::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .min(baseLineTotal);
        BigDecimal promoUnit = MoneyOps.unitPriceForLineDiscount(unitPrice, baseLineTotal, promoDiscount);

        // A promotion that forbids manual stacking wins that argument: the cashier's discount is
        // dropped for this line and the trace says so, so the till shows it not taking.
        Applicable manualBlocker = manualType == DiscountType.NONE ? null : selection.chosen().stream()
                .filter(a -> !a.promotion().allowManualStacking())
                .findFirst().orElse(null);
        DiscountType effectiveManualType = manualBlocker == null ? manualType : DiscountType.NONE;
        if (manualBlocker != null) {
            decisions.add(LineDecision.of(manualBlocker.promotion(), Outcome.MANUAL_BLOCKED));
        }

        BigDecimal stackedUnit = MoneyOps.finalUnitPrice(promoUnit, effectiveManualType, manualValue);
        BigDecimal promoLineTotal = MoneyOps.lineTotal(line.itemType(), promoUnit, line.normalizedQty());
        BigDecimal finalLineTotal = MoneyOps.lineTotal(line.itemType(), stackedUnit, line.normalizedQty());
        BigDecimal manualDiscount = MoneyOps.round2(promoLineTotal.subtract(finalLineTotal));
        BigDecimal appliedDiscount = MoneyOps.round2(baseLineTotal.subtract(finalLineTotal));

        Applicable primary = selection.chosen().stream()
                .max(Comparator.comparing(Applicable::discount))
                .orElseThrow();

        // The caller rebuilds the final unit price from the returned type/value pair, so the pair
        // has to reproduce the price actually charged. A rate survives only when nothing altered
        // it: a second promotion stacked on, a manual discount on top, the cap biting, an offer
        // price or any effect other than a plain rate — all mean the line no longer sells at that
        // rate, and the pair collapses to the flat per-unit reduction. Returning PERCENT there
        // would re-expand to the uncapped discount downstream.
        boolean single = selection.chosen().size() == 1;
        boolean capApplied = primary.discount().compareTo(primary.uncapped()) < 0;
        boolean plainRate = primary.target().hasOwnRate()
                || (primary.target().offerPrice() == null
                    && primary.promotion().effectType() == PromotionEffectType.DISCOUNT);
        boolean rateIntact = single && effectiveManualType == DiscountType.NONE && !capApplied && plainRate;
        DiscountType effectiveType = rateIntact ? effectiveRateType(primary) : DiscountType.FIXED;
        BigDecimal effectiveValue = rateIntact
                ? effectiveRateValue(primary)
                : MoneyOps.round2(MoneyOps.nonNegative(unitPrice.subtract(stackedUnit)));

        String name = single ? primary.promotion().name()
                : String.join(" + ", selection.chosen().stream().map(a -> a.promotion().name()).toList());
        List<Long> ids = selection.chosen().stream().map(a -> a.promotion().id()).toList();

        return new LineEvaluation(new PromotionApplication(
                primary.promotion().id(), name, effectiveType, effectiveValue.doubleValue(),
                manualDiscount.doubleValue(), promoDiscount.doubleValue(), appliedDiscount.doubleValue(),
                baseLineTotal.doubleValue(), finalLineTotal.doubleValue(),
                true, ids, selection.exclusive()
        ), List.copyOf(decisions));
    }

    // ── bill ────────────────────────────────────────────────────────────────────────────

    /**
     * @param pricedLines         the cart with each line already at its post-line-discount unit
     *                            price; bundles and cheapest-free count units from here
     * @param linesHaveExclusive  a line winner was EXCLUSIVE, so every bill-level promotion stands
     *                            down and only the manual discount remains
     */
    public static OrderEvaluation evaluateOrder(Long branchId, Long customerId, BigDecimal baseTotal,
                                                BigDecimal manualBillDiscount, List<PricingLine> pricedLines,
                                                boolean linesHaveExclusive, List<PromotionSnapshot> promotions) {
        BigDecimal safeBase = MoneyOps.nonNegative(baseTotal);
        BigDecimal manualDiscount = MoneyOps.round2(MoneyOps.nonNegative(manualBillDiscount).min(safeBase));
        List<PricingLine> lines = pricedLines == null ? List.of() : pricedLines;

        List<LineDecision> decisions = new ArrayList<>();
        List<Applicable> applicable = new ArrayList<>();

        for (PromotionSnapshot promotion : promotions == null ? List.<PromotionSnapshot>of() : promotions) {
            if (!promotion.isOrderLevel()) {
                decisions.add(LineDecision.of(promotion, Outcome.WRONG_SCOPE));
                continue;
            }
            if (linesHaveExclusive) {
                decisions.add(LineDecision.of(promotion, Outcome.BLOCKED_BY_EXCLUSIVE));
                continue;
            }
            if (!promotion.coversBranch(branchId)) {
                decisions.add(LineDecision.of(promotion, Outcome.BRANCH_MISMATCH));
                continue;
            }
            if (safeBase.compareTo(MoneyOps.nonNegative(promotion.minBillAmount())) < 0) {
                decisions.add(LineDecision.of(promotion, Outcome.BELOW_MIN_BILL));
                continue;
            }
            if (!matchesCustomer(promotion, customerId)) {
                decisions.add(LineDecision.of(promotion, Outcome.CUSTOMER_MISMATCH));
                continue;
            }
            BigDecimal uncapped = orderEffectDiscount(promotion, safeBase, lines);
            if (uncapped == null) {
                decisions.add(LineDecision.of(promotion, Outcome.NO_TIER_REACHED));
                continue;
            }
            if (uncapped.signum() <= 0) {
                decisions.add(LineDecision.of(promotion, Outcome.NO_DISCOUNT));
                continue;
            }
            BigDecimal capped = capDiscount(promotion, uncapped).min(safeBase);
            applicable.add(new Applicable(promotion, null, capped, uncapped, decisions.size()));
            decisions.add(null);
        }

        Selection selection = select(applicable);
        fillDecisions(decisions, applicable, selection);

        BigDecimal promoDiscount = selection.chosen().stream()
                .map(Applicable::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .min(safeBase);

        if (!selection.chosen().isEmpty() && promoDiscount.compareTo(manualDiscount) > 0) {
            Applicable primary = selection.chosen().stream()
                    .max(Comparator.comparing(Applicable::discount)).orElseThrow();
            boolean single = selection.chosen().size() == 1;
            String name = single ? primary.promotion().name()
                    : String.join(" + ", selection.chosen().stream().map(a -> a.promotion().name()).toList());
            return new OrderEvaluation(new PromotionOrderApplication(
                    primary.promotion().id(), name,
                    primary.promotion().discountType(), MoneyOps.nz(primary.promotion().discountValue()).doubleValue(),
                    manualDiscount.doubleValue(), promoDiscount.doubleValue(), promoDiscount.doubleValue(),
                    safeBase.doubleValue(), MoneyOps.round2(safeBase.subtract(promoDiscount)).doubleValue(),
                    true, selection.chosen().stream().map(a -> a.promotion().id()).toList()
            ), List.copyOf(decisions));
        }

        for (Applicable a : selection.chosen()) {
            decisions.set(a.decisionIndex(), LineDecision.of(a.promotion(), Outcome.LOST_TO_MANUAL, a.discount()));
        }
        return new OrderEvaluation(new PromotionOrderApplication(
                null, null, DiscountType.FIXED, manualDiscount.doubleValue(),
                manualDiscount.doubleValue(), promoDiscount.doubleValue(), manualDiscount.doubleValue(),
                safeBase.doubleValue(), MoneyOps.round2(safeBase.subtract(manualDiscount)).doubleValue(),
                false, List.of()
        ), List.copyOf(decisions));
    }

    // ── selection ───────────────────────────────────────────────────────────────────────

    /**
     * BEST_ONLY and EXCLUSIVE candidates compete on discount, ties to the earlier (higher
     * priority) entry. If the winner is EXCLUSIVE it stands alone; otherwise every STACKABLE
     * candidate joins it, in priority order.
     */
    private static Selection select(List<Applicable> applicable) {
        Applicable winner = null;
        for (Applicable a : applicable) {
            if (a.promotion().stackingMode() == StackingMode.STACKABLE) {
                continue;
            }
            if (winner == null || a.discount().compareTo(winner.discount()) > 0) {
                winner = a;
            }
        }
        if (winner != null && winner.promotion().stackingMode() == StackingMode.EXCLUSIVE) {
            return new Selection(List.of(winner), true);
        }
        List<Applicable> chosen = new ArrayList<>();
        for (Applicable a : applicable) {
            if (a == winner || a.promotion().stackingMode() == StackingMode.STACKABLE) {
                chosen.add(a);
            }
        }
        return new Selection(chosen, false);
    }

    private static void fillDecisions(List<LineDecision> decisions, List<Applicable> applicable, Selection selection) {
        for (Applicable a : applicable) {
            Outcome outcome;
            if (selection.chosen().contains(a)) {
                outcome = Outcome.APPLIED;
            } else if (selection.exclusive() && a.promotion().stackingMode() == StackingMode.STACKABLE) {
                outcome = Outcome.BLOCKED_BY_EXCLUSIVE;
            } else {
                outcome = Outcome.LOST_TO_BETTER;
            }
            decisions.set(a.decisionIndex(), LineDecision.of(a.promotion(), outcome, a.discount()));
        }
    }

    // ── matching ────────────────────────────────────────────────────────────────────────

    /**
     * The target row this line matched, or null. Returns the row rather than a boolean because
     * the row is where a per-item offer price lives.
     */
    private static TargetSnapshot matchingTarget(PromotionSnapshot promotion, PricingLine line) {
        if (promotion.scope() == PromotionScope.ITEM) {
            for (TargetSnapshot target : promotion.targets()) {
                if (Objects.equals(target.itemId(), line.itemId())) {
                    return target;
                }
            }
            return null;
        }
        for (TargetSnapshot target : promotion.targets()) {
            boolean subMatch = target.subCategoryId() != null
                    && Objects.equals(target.subCategoryId(), line.subCategoryId());
            boolean catMatch = target.categoryId() != null
                    && Objects.equals(target.categoryId(), line.categoryId());
            if (subMatch || catMatch) {
                return target;
            }
        }
        return null;
    }

    private static boolean matchesCustomer(PromotionSnapshot promotion, Long customerId) {
        if (promotion.scope() != PromotionScope.CUSTOMER) {
            return true;
        }
        if (customerId == null || customerId <= 0) {
            return false;
        }
        for (TargetSnapshot target : promotion.targets()) {
            if (Objects.equals(target.customerId(), customerId)) {
                return true;
            }
        }
        return false;
    }

    // ── line effects ────────────────────────────────────────────────────────────────────

    /**
     * The uncapped discount this promotion would take off this line. Null means a TIERED
     * promotion whose lowest step was not reached; zero or less means it matched but has nothing
     * to give.
     *
     * <p>A per-item offer price or rate on the target row is more specific than the promotion's
     * effect and wins regardless of effect type.
     */
    private static BigDecimal lineEffectDiscount(PromotionSnapshot promotion, TargetSnapshot target,
                                                 PricingLine line, BigDecimal unitPrice, BigDecimal baseLineTotal) {
        if (target.offerPrice() != null) {
            BigDecimal price = MoneyOps.nonNegative(target.offerPrice()).min(unitPrice);
            return baseLineTotal.subtract(MoneyOps.lineTotal(line.itemType(), price, line.normalizedQty()));
        }
        if (target.hasOwnRate()) {
            BigDecimal price = MoneyOps.finalUnitPrice(unitPrice, target.discountType(), target.discountValue());
            return baseLineTotal.subtract(MoneyOps.lineTotal(line.itemType(), price, line.normalizedQty()));
        }
        switch (promotion.effectType()) {
            case DISCOUNT: {
                BigDecimal price = MoneyOps.finalUnitPrice(unitPrice, promotion.discountType(), promotion.discountValue());
                return baseLineTotal.subtract(MoneyOps.lineTotal(line.itemType(), price, line.normalizedQty()));
            }
            case FIXED_PRICE: {
                BigDecimal price = MoneyOps.nonNegative(promotion.discountValue()).min(unitPrice);
                return baseLineTotal.subtract(MoneyOps.lineTotal(line.itemType(), price, line.normalizedQty()));
            }
            case BUY_X_GET_Y_FREE: {
                BigDecimal buy = MoneyOps.nonNegative(promotion.buyQty());
                BigDecimal get = MoneyOps.nonNegative(promotion.getQty());
                if (buy.signum() <= 0 || get.signum() <= 0) {
                    return BigDecimal.ZERO;
                }
                BigDecimal units = MoneyOps.primaryUnits(line.itemType(), line.normalizedQty());
                BigDecimal groups = units.divide(buy.add(get), 0, RoundingMode.FLOOR);
                BigDecimal freeUnits = groups.multiply(get);
                return MoneyOps.round2(freeUnits.multiply(unitPrice));
            }
            case PROFIT_SHARE: {
                // Share of margin, not of price. The cost here is the one the caller knows -
                // for a sale that is the FIFO cost of the batches going out, so two batches of
                // one item give away different amounts and neither goes below its own cost.
                BigDecimal cost = MoneyOps.nz(line.costPrice());
                if (cost.signum() <= 0) {
                    return BigDecimal.ZERO;
                }
                BigDecimal profit = MoneyOps.nonNegative(unitPrice.subtract(cost));
                if (profit.signum() <= 0) {
                    return BigDecimal.ZERO;
                }
                BigDecimal share = MoneyOps.nonNegative(promotion.discountValue()).min(MoneyOps.HUNDRED);
                BigDecimal price = unitPrice.subtract(profit.multiply(share).divide(MoneyOps.HUNDRED, 6, RoundingMode.HALF_UP));
                return baseLineTotal.subtract(MoneyOps.lineTotal(line.itemType(), price, line.normalizedQty()));
            }
            case TIERED: {
                BigDecimal units = MoneyOps.primaryUnits(line.itemType(), line.normalizedQty());
                TierSnapshot tier = highestQtyTier(promotion, units);
                if (tier == null) {
                    return null;
                }
                BigDecimal price = MoneyOps.finalUnitPrice(unitPrice, tier.discountType(), tier.discountValue());
                return baseLineTotal.subtract(MoneyOps.lineTotal(line.itemType(), price, line.normalizedQty()));
            }
            default:
                return BigDecimal.ZERO;
        }
    }

    private static TierSnapshot highestQtyTier(PromotionSnapshot promotion, BigDecimal units) {
        TierSnapshot best = null;
        for (TierSnapshot tier : promotion.tiers()) {
            if (tier.minQty() == null || units.compareTo(tier.minQty()) < 0) {
                continue;
            }
            if (best == null || tier.minQty().compareTo(best.minQty()) > 0) {
                best = tier;
            }
        }
        return best;
    }

    private static DiscountType effectiveRateType(Applicable a) {
        return a.target().hasOwnRate() ? a.target().discountType() : a.promotion().discountType();
    }

    private static BigDecimal effectiveRateValue(Applicable a) {
        return a.target().hasOwnRate() ? a.target().discountValue() : MoneyOps.nz(a.promotion().discountValue());
    }

    // ── bill effects ────────────────────────────────────────────────────────────────────

    /**
     * The uncapped discount this promotion would take off the bill. Null for a TIERED promotion
     * whose lowest step was not reached.
     */
    private static BigDecimal orderEffectDiscount(PromotionSnapshot promotion, BigDecimal baseTotal, List<PricingLine> lines) {
        switch (promotion.effectType()) {
            case DISCOUNT:
                return rateOf(baseTotal, promotion.discountType(), promotion.discountValue());
            case TIERED: {
                TierSnapshot tier = highestAmountTier(promotion, baseTotal);
                if (tier == null) {
                    return null;
                }
                return rateOf(baseTotal, tier.discountType(), tier.discountValue());
            }
            case BUNDLE:
                return bundleDiscount(promotion, lines);
            case CHEAPEST_FREE:
                return cheapestFreeDiscount(promotion, lines);
            default:
                // FIXED_PRICE and BUY_X_GET_Y_FREE are line mechanics; on a bill they mean nothing.
                return BigDecimal.ZERO;
        }
    }

    private static BigDecimal rateOf(BigDecimal amount, DiscountType type, BigDecimal value) {
        if (type == DiscountType.PERCENT) {
            BigDecimal percent = MoneyOps.nz(value).max(BigDecimal.ZERO).min(MoneyOps.HUNDRED);
            return MoneyOps.round2(amount.multiply(percent).movePointLeft(2));
        }
        if (type == DiscountType.FIXED) {
            return MoneyOps.round2(MoneyOps.nonNegative(value));
        }
        return BigDecimal.ZERO;
    }

    private static TierSnapshot highestAmountTier(PromotionSnapshot promotion, BigDecimal amount) {
        TierSnapshot best = null;
        for (TierSnapshot tier : promotion.tiers()) {
            if (tier.minAmount() == null || amount.compareTo(tier.minAmount()) < 0) {
                continue;
            }
            if (best == null || tier.minAmount().compareTo(best.minAmount()) > 0) {
                best = tier;
            }
        }
        return best;
    }

    /**
     * "Any N for X." Eligible whole units are sorted dearest first and taken N at a time; each
     * full group is charged X instead of its own sum. Dearest first is what a customer would
     * choose, so it is what the till does.
     */
    private static BigDecimal bundleDiscount(PromotionSnapshot promotion, List<PricingLine> lines) {
        int groupSize = groupSize(promotion);
        if (groupSize < 2) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> units = eligibleUnitPrices(promotion, lines);
        BigDecimal bundlePrice = MoneyOps.nonNegative(promotion.discountValue());
        BigDecimal discount = BigDecimal.ZERO;
        for (int start = 0; start + groupSize <= units.size(); start += groupSize) {
            BigDecimal groupSum = BigDecimal.ZERO;
            for (int i = start; i < start + groupSize; i++) {
                groupSum = groupSum.add(units.get(i));
            }
            BigDecimal saving = groupSum.subtract(bundlePrice);
            if (saving.signum() > 0) {
                discount = discount.add(saving);
            }
        }
        return MoneyOps.round2(discount);
    }

    /** "Buy N, cheapest free." Dearest-first groups of N; the last in each full group is free. */
    private static BigDecimal cheapestFreeDiscount(PromotionSnapshot promotion, List<PricingLine> lines) {
        int groupSize = groupSize(promotion);
        if (groupSize < 2) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> units = eligibleUnitPrices(promotion, lines);
        BigDecimal discount = BigDecimal.ZERO;
        for (int start = 0; start + groupSize <= units.size(); start += groupSize) {
            discount = discount.add(units.get(start + groupSize - 1));
        }
        return MoneyOps.round2(discount);
    }

    private static int groupSize(PromotionSnapshot promotion) {
        BigDecimal buy = MoneyOps.nonNegative(promotion.buyQty());
        return buy.setScale(0, RoundingMode.FLOOR).intValueExact();
    }

    /**
     * Every eligible whole unit in the cart as its own price, dearest first. Weight and volume
     * lines contribute their whole primary units only — half a kilo is not a bundle item.
     * Capped so a pathological cart cannot expand into millions of entries.
     */
    private static List<BigDecimal> eligibleUnitPrices(PromotionSnapshot promotion, List<PricingLine> lines) {
        final int hardCap = 10_000;
        List<BigDecimal> units = new ArrayList<>();
        for (PricingLine line : lines) {
            boolean eligible = !promotion.matchesTargetsByItem() || matchingTarget(promotion, line) != null;
            if (!eligible) {
                continue;
            }
            int whole = MoneyOps.primaryUnits(line.itemType(), line.normalizedQty())
                    .setScale(0, RoundingMode.FLOOR).intValue();
            BigDecimal price = MoneyOps.nz(line.unitPrice());
            for (int i = 0; i < whole && units.size() < hardCap; i++) {
                units.add(price);
            }
        }
        units.sort(Comparator.reverseOrder());
        return units;
    }

    // ── guards ──────────────────────────────────────────────────────────────────────────

    /**
     * Null when the promotion may price this line; otherwise the reason it may not. An item
     * with no cost is left alone — there is nothing to measure against, and refusing on missing
     * data would disable promotions on half-set-up catalogues.
     */
    private static Outcome marginVerdict(PromotionSnapshot promotion, BigDecimal costPrice, BigDecimal discountedUnitPrice) {
        if (costPrice == null) {
            return null;
        }
        if (!promotion.allowBelowCost() && discountedUnitPrice.compareTo(costPrice) < 0) {
            return Outcome.BELOW_COST;
        }
        BigDecimal floor = promotion.marginFloorPercent();
        if (floor != null) {
            BigDecimal margin = MoneyOps.marginPercent(discountedUnitPrice, costPrice);
            if (margin == null || margin.compareTo(floor) < 0) {
                return Outcome.BELOW_MARGIN_FLOOR;
            }
        }
        return null;
    }

    /**
     * Clamps a discount to the promotion's cap. Zero means no cap — the column is NOT NULL with a
     * default of 0, so it cannot say "uncapped" any other way, and uncapped is the only reading
     * under which promotions written before the cap existed keep working.
     */
    private static BigDecimal capDiscount(PromotionSnapshot promotion, BigDecimal discount) {
        BigDecimal cap = MoneyOps.nonNegative(promotion.maxDiscountAmount());
        return cap.signum() <= 0 ? MoneyOps.round2(discount) : MoneyOps.round2(discount.min(cap));
    }
}
