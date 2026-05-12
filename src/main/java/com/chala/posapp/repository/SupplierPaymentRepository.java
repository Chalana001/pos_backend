package com.chala.posapp.repository;

import com.chala.posapp.entity.supplier.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {
    List<SupplierPayment> findBySupplierIdOrderByPaidAtDesc(Long supplierId);
}
