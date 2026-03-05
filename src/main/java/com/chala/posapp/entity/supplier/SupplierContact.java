package com.chala.posapp.entity.supplier;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "supplier_contacts")
@Getter
@Setter
public class SupplierContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String contactNumber;

    @ManyToOne
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;
}
