package com.chala.posapp.dto.report;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TopSellingItemResponse {
    private Long itemId;
    private String itemName;
    private double qtySold;
    private double revenue;
}
