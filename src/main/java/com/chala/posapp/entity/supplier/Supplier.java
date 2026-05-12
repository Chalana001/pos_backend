package com.chala.posapp.entity.supplier;

import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "suppliers",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "phone"}),
                @UniqueConstraint(columnNames = {"tenant_id", "email"})
        },
        indexes = {
                @Index(name = "idx_tenant_supplier_phone", columnList = "tenant_id, phone"),
                @Index(name = "idx_tenant_supplier_email", columnList = "tenant_id, email")
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
