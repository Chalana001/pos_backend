package com.chala.posapp.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockBatchResponse {
        private Long batchId;
        private BigDecimal price;
        private Integer qty;
        private LocalDateTime expiry;
}
