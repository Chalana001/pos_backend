package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
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
        private BigDecimal displayQty;
        private MeasurementUnit displayUnit;
        private LocalDateTime expiry;
}
