package com.chala.posapp.repository;

import com.chala.posapp.entity.stock.PurchaseStockOffset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseStockOffsetRepository extends JpaRepository<PurchaseStockOffset, Long> {

    List<PurchaseStockOffset> findByPurchaseIdOrderByIdAsc(Long purchaseId);

    List<PurchaseStockOffset> findByPurchaseIdAndReversedAtIsNullOrderByIdAsc(Long purchaseId);
}
