package com.chala.posapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "grn")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GRN {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String grnNo;

    @ManyToOne
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    // 👇 අලුත් සම්බන්ධය (Link to Parent)
    @ManyToOne
    @JoinColumn(name = "purchase_id", nullable = false)
    @JsonIgnore // Infinite Loop වලක්වන්න
    private Purchase purchase;

    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private String note;
    private LocalDateTime receivedAt;
    private Long createdByUserId;
}