package com.chala.posapp.entity.supplier;

import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "supplier_items")
@Getter
@Setter
public class SupplierItem extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Double lastBuyingPrice;

    private String supplierItemCode; // optional

    private Boolean primarySupplier = false;

    @ManyToOne
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @ManyToOne
    @JoinColumn(name = "item_id")
    private Item item;
}

