package com.chala.posapp.repository;

import com.chala.posapp.entity.BranchSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BranchSequenceRepository extends JpaRepository<BranchSequence, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BranchSequence b WHERE b.branchId = :branchId")
    Optional<BranchSequence> findByBranchIdWithLock(@Param("branchId") Long branchId);
}