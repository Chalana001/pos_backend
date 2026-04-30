package com.chala.posapp.dto.report;

import com.chala.posapp.entity.ItemType;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProfitReportResponse {
    private Long itemId;
    private String itemName;
    private ItemType itemType;
    private double qtySold;
    private double revenue;
    private double cost;
    private double profit;
}
