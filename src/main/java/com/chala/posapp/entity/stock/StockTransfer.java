package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "stock_transfers",
        uniqueConstraints = {
                // 1. Multi-tenant Unique Constraint එක (transferNo එක tenant_id එකත් එක්ක Unique වෙයි)
                @UniqueConstraint(columnNames = {"tenant_id", "transfer_no"})
        },
        indexes = {
                // 2. Index වලට Database Column Names පාවිච්චි කළා, ඒ වගේම tenant_id එකතු කළා
                @Index(name = "idx_tenant_transfer_no", columnList = "tenant_id, transfer_no"),
                @Index(name = "idx_tenant_transfer_branches", columnList = "tenant_id, from_branch_id, to_branch_id"),
                @Index(name = "idx_tenant_transfer_status", columnList = "tenant_id, status")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockTransfer extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 3. මෙතන තිබ්බ unique = true කෑල්ල අයින් කළා (උඩින් UniqueConstraint එක දාපු නිසා)
    @Column(name = "transfer_no", nullable = false, length = 40)
    private String transferNo;

    @Column(name = "from_branch_id", nullable = false)
    private Long fromBranchId;

    @Column(name = "to_branch_id", nullable = false)
    private Long toBranchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockTransferStatus status;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Column(name = "received_by_user_id")
    private Long receivedByUserId;

    @Column(length = 255)
    private String note;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    // 4. MappedBy අවුල විසඳුවා. Long ID එකක් පාවිච්චි කරන නිසා @JoinColumn එක දැම්මා.
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", insertable = false, updatable = false)
    @Builder.Default
    private List<StockTransferItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        requestedAt = LocalDateTime.now();
        if (status == null) status = StockTransferStatus.IN_TRANSIT;
    }
}