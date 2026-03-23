package com.chala.posapp.entity.supplier;

import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "supplier_bank_details")
@Getter @Setter
public class SupplierBankDetails extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String bankName;
    private String accountNumber;
    private String accountName;
    private String branchName;

    @OneToOne
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;
}
