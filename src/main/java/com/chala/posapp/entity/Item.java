package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "items", indexes = {
        @Index(name = "idx_item_barcode", columnList = "barcode") // Barcode Search එක වේගවත් කරන්න
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String barcode;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 80)
    private String category;

    // --- MONEY FIELDS (Changed double to BigDecimal) ---
    // Precision 10, Scale 2 කියන්නේ ඉලක්කම් 10යි, දශම ස්ථාන 2යි (උදා: 12345678.99)

    @Column(name = "cost_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "selling_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPrice;

    // ---------------------------------------------------

    @Column(nullable = false)
    private int reorderLevel;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    @Builder.Default // Builder පාවිච්චි කරද්දී මේක false නොවී true විදියටම තියෙන්න
    private boolean active = true;

    @Column(nullable = false, updatable = false) // හදපු දිනය කවදාවත් වෙනස් වෙන්නේ නෑ
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default // List එක null නොවී හිස් List එකක් ලෙස හැදෙන්න
    private List<SupplierItem> suppliers = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}