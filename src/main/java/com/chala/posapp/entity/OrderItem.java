package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "order_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long itemId;

    // 🔥 NEW FIELD: 1. Batch ID (අත්‍යවශ්‍යයි)
    // Return එකක් ආවොත් හෝ System එකේ දෝෂයක් ආවොත්,
    // හරියටම කොයි Batch එකේ බඩුද අඩු වුනේ කියලා බලන්න මේක ඕන.
    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 80)
    private String barcode;

    @Column(nullable = false, length = 160)
    private String itemName;

    @Column(nullable = false)
    private int qty;

    // 🔥 NEW FIELD: 2. Cost Price (Profit Report එකට අත්‍යවශ්‍යයි)
    // Item Master එකේ Cost එක වෙනස් වුනත්, විකුණපු වෙලාවේ තිබුණ Cost එක
    // මෙතන Save වෙලා තියෙන්න ඕන. නැත්නම් Profit එක වැරදෙනවා.
    @Column(nullable = false)
    private double costPrice;

    @Column(nullable = false)
    private double unitPrice; // Selling Price (Before Discount)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    @Column(nullable = false)
    private double discountValue;

    @Column(nullable = false)
    private double finalUnitPrice; // after discount

    @Column(nullable = false)
    private double lineTotal; // finalUnitPrice * qty
}