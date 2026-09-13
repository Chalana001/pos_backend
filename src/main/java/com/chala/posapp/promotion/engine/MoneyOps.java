package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.util.QuantityConversionUtil;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The arithmetic the pricing engine is allowed to do, in one place.
 *
 * <p>Everything here is {@link BigDecimal}. The engine used to compute in {@code double} and
 * round with BigDecimal afterwards, which patches the display but not the sum, the error has
 * already happened by then. Money enters as BigDecimal, stays BigDecimal, and is rounded to
 * two places exactly once, at the boundary where it becomes a line total or a discount.
 */
public final class MoneyOps {

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Enough precision for any intermediate unit price; the final rounding is {@link #round2}. */
    private static final MathContext INTERMEDIATE = MathContext.DECIMAL64;

    private MoneyOps() {
    }

    public static BigDecimal round2(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal nonNegative(BigDecimal value) {
        BigDecimal safe = nz(value);
        return safe.signum() < 0 ? BigDecimal.ZERO : safe;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * A unit price after one discount. PERCENT is clamped to 0-100, FIXED to the price itself,
     * so a discount can never take a price below zero.
     */
    public static BigDecimal finalUnitPrice(BigDecimal unitPrice, DiscountType type, BigDecimal value) {
        BigDecimal price = nz(unitPrice);
        if (type == null || type == DiscountType.NONE || value == null) {
            return price;
        }
        if (type == DiscountType.PERCENT) {
            BigDecimal percent = value.max(BigDecimal.ZERO).min(HUNDRED);
            return price.subtract(price.multiply(percent).movePointLeft(2));
        }
        BigDecimal amount = value.max(BigDecimal.ZERO).min(price);
        return price.subtract(amount);
    }

    /**
     * What a line comes to at a given unit price, rounded to money. Quantities are normalized
     * base units, so this defers to the same conversion the rest of the system uses.
     */
    public static BigDecimal lineTotal(ItemType itemType, BigDecimal unitPrice, int normalizedQty) {
        return round2(QuantityConversionUtil.calculateActualAmount(itemType, nz(unitPrice), normalizedQty));
    }

    /**
     * The unit price that produces a given line discount.
     *
     * <p>Downstream works in unit-price space, but a capped discount is only expressible as an
     * amount. Scaling works because {@link #lineTotal} is linear in the unit price, so it holds
     * for weight and measure items as well as whole units.
     */
    public static BigDecimal unitPriceForLineDiscount(BigDecimal unitPrice, BigDecimal baseLineTotal, BigDecimal lineDiscount) {
        if (!isPositive(baseLineTotal)) {
            return nz(unitPrice);
        }
        BigDecimal ratio = baseLineTotal.subtract(nz(lineDiscount)).divide(baseLineTotal, INTERMEDIATE);
        if (ratio.signum() < 0) {
            ratio = BigDecimal.ZERO;
        }
        return nz(unitPrice).multiply(ratio, INTERMEDIATE);
    }

    /** A line's quantity in primary units, what buy-X-get-Y, bundles and tiers count in. */
    public static BigDecimal primaryUnits(ItemType itemType, int normalizedQty) {
        return QuantityConversionUtil.toPrimaryUnits(itemType, normalizedQty);
    }

    /** Gross margin of a price over a cost, as a percentage of the price. Null when the price is zero. */
    public static BigDecimal marginPercent(BigDecimal price, BigDecimal cost) {
        if (!isPositive(price)) {
            return null;
        }
        return price.subtract(nz(cost)).divide(price, INTERMEDIATE).multiply(HUNDRED);
    }
}
