package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "grn_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grn_id", nullable = false)
    private GRN grn;

    @ManyToOne
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    private Integer qty;

    // History: මේ GRN එක එනකොට තිබුන මිලගණන් (පස්සේ වෙනස් වෙන්න පුළුවන් නිසා මෙතන save කරගන්නවා)
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;

    private BigDecimal amount; // Line Total (qty * costPrice)
}