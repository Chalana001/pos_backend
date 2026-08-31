package com.chala.posapp.repository;

import com.chala.posapp.entity.stock.StockProcessingOutput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockProcessingOutputRepository extends JpaRepository<StockProcessingOutput, Long> {
    List<StockProcessingOutput> findByProcessingIdOrderByIdAsc(Long processingId);
}
