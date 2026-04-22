package com.chala.posapp.util;

import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class QuantityConversionUtil {

    private static final BigDecimal GRAMS_PER_KILOGRAM = BigDecimal.valueOf(1000);

    private QuantityConversionUtil() {
    }

    public static MeasurementUnit normalizeItemUnit(ItemType itemType, MeasurementUnit unit) {
        if (itemType == ItemType.NORMAL) {
            return MeasurementUnit.PCS;
        }

        if (itemType == ItemType.SERVICE) {
            return MeasurementUnit.SERVICE;
        }

        MeasurementUnit resolved = unit == null ? MeasurementUnit.KG : unit;
        if (resolved == MeasurementUnit.PCS || resolved == MeasurementUnit.SERVICE) {
            throw new BadRequestException("Weight items must use G or KG as the default unit");
        }
        return resolved;
    }

    public static int normalizeReorderLevel(ItemType itemType, MeasurementUnit unit, BigDecimal reorderLevel) {
        if (reorderLevel == null || reorderLevel.signum() <= 0) {
            return 0;
        }
        return normalizeQuantity(itemType, unit, reorderLevel, unit);
    }

    public static int normalizeQuantity(Item item, BigDecimal quantity, MeasurementUnit requestedUnit) {
        return normalizeQuantity(item.getItemType(), item.getDefaultUnit(), quantity, requestedUnit);
    }

    public static int normalizeQuantity(ItemType itemType, MeasurementUnit defaultUnit, BigDecimal quantity, MeasurementUnit requestedUnit) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new BadRequestException("Quantity must be greater than zero");
        }

        if (itemType == ItemType.NORMAL) {
            MeasurementUnit resolvedUnit = requestedUnit == null ? MeasurementUnit.PCS : requestedUnit;
            if (resolvedUnit != MeasurementUnit.PCS) {
                throw new BadRequestException("Normal items only support PCS quantity");
            }

            BigDecimal normalized = quantity.stripTrailingZeros();
            if (normalized.scale() > 0) {
                throw new BadRequestException("Normal item quantity must be a whole number");
            }

            return normalized.intValueExact();
        }

        if (itemType == ItemType.SERVICE) {
            MeasurementUnit resolvedUnit = requestedUnit == null ? MeasurementUnit.SERVICE : requestedUnit;
            if (resolvedUnit != MeasurementUnit.SERVICE && resolvedUnit != MeasurementUnit.PCS) {
                throw new BadRequestException("Service items only support SERVICE quantity");
            }

            BigDecimal normalized = quantity.stripTrailingZeros();
            if (normalized.scale() > 0) {
                throw new BadRequestException("Service item quantity must be a whole number");
            }

            return normalized.intValueExact();
        }

        MeasurementUnit resolvedUnit = requestedUnit == null ? normalizeItemUnit(ItemType.WEIGHT, defaultUnit) : requestedUnit;
        if (resolvedUnit == MeasurementUnit.PCS || resolvedUnit == MeasurementUnit.SERVICE) {
            throw new BadRequestException("Weight item quantity unit must be G or KG");
        }

        BigDecimal grams = resolvedUnit == MeasurementUnit.KG
                ? quantity.multiply(GRAMS_PER_KILOGRAM)
                : quantity;

        BigDecimal normalized = grams.stripTrailingZeros();
        if (normalized.scale() > 0) {
            throw new BadRequestException("Weight quantity must resolve to whole grams");
        }

        return normalized.intValueExact();
    }

    public static BigDecimal toDisplayQuantity(Item item, Integer normalizedQty) {
        return toDisplayQuantity(item.getItemType(), item.getDefaultUnit(), normalizedQty);
    }

    public static BigDecimal toDisplayQuantity(ItemType itemType, MeasurementUnit defaultUnit, Integer normalizedQty) {
        if (normalizedQty == null) {
            return BigDecimal.ZERO;
        }

        if (itemType != ItemType.WEIGHT) {
            return BigDecimal.valueOf(normalizedQty.longValue());
        }

        if (normalizeItemUnit(ItemType.WEIGHT, defaultUnit) == MeasurementUnit.KG) {
            return BigDecimal.valueOf(normalizedQty.longValue())
                    .divide(GRAMS_PER_KILOGRAM, 3, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }

        return BigDecimal.valueOf(normalizedQty.longValue());
    }

    public static BigDecimal toPerBaseUnitPrice(Item item, BigDecimal configuredPrice) {
        if (configuredPrice == null) {
            return BigDecimal.ZERO;
        }

        if (item.getItemType() != ItemType.WEIGHT) {
            return configuredPrice;
        }

        return configuredPrice.divide(GRAMS_PER_KILOGRAM, 6, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateActualAmount(Item item, BigDecimal configuredPrice, int normalizedQty) {
        if (configuredPrice == null) {
            return BigDecimal.ZERO;
        }

        if (item.getItemType() != ItemType.WEIGHT) {
            return configuredPrice.multiply(BigDecimal.valueOf(normalizedQty));
        }

        return configuredPrice
                .multiply(BigDecimal.valueOf(normalizedQty))
                .divide(GRAMS_PER_KILOGRAM, 2, RoundingMode.HALF_UP);
    }
}
