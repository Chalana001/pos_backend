package com.chala.posapp.entity.supplier;

import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// MISS-06: Auto-filter deleted suppliers from all JPA queries
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(
        name = "suppliers",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"phone"}),
                @UniqueConstraint(columnNames = {"email"})
        },
        indexes = {
                @Index(name = "idx_supplier_phone", columnList = "phone"),
                @Index(name = "idx_supplier_email", columnList = "email")
        }
)
@Getter
@Setter
public class Supplier extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 1000)
    private String address;

    @Column(name = "due_amount", precision = 12, scale = 2)
    private BigDecimal dueAmount = BigDecimal.ZERO;

    private Boolean active = true;

    /** MISS-06: Soft-delete timestamp — null means not deleted. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "supplier", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierContact> contacts = new ArrayList<>();

    @OneToOne(mappedBy = "supplier", cascade = CascadeType.ALL, orphanRemoval = true)
    private SupplierBankDetails bankDetails;

    @OneToMany(mappedBy = "supplier", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierItem> suppliedItems = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (dueAmount == null) dueAmount = BigDecimal.ZERO;
        if (active == null) active = true;
    }
}
