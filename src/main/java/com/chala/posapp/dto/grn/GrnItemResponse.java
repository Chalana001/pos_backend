package com.chala.posapp.dto.grn;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class GrnItemResponse {
    private Long id;        // GrnItem row ID. Used by purchase return to identify the exact line
    private Long itemId;    // Product/Item ID
    private String barcode;
    private String itemName;
    private String altName;
    private BigDecimal qty;
    private BigDecimal freeQty;
    private MeasurementUnit qtyUnit;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal lineTotal;

    /**
     * The unit cost as the operator typed it, before the bill discount was spread over the
     * lines. costPrice above is the EFFECTIVE cost, net of that share, because that is what
     * stock and margin have to be valued at.
     *
     * Only "Cancel &amp; Rebuild" needs this. Pre-filling a correction from costPrice and
     * then sending the same discountAmount again applies the discount twice, which silently
     * moved a real 104,446.95 bill to 100,095.12 when its supplier was corrected.
     * Reconstructed rather than stored, so it works for bills recorded before this existed.
     */
    private BigDecimal grossCostPrice;

    /**
     * Expiry as recorded on this line's stock batch, when it can be identified unambiguously.
     *
     * Expiry is not stored on the GRN line — it lives on the StockBatch the line created —
     * so it has to be read back from there. Only populated for a detail fetch, and only
     * where the line maps to exactly one batch: "Cancel &amp; Rebuild" pre-fills the new
     * bill from this, and a rebuild that silently dropped expiry dates would put stock on
     * the shelf with none.
     */
    private LocalDate expiryDate;
}
