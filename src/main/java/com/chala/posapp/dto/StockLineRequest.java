package com.chala.posapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockLineRequest {

    @NotNull
    private Long branchId;

    @Min(0)
    private Integer quantity;
}
