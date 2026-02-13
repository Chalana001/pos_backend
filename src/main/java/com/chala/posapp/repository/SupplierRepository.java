package com.chala.posapp.repository;

import com.chala.posapp.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByActiveTrue();

    List<Supplier> findByNameContainingIgnoreCase(String name);

    boolean existsByEmail(String email);
}
