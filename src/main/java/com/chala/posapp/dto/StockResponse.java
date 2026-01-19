package com.chala.posapp.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockResponse {
    private Long id;
    private Long branchId;
    private Long itemId;
    private int quantity;
    private LocalDateTime updatedAt;
}
