package com.chala.posapp.repository;

import com.chala.posapp.entity.PurchaseReturn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseReturnRepository extends JpaRepository<PurchaseReturn, Long> {

    List<PurchaseReturn> findByPurchaseIdOrderByCreatedAtDesc(Long purchaseId);

    Optional<PurchaseReturn> findByDebitNoteNo(String debitNoteNo);

    boolean existsByDebitNoteNo(String debitNoteNo);

    long countByPurchaseId(Long purchaseId);

    List<PurchaseReturn> findByGrnId(Long grnId);
}
