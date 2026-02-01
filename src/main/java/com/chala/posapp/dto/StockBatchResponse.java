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
        private BigDecimal price;  // Selling Price of this batch
        private Integer qty;        // Available Quantity in this batch
        private LocalDateTime expiry;  // Expiry Date (Optional)
}
