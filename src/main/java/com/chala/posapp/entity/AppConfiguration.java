package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "app_configurations",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"branch_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppConfiguration extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long branchId;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean recipeItemsEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean weightItemsEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean servicesEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean tableManagementEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean dineInEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'MAIN_AND_SUB'")
    @Builder.Default
    private CategoryMode categoryMode = CategoryMode.MAIN_AND_SUB;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'MANAGER_OVERRIDE'")
    @Builder.Default
    private StockOverrideMode stockOverrideMode = StockOverrideMode.MANAGER_OVERRIDE;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean adminStockOverrideAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean managerStockOverrideAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean cashierStockOverrideAllowed = false;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean warrantyEnabled = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean kotEnabled = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean printReceiptAfterCheckout = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean adminWarrantyAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean managerWarrantyAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean cashierWarrantyAllowed = false;

    // Scale-barcode decoding (tenant V51, moved here from barcode_label_settings):
    // the digit layout the branch's own weighing scale prints, so that
    // ItemService.getByBarcode can pull an embedded weight or price out of a
    // scanned label. See barcode/ScaleBarcodeFormat and ScaleBarcodeDecoder.
    @Column(name = "scale_barcode_enabled", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean scaleBarcodeEnabled = false;

    // Key of the ScaleBarcodeFormatPresets template the admin started from, or
    // null/"CUSTOM". Informational only; decoding uses the fields below.
    @Column(name = "scale_barcode_preset_key", length = 50)
    private String scaleBarcodePresetKey;

    // One or more prefixes, comma separated, letters allowed ("20,21" or "NS").
    // Each entry is exactly scaleBarcodePrefixLength characters; stored upper case.
    @Column(name = "scale_barcode_prefix", length = 40)
    private String scaleBarcodePrefix;

    @Column(name = "scale_barcode_prefix_length", nullable = false, columnDefinition = "int default 2")
    @Builder.Default
    private int scaleBarcodePrefixLength = 2;

    @Column(name = "scale_barcode_item_code_length", nullable = false, columnDefinition = "int default 5")
    @Builder.Default
    private int scaleBarcodeItemCodeLength = 5;

    @Column(name = "scale_barcode_value_length", nullable = false, columnDefinition = "int default 5")
    @Builder.Default
    private int scaleBarcodeValueLength = 5;

    @Enumerated(EnumType.STRING)
    @Column(name = "scale_barcode_value_type", nullable = false, length = 20, columnDefinition = "varchar(20) default 'WEIGHT'")
    @Builder.Default
    private ScaleBarcodeValueType scaleBarcodeValueType = ScaleBarcodeValueType.WEIGHT;

    // Unit of a WEIGHT value: G or KG. Ignored for PRICE.
    @Enumerated(EnumType.STRING)
    @Column(name = "scale_barcode_weight_unit", nullable = false, length = 5, columnDefinition = "varchar(5) default 'G'")
    @Builder.Default
    private MeasurementUnit scaleBarcodeWeightUnit = MeasurementUnit.G;

    // Implied decimal places in the value digits: "01234" with 3 decimals is
    // 1.234 (kg or rupees), with 0 decimals it is 1234 (g or rupees).
    @Column(name = "scale_barcode_value_decimals", nullable = false, columnDefinition = "int default 0")
    @Builder.Default
    private int scaleBarcodeValueDecimals = 0;

    // Whether "00123" in the item-code segment looks up item barcode "123".
    @Column(name = "scale_barcode_strip_leading_zeros", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean scaleBarcodeStripLeadingZeros = false;

    @Column(name = "scale_barcode_has_check_digit", nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean scaleBarcodeHasCheckDigit = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (categoryMode == null) {
            categoryMode = CategoryMode.MAIN_AND_SUB;
        }
        if (stockOverrideMode == null) {
            stockOverrideMode = StockOverrideMode.MANAGER_OVERRIDE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
