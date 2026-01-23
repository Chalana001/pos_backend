package com.chala.posapp.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ItemWithStockResponse {
    private Long id;
    private String barcode;
    private String name;
    private String category;
    private double costPrice;
    private double sellingPrice;
    private int reorderLevel;
    private boolean active;
    private LocalDateTime createdAt;

    private int quantity; // ✅ calculated stock qty (branch or total)
}
