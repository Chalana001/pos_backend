package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateStockProcessingRequest {

    @NotNull
    private Long branchId;

    @NotNull
    private Long sourceItemId;

    @NotNull
    private Long sourceBatchId;

    @NotNull
    @Positive
    private BigDecimal sourceQty;

    private MeasurementUnit sourceQtyUnit;

    @Size(max = 500)
    private String note;

    @Valid
    @NotEmpty
    private List<CreateStockProcessingOutputRequest> outputs;
}
