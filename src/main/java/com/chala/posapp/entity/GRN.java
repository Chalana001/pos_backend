package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "grn") // Goods Received Note
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GRN {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String grnNo; // GRN-001

    @ManyToOne
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    private BigDecimal totalAmount;
    private BigDecimal paidAmount; // එවලේම ගෙව්ව නම් ගාන

    private String note;

    private LocalDateTime receivedAt; // බඩු භාරගත්ත වෙලාව

    private Long createdByUserId; // කවුද Enter කළේ?
}