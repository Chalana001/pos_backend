package com.chala.posapp.dto.grn;
import com.chala.posapp.entity.MeasurementUnit;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class GrnItemRequest {
    private Long itemId;
    private BigDecimal qty;
    private MeasurementUnit qtyUnit;

    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private LocalDate expiryDate;
    private Boolean zeroNegativeStock;
}
