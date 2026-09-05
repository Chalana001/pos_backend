package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.promotion.engine.LineDecision.Outcome;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decides what a promotion does to a cart. Pure: no Spring, no repositories, no clock — every
 * input arrives as an argument, so the same call gives the same answer anywhere it runs.
 *
 * <p>That is the point of the extraction. The maths used to live inside a {@code @Service}
 * alongside the database access, which made it impossible to run against historical carts
 * (the simulator), impossible to run on an offline till, and awkward to test without mocking
 * seven repositories. Here it takes a {@link PricingLine} and a list of
 * {@link PromotionSnapshot}s and returns a priced result with a reason for every promotion it
 * considered.
 *
 * <p>Behaviour is deliberately identical to what {@code PromotionService} did inline: best
 * single promotion wins per line, manual discount stacks on top at line level and competes at
 * bill level, caps and floors as before. Only the arithmetic changed — {@code double} became
 * {@link BigDecimal} throughout.
 */
public final class PromotionEvaluator {

    private PromotionEvaluator() {
    }

    /**
     * Prices one line: picks the best item/category promotion, then stacks the manual discount.
     *
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
        PromotionSnapshot best = null;
        TargetSnapshot bestTarget = null;
        BigDecimal bestDiscount = BigDecimal.ZERO;
        BigDecimal bestUncapped = BigDecimal.ZERO;
        int bestIndex = -1;

        List<PromotionSnapshot> candidates = promotions == null ? List.of() : promotions;
        for (PromotionSnapshot promotion : candidates) {
            if (!promotion.isLineScope()) {
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
            BigDecimal promoUnitPrice = resolveOfferUnitPrice(promotion, target, unitPrice);
            Outcome marginVerdict = marginVerdict(promotion, line.costPrice(), promoUnitPrice);
            if (marginVerdict != null) {
                decisions.add(LineDecision.of(promotion, marginVerdict));
                continue;
            }
            BigDecimal promoLineTotal = MoneyOps.lineTotal(line.itemType(), promoUnitPrice, line.normalizedQty());
            BigDecimal uncapped = MoneyOps.round2(baseLineTotal.subtract(promoLineTotal));
            BigDecimal discount = capLineDiscount(promotion, uncapped);
            if (discount.signum() <= 0) {
                decisions.add(LineDecision.of(promotion, Outcome.NO_DISCOUNT));
                continue;
            }
            // Strictly greater: the list arrives ordered by priority, so ties go to the earlier
            // entry. That is the only thing priority decides.
            if (discount.compareTo(bestDiscount) > 0) {
                if (best != null) {
                    decisions.set(bestIndex, LineDecision.of(best, Outcome.LOST_TO_BETTER, bestDiscount));
                }
                best = promotion;
                bestTarget = target;
                bestDiscount = discount;
                bestUncapped = uncapped;
                bestIndex = decisions.size();
                decisions.add(LineDecision.of(promotion, Outcome.APPLIED, discount));
            } else {
                decisions.add(LineDecision.of(promotion, Outcome.LOST_TO_BETTER, discount));
            }
        }

        if (best == null) {
            BigDecimal manualUnitPrice = MoneyOps.finalUnitPrice(unitPrice, manualType, manualValue);
            BigDecimal manualLineTotal = MoneyOps.lineTotal(line.itemType(), manualUnitPrice, line.normalizedQty());
            BigDecimal manualDiscount = MoneyOps.round2(baseLineTotal.subtract(manualLineTotal));
            return new LineEvaluation(new PromotionApplication(
                    null,
                    null,
                    manualType,
                    manualType == DiscountType.NONE ? 0.0 : MoneyOps.nonNegative(manualValue).doubleValue(),
                    manualDiscount.doubleValue(),
                    0.0,
                    manualDiscount.doubleValue(),
                    baseLineTotal.doubleValue(),
                    manualLineTotal.doubleValue(),
                    false
            ), List.copyOf(decisions));
        }

        BigDecimal promoUnitPrice = MoneyOps.unitPriceForLineDiscount(unitPrice, baseLineTotal, bestDiscount);
        BigDecimal stackedUnitPrice = MoneyOps.finalUnitPrice(promoUnitPrice, manualType, manualValue);
        BigDecimal promoLineTotal = MoneyOps.lineTotal(line.itemType(), promoUnitPrice, line.normalizedQty());
        BigDecimal finalLineTotal = MoneyOps.lineTotal(line.itemType(), stackedUnitPrice, line.normalizedQty());
        BigDecimal manualDiscount = MoneyOps.round2(promoLineTotal.subtract(finalLineTotal));
        BigDecimal appliedDiscount = MoneyOps.round2(baseLineTotal.subtract(finalLineTotal));

        // The caller rebuilds the final unit price from the returned type/value pair, so the pair
        // has to reproduce the price actually charged. A rate survives only when nothing altered
        // it: a manual discount on top, the cap biting, or a per-item offer price (a price, not a
        // rate) all mean the line no longer sells at that rate, and the pair collapses to the flat
        // per-unit reduction. Returning PERCENT there would re-expand to the uncapped discount.
        boolean capApplied = bestDiscount.compareTo(bestUncapped) < 0;
        boolean rateIntact = manualType == DiscountType.NONE && !capApplied && bestTarget.offerPrice() == null;
        DiscountType effectiveType = rateIntact ? effectiveRateType(best, bestTarget) : DiscountType.FIXED;
        BigDecimal effectiveValue = rateIntact
                ? effectiveRateValue(best, bestTarget)
                : MoneyOps.round2(MoneyOps.nonNegative(unitPrice.subtract(stackedUnitPrice)));

        return new LineEvaluation(new PromotionApplication(
                best.id(),
                best.name(),
                effectiveType,
                effectiveValue.doubleValue(),
                manualDiscount.doubleValue(),
                bestDiscount.doubleValue(),
                appliedDiscount.doubleValue(),
                baseLineTotal.doubleValue(),
                finalLineTotal.doubleValue(),
                true
        ), List.copyOf(decisions));
    }

    /**
     * Prices the bill: the best bill/customer promotion competes with the manual bill discount
     * and the larger one is taken. Deliberately not stacked — the line path stacks, and the two
     * halves having different semantics is a decision on record, not an accident.
     */
    public static OrderEvaluation evaluateOrder(Long branchId, Long customerId, BigDecimal baseTotal,
                                                BigDecimal manualBillDiscount, List<PromotionSnapshot> promotions) {
        BigDecimal safeBase = MoneyOps.nonNegative(baseTotal);
        BigDecimal manualDiscount = MoneyOps.round2(MoneyOps.nonNegative(manualBillDiscount).min(safeBase));

        List<LineDecision> decisions = new ArrayList<>();
        PromotionSnapshot best = null;
        BigDecimal bestDiscount = BigDecimal.ZERO;
        int bestIndex = -1;

        List<PromotionSnapshot> candidates = promotions == null ? List.of() : promotions;
        for (PromotionSnapshot promotion : candidates) {
            if (!promotion.isOrderScope()) {
                decisions.add(LineDecision.of(promotion, Outcome.WRONG_SCOPE));
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
            BigDecimal discount = orderDiscount(promotion, safeBase);
            if (discount.signum() <= 0) {
                decisions.add(LineDecision.of(promotion, Outcome.NO_DISCOUNT));
                continue;
            }
            if (discount.compareTo(bestDiscount) > 0) {
                if (best != null) {
                    decisions.set(bestIndex, LineDecision.of(best, Outcome.LOST_TO_BETTER, bestDiscount));
                }
                best = promotion;
                bestDiscount = discount;
                bestIndex = decisions.size();
                decisions.add(LineDecision.of(promotion, Outcome.APPLIED, discount));
            } else {
                decisions.add(LineDecision.of(promotion, Outcome.LOST_TO_BETTER, discount));
            }
        }

        if (best != null && bestDiscount.compareTo(manualDiscount) > 0) {
            return new OrderEvaluation(new PromotionOrderApplication(
                    best.id(),
                    best.name(),
                    best.discountType(),
                    MoneyOps.nz(best.discountValue()).doubleValue(),
                    manualDiscount.doubleValue(),
                    bestDiscount.doubleValue(),
                    bestDiscount.doubleValue(),
                    safeBase.doubleValue(),
                    MoneyOps.round2(safeBase.subtract(bestDiscount)).doubleValue(),
                    true
            ), List.copyOf(decisions));
        }

        if (best != null) {
            decisions.set(bestIndex, LineDecision.of(best, Outcome.LOST_TO_MANUAL, bestDiscount));
        }
        return new OrderEvaluation(new PromotionOrderApplication(
                null,
                null,
                DiscountType.FIXED,
                manualDiscount.doubleValue(),
                manualDiscount.doubleValue(),
                bestDiscount.doubleValue(),
                manualDiscount.doubleValue(),
                safeBase.doubleValue(),
                MoneyOps.round2(safeBase.subtract(manualDiscount)).doubleValue(),
                false
        ), List.copyOf(decisions));
    }

    // ── matching ────────────────────────────────────────────────────────────────────────

    /**
     * The target row this line matched, or null. Returns the row rather than a boolean because
     * the row is where a per-item offer price lives.
     */
    private static TargetSnapshot matchingTarget(PromotionSnapshot promotion, PricingLine line) {
        if (promotion.scope() == com.chala.posapp.entity.PromotionScope.ITEM) {
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
        if (promotion.scope() == com.chala.posapp.entity.PromotionScope.BILL) {
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

    // ── pricing ─────────────────────────────────────────────────────────────────────────

    /**
     * Offer price, then per-item rate, then the promotion's own rate — most specific first.
     * An offer price above list is clamped: charging above the shelf label is never the intent.
     */
    private static BigDecimal resolveOfferUnitPrice(PromotionSnapshot promotion, TargetSnapshot target, BigDecimal unitPrice) {
        if (target.offerPrice() != null) {
            return MoneyOps.nonNegative(target.offerPrice()).min(unitPrice);
        }
        if (target.hasOwnRate()) {
            return MoneyOps.finalUnitPrice(unitPrice, target.discountType(), target.discountValue());
        }
        return MoneyOps.finalUnitPrice(unitPrice, promotion.discountType(), promotion.discountValue());
    }

    private static DiscountType effectiveRateType(PromotionSnapshot promotion, TargetSnapshot target) {
        return target.hasOwnRate() ? target.discountType() : promotion.discountType();
    }

    private static BigDecimal effectiveRateValue(PromotionSnapshot promotion, TargetSnapshot target) {
        return target.hasOwnRate() ? target.discountValue() : MoneyOps.nz(promotion.discountValue());
    }

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
     * Clamps one line's discount to the promotion's cap. Zero means no cap — the column is NOT
     * NULL with a default of 0, so it cannot say "uncapped" any other way, and uncapped is the
     * only reading under which promotions written before the cap existed keep working.
     */
    private static BigDecimal capLineDiscount(PromotionSnapshot promotion, BigDecimal discount) {
        BigDecimal cap = MoneyOps.nonNegative(promotion.maxDiscountAmount());
        return cap.signum() <= 0 ? discount : MoneyOps.round2(discount.min(cap));
    }

    private static BigDecimal orderDiscount(PromotionSnapshot promotion, BigDecimal baseTotal) {
        BigDecimal discount;
        if (promotion.discountType() == DiscountType.PERCENT) {
            BigDecimal percent = MoneyOps.nz(promotion.discountValue()).max(BigDecimal.ZERO).min(MoneyOps.HUNDRED);
            discount = baseTotal.multiply(percent).movePointLeft(2);
        } else if (promotion.discountType() == DiscountType.FIXED) {
            discount = MoneyOps.nonNegative(promotion.discountValue());
        } else {
            discount = BigDecimal.ZERO;
        }
        BigDecimal cap = MoneyOps.nonNegative(promotion.maxDiscountAmount());
        if (cap.signum() > 0) {
            discount = discount.min(cap);
        }
        return MoneyOps.round2(discount.min(baseTotal));
    }
}
