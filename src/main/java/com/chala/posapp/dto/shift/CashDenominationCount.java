package com.chala.posapp.dto.shift;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashDenominationCount {
    private int denomination;
    private int quantity;
}
