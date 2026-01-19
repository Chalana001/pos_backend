package com.chala.posapp.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ItemResponse {
    private Long id;
    private String barcode;
    private String name;
    private String category;
    private double costPrice;
    private double sellingPrice;
    private int reorderLevel;
    private String imageUrl;
    private boolean active;
    private LocalDateTime createdAt;
}
