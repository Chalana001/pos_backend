package com.chala.posapp.util;

import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class QuantityConversionUtil {

    private static final BigDecimal GRAMS_PER_KILOGRAM = BigDecimal.valueOf(1000);
    private static final BigDecimal MILLILITERS_PER_LITER = BigDecimal.valueOf(1000);

    private QuantityConversionUtil() {
    }

    public static MeasurementUnit normalizeItemUnit(ItemType itemType, MeasurementUnit unit) {
        if (itemType == ItemType.NORMAL || itemType == ItemType.RECIPE) {
            return MeasurementUnit.PCS;
        }

        if (itemType == ItemType.SERVICE) {
            return MeasurementUnit.SERVICE;
        }

        if (itemType == ItemType.WEIGHT) {
            MeasurementUnit resolved = unit == null ? MeasurementUnit.KG : unit;
            if (resolved != MeasurementUnit.G && resolved != MeasurementUnit.KG) {
                throw new BadRequestException("Weight items must use G or KG as the default unit");
            }
            return resolved;
        }

        if (itemType == ItemType.VOLUME) {
            MeasurementUnit resolved = unit == null ? MeasurementUnit.L : unit;
            if (resolved != MeasurementUnit.ML && resolved != MeasurementUnit.L) {
                throw new BadRequestException("Volume items must use ML or L as the default unit");
            }
            return resolved;
        }

        MeasurementUnit resolved = unit == null ? MeasurementUnit.KG : unit;
        if (resolved == MeasurementUnit.PCS || resolved == MeasurementUnit.SERVICE || resolved == MeasurementUnit.ML || resolved == MeasurementUnit.L) {
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

    public static int normalizeSaleQuantity(Item item, BigDecimal quantity, MeasurementUnit requestedUnit) {
        if ((item.getItemType() == ItemType.NORMAL || item.getItemType() == ItemType.RECIPE || item.getItemType() == ItemType.SERVICE)
                && quantity != null
                && quantity.stripTrailingZeros().scale() > 0) {
            throw new BadRequestException(item.getItemType() == ItemType.RECIPE
                    ? "Recipe item quantity must be a whole number"
                    : item.getItemType() == ItemType.SERVICE
                    ? "Service item quantity must be a whole number"
                    : "Normal item quantity must be a whole number");
        }
        return normalizeQuantity(item, quantity, requestedUnit);
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

            BigDecimal normalized = quantity.multiply(baseUnitsPerPrimaryUnit(itemType)).stripTrailingZeros();
            if (normalized.scale() > 0) {
                throw new BadRequestException("Normal item quantity supports up to 3 decimal places");
            }

            return normalized.intValueExact();
        }

        if (itemType == ItemType.RECIPE) {
            MeasurementUnit resolvedUnit = requestedUnit == null ? MeasurementUnit.PCS : requestedUnit;
            if (resolvedUnit != MeasurementUnit.PCS) {
                throw new BadRequestException("Recipe items only support PCS quantity");
            }

            BigDecimal normalized = quantity.stripTrailingZeros();
            if (normalized.scale() > 0) {
                throw new BadRequestException("Recipe item quantity must be a whole number");
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

        if (itemType == ItemType.WEIGHT) {
            MeasurementUnit resolvedUnit = requestedUnit == null ? normalizeItemUnit(ItemType.WEIGHT, defaultUnit) : requestedUnit;
            if (resolvedUnit != MeasurementUnit.G && resolvedUnit != MeasurementUnit.KG) {
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

        if (itemType == ItemType.VOLUME) {
            MeasurementUnit resolvedUnit = requestedUnit == null ? normalizeItemUnit(ItemType.VOLUME, defaultUnit) : requestedUnit;
            if (resolvedUnit != MeasurementUnit.ML && resolvedUnit != MeasurementUnit.L) {
                throw new BadRequestException("Volume item quantity unit must be ML or L");
            }

            BigDecimal milliliters = resolvedUnit == MeasurementUnit.L
                    ? quantity.multiply(MILLILITERS_PER_LITER)
                    : quantity;

            BigDecimal normalized = milliliters.stripTrailingZeros();
            if (normalized.scale() > 0) {
                throw new BadRequestException("Volume quantity must resolve to whole milliliters");
            }

            return normalized.intValueExact();
        }

        throw new BadRequestException("Unsupported item type for quantity conversion");
    }

    public static BigDecimal toDisplayQuantity(Item item, Integer normalizedQty) {
        return toDisplayQuantity(item.getItemType(), item.getDefaultUnit(), normalizedQty);
    }

    public static BigDecimal toDisplayQuantity(ItemType itemType, MeasurementUnit defaultUnit, Integer normalizedQty) {
        if (normalizedQty == null) {
            return BigDecimal.ZERO;
        }

        if (itemType != ItemType.NORMAL && itemType != ItemType.WEIGHT && itemType != ItemType.VOLUME) {
            return BigDecimal.valueOf(normalizedQty.longValue());
        }

        if (itemType == ItemType.NORMAL) {
            return BigDecimal.valueOf(normalizedQty.longValue())
                    .divide(baseUnitsPerPrimaryUnit(itemType), 3, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }

        if (itemType == ItemType.WEIGHT && normalizeItemUnit(ItemType.WEIGHT, defaultUnit) == MeasurementUnit.KG) {
            return BigDecimal.valueOf(normalizedQty.longValue())
                    .divide(GRAMS_PER_KILOGRAM, 3, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }

        if (itemType == ItemType.VOLUME && normalizeItemUnit(ItemType.VOLUME, defaultUnit) == MeasurementUnit.L) {
            return BigDecimal.valueOf(normalizedQty.longValue())
                    .divide(MILLILITERS_PER_LITER, 3, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }

        return BigDecimal.valueOf(normalizedQty.longValue());
    }

    public static BigDecimal toPerBaseUnitPrice(Item item, BigDecimal configuredPrice) {
        if (configuredPrice == null) {
            return BigDecimal.ZERO;
        }

        if (item.getItemType() != ItemType.NORMAL && item.getItemType() != ItemType.WEIGHT && item.getItemType() != ItemType.VOLUME) {
            return configuredPrice;
        }

        return configuredPrice.divide(baseUnitsPerPrimaryUnit(item.getItemType()), 6, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateActualAmount(Item item, BigDecimal configuredPrice, int normalizedQty) {
        if (configuredPrice == null) {
            return BigDecimal.ZERO;
        }

        if (item.getItemType() != ItemType.NORMAL && item.getItemType() != ItemType.WEIGHT && item.getItemType() != ItemType.VOLUME) {
            return configuredPrice.multiply(BigDecimal.valueOf(normalizedQty));
        }

        return configuredPrice
                .multiply(BigDecimal.valueOf(normalizedQty))
                .divide(baseUnitsPerPrimaryUnit(item.getItemType()), 2, RoundingMode.HALF_UP);
    }

    public static boolean isMeasuredItem(ItemType itemType) {
        return itemType == ItemType.WEIGHT || itemType == ItemType.VOLUME;
    }

    public static MeasurementUnit primaryDisplayUnit(Item item) {
        if (item.getItemType() == ItemType.WEIGHT) {
            return MeasurementUnit.KG;
        }
        if (item.getItemType() == ItemType.VOLUME) {
            return MeasurementUnit.L;
        }
        return item.getDefaultUnit();
    }

    private static BigDecimal baseUnitsPerPrimaryUnit(ItemType itemType) {
        return itemType == ItemType.NORMAL || itemType == ItemType.VOLUME
                ? MILLILITERS_PER_LITER
                : GRAMS_PER_KILOGRAM;
    }
}
