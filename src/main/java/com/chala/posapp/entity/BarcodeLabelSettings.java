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

    @Column(name = "show_expiry", nullable = false)
    private boolean showExpiry;

    @Column(name = "expiry_prefix", nullable = false, length = 20)
    private String expiryPrefix;

    @Column(name = "expiry_font_size", nullable = false)
    private int expiryFontSize;

    @Column(name = "expiry_date_format", nullable = false, length = 20)
    private String expiryDateFormat;

    @Column(name = "direct_print_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean directPrintEnabled;

    @Column(name = "printer_name", length = 160)
    private String printerName;

    @Column(name = "printer_copies", nullable = false, columnDefinition = "int default 1")
    private int printerCopies;

    // Ordered element-array layout as a raw JSON string. null → render from the
    // flat show*/font-size columns above (legacy layout).
    @Lob
    @Column(name = "layout_json", columnDefinition = "LONGTEXT")
    private String layoutJson;

    // Scale-barcode decoding: lets a shop teach the backend the digit layout its
    // own weighing-scale / label-printer device prints (prefix + item-code +
    // weight-or-price + optional check digit), so ItemService.getByBarcode can
    // decode it generically instead of any format being hardcoded.
    @Column(name = "scale_barcode_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean scaleBarcodeEnabled;

    // Key of the ScaleBarcodeFormatPresets template the admin started from, or
    // null/"CUSTOM" if every field below was filled in by hand. Informational
    // only — decoding always uses the fields below, never this key.
    @Column(name = "scale_barcode_preset_key", length = 50)
    private String scaleBarcodePresetKey;

    @Column(name = "scale_barcode_prefix", length = 4)
    private String scaleBarcodePrefix;

    @Column(name = "scale_barcode_prefix_length", nullable = false, columnDefinition = "int default 2")
    private int scaleBarcodePrefixLength;

    @Column(name = "scale_barcode_item_code_length", nullable = false, columnDefinition = "int default 5")
    private int scaleBarcodeItemCodeLength;

    @Column(name = "scale_barcode_value_length", nullable = false, columnDefinition = "int default 5")
    private int scaleBarcodeValueLength;

    @Enumerated(EnumType.STRING)
    @Column(name = "scale_barcode_value_type", nullable = false, length = 20)
    @Builder.Default
    private ScaleBarcodeValueType scaleBarcodeValueType = ScaleBarcodeValueType.WEIGHT_GRAMS;

    @Column(name = "scale_barcode_has_check_digit", nullable = false, columnDefinition = "boolean default true")
    private boolean scaleBarcodeHasCheckDigit;

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
