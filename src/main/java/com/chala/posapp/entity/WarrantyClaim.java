package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "warranty_claims",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"claim_no"})
        },
        indexes = {
                @Index(name = "idx_warranty_claim_warranty", columnList = "warranty_id"),
                @Index(name = "idx_warranty_claim_branch", columnList = "branch_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarrantyClaim extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_no", nullable = false, length = 100)
    private String claimNo;

    @Column(name = "warranty_id", nullable = false)
    private Long warrantyId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private WarrantyClaimActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WarrantyClaimStatus status;

    @Column(name = "issue_description", nullable = false, length = 1000)
    private String issueDescription;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = WarrantyClaimStatus.OPEN;
        }
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
