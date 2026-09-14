package com.chala.posapp.dto.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.ItemOverheadCostMode;
import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ItemUpdateRequest {

    /**
     * Blank or absent leaves the item's current barcode alone. Unlike creation this never
     * generates one: an item already has a barcode, and handing it a fresh random number
     * on an unrelated edit would orphan every printed label and scale PLU pointing at it.
     */
    @Size(max = 80)
    private String barcode;

    @Size(min = 2, max = 160)
    private String name;

    @Size(max = 160)
    private String altName;

    private Long subCategoryId;

    @PositiveOrZero
    private BigDecimal costPrice;

    @PositiveOrZero
    private BigDecimal sellingPrice;

    @PositiveOrZero
    private BigDecimal reorderLevel;

    private ItemType itemType;

    private MeasurementUnit defaultUnit;
    private List<Long> branchIds;

    @Size(max = 500)
    private String imageUrl;

    @JsonProperty("isKotEnabled")
    private Boolean isKotEnabled;

    @Valid
    private List<ItemIngredientRequest> ingredients;

    private Boolean active;

    private Boolean posVisible;

    private Boolean stockProcessingEnabled;

    private ItemOverheadCostMode overheadCostMode;

    @PositiveOrZero
    private BigDecimal overheadCostValue;

    @Valid
    private List<StockProcessingOutputLinkRequest> processingOutputs;
}
