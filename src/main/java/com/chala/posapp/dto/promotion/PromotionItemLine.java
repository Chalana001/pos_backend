package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One item in an ITEM-scope promotion, optionally carrying a price of its own.
 *
 * <p>This is what lets a single campaign hold a price list, twenty items at twenty different
 * prices under one name, one schedule and one switch, instead of twenty promotions kept in
 * sync by hand.
 *
 * <p>All three override fields are optional. Leaving them empty means the item inherits the
 * promotion's own discount, which is both the old behaviour and the way to express "20% off
 * these fifteen items, except these three at fixed prices" as one promotion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionItemLine {

    @NotNull
    private Long id;

    /** Sells at exactly this price. Takes precedence over {@link #discountType}. */
    @PositiveOrZero
    private BigDecimal offerPrice;

    /** A rate for this item alone, used when {@link #offerPrice} is absent. */
    private DiscountType discountType;

    @PositiveOrZero
    private BigDecimal discountValue;

    /**
     * Display-only, filled when a promotion is read so the list can be searched by item.
     * Clients sending this DTO back are ignored: {@code applyTargets} never reads these.
     */
    private String name;

    /** Display-only twin of {@link #name}; a barcode search finds promotions by their items. */
    private String barcode;

    /**
     * Display-only. The shelf price the offer price is judged against. Filled by reads that
     * resolve items ({@code list} and {@code getById}); write responses pass no items and
     * leave it null. Null on soft-deleted items, whose rows the repository no longer returns.
     */
    private BigDecimal sellingPrice;
}
