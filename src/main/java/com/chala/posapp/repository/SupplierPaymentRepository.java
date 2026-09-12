package com.chala.posapp.repository;

import com.chala.posapp.entity.supplier.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {
    List<SupplierPayment> findBySupplierIdOrderByPaidAtDesc(Long supplierId);

    /**
     * Has any supplier payment been allocated to this bill?
     *
     * Cancelling only reverses the bill's remaining due; it does not touch payment rows or
     * give back what was already paid, so a bill with payments against it cannot be voided
     * without overstating the supplier ledger by the amount already settled.
     */
    boolean existsByPurchaseId(Long purchaseId);
}
