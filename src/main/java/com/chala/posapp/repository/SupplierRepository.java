package com.chala.posapp.repository;

import com.chala.posapp.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    // Active අය විතරක් ගන්න
    List<Supplier> findByActiveTrue();

    // ඕන නම් නමෙන් search කරන්න
    List<Supplier> findByNameContainingIgnoreCase(String name);
}
