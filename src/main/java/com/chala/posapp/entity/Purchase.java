package com.chala.posapp.entity;

import com.chala.posapp.entity.supplier.Supplier;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "purchase",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "supplier_id", "invoice_no"})
        }
)
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Purchase extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", length = 100)
    private String invoiceNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    private LocalDateTime createdAt;

    @Column(precision = 12, scale = 2)
    private BigDecimal grandTotal;

    @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL)
    @Builder.Default
    private List<GRN> grnList = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}