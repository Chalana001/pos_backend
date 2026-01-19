package com.chala.posapp.repository;

import com.chala.posapp.entity.StockTransfer;
import com.chala.posapp.entity.StockTransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    Optional<StockTransfer> findByTransferNo(String transferNo);

    List<StockTransfer> findByFromBranchIdOrderByRequestedAtDesc(Long fromBranchId);

    List<StockTransfer> findByToBranchIdOrderByRequestedAtDesc(Long toBranchId);

    // ✅ pending lists
    List<StockTransfer> findByToBranchIdAndStatusOrderByRequestedAtDesc(Long toBranchId, StockTransferStatus status);

    List<StockTransfer> findByFromBranchIdAndStatusOrderByRequestedAtDesc(Long fromBranchId, StockTransferStatus status);

    long countByFromBranchId(Long fromBranchId);
}
