package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "branch_sequences")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BranchSequence extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "next_invoice_number", nullable = false)
    private Long nextInvoiceNumber;
}