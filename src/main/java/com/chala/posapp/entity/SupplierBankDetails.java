package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "supplier_bank_details")
@Getter @Setter
public class SupplierBankDetails {

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
