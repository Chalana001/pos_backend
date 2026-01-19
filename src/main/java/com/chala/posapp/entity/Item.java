package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String barcode; // itemCode / barcode unique

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 80)
    private String category; // optional

    @Column(nullable = false)
    private double costPrice; // watena gana

    @Column(nullable = false)
    private double sellingPrice;

    @Column(nullable = false)
    private int reorderLevel; // low stock level

    // Optional image feature (nullable)
    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (!active) active = true;
    }
}
