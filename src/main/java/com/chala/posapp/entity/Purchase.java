package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "purchase")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // User අතින් ගහන Supplier Invoice No එක (Ex: INV-999)
    private String invoiceNo;

    @ManyToOne
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    private LocalDateTime createdAt;
    private BigDecimal grandTotal; // මුළු බිලේ වටිනාකම

    // එක Purchase එකක GRN ගොඩක් තියෙන්න පුළුවන්
    @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL)
    private List<GRN> grnList = new ArrayList<>();
}