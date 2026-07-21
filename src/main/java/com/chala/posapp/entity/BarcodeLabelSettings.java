package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "barcode_label_settings",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"branch_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BarcodeLabelSettings extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "label_width_mm", nullable = false)
    private int labelWidthMm;

    @Column(name = "label_height_mm", nullable = false)
    private int labelHeightMm;

    @Column(name = "padding_top_mm", nullable = false)
    private int paddingTopMm;

    @Column(name = "show_shop_name", nullable = false)
    private boolean showShopName;

    @Column(name = "shop_name_text", length = 60)
    private String shopNameText;

    @Column(name = "shop_name_font_size", nullable = false)
    private int shopNameFontSize;

    @Column(name = "shop_name_bold", nullable = false)
    private boolean shopNameBold;

    @Column(name = "show_item_name", nullable = false)
    private boolean showItemName;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_name_source", nullable = false, length = 20)
    @Builder.Default
    private ItemNameSource itemNameSource = ItemNameSource.PRIMARY;

    @Column(name = "item_name_max_chars", nullable = false)
    private int itemNameMaxChars;

    @Column(name = "item_name_font_size", nullable = false)
    private int itemNameFontSize;

    @Column(name = "barcode_format", nullable = false, length = 20)
    private String barcodeFormat;

    @Column(name = "barcode_width", nullable = false, precision = 3, scale = 1)
    private BigDecimal barcodeWidth;

    @Column(name = "barcode_height", nullable = false)
    private int barcodeHeight;

    @Column(name = "show_barcode_value", nullable = false)
    private boolean showBarcodeValue;

    @Column(name = "barcode_value_font_size", nullable = false)
    private int barcodeValueFontSize;

    @Column(name = "show_price", nullable = false)
    private boolean showPrice;

    @Column(name = "price_prefix", nullable = false, length = 10)
    private String pricePrefix;

    @Column(name = "price_font_size", nullable = false)
    private int priceFontSize;

    @Column(name = "price_bold", nullable = false)
    private boolean priceBold;

    @Column(name = "show_footer_text", nullable = false)
    private boolean showFooterText;

    @Column(name = "footer_text", length = 80)
    private String footerText;

    @Column(name = "footer_font_size", nullable = false)
    private int footerFontSize;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
