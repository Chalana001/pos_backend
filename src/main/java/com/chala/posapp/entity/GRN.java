package com.chala.posapp.entity;

import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "grn",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"grn_no"})
        },
        indexes = {
                @Index(name = "idx_grn_no", columnList = "grn_no")
        }
)
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GRN extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grn_no", nullable = false)
    private String grnNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id")
    @JsonIgnore
    private Purchase purchase;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(precision = 12, scale = 2)
    private BigDecimal paidAmount;

    private String note;

    private LocalDateTime receivedAt;

    private Long createdByUserId;
}