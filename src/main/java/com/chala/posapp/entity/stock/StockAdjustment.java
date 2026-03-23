package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "stock_adjustments",
        indexes = {
                // Tenant, Branch සහ Item අනුව Filter කරද්දී වේගය වැඩි කිරීමට
                @Index(name = "idx_tenant_branch_item_adj", columnList = "tenant_id, branch_id, item_id"),
                @Index(name = "idx_tenant_created_at_adj", columnList = "tenant_id, created_at")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockAdjustment extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // (Optional) Reference No එකක් ඕනේ නම් මේක දාන්න
    // @Column(length = 40)
    // private String adjustmentNo;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockAdjustmentType type;

    @Column(name = "qty_change", nullable = false)
    private int qtyChange;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "user_id", nullable = false)
    private Long userId; // මේක කරපු යූසර්

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}