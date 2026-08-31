package com.chala.posapp.repository;

import com.chala.posapp.entity.StockProcessingOutputLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockProcessingOutputLinkRepository extends JpaRepository<StockProcessingOutputLink, Long> {
    List<StockProcessingOutputLink> findBySourceItemIdOrderByIdAsc(Long sourceItemId);
    void deleteBySourceItemId(Long sourceItemId);
}
