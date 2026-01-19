package com.chala.posapp.repository;

import com.chala.posapp.entity.CashDrop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CashDropRepository extends JpaRepository<CashDrop, Long> {
    List<CashDrop> findByShiftId(Long shiftId);
}
