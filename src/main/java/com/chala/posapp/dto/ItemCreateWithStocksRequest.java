package com.chala.posapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemCreateWithStocksRequest {

    @Valid
    @NotNull
    private ItemCreateRequest itemCreateRequest;

    @Valid
    @NotEmpty
    private List<StockLineRequest> stocks;
}
