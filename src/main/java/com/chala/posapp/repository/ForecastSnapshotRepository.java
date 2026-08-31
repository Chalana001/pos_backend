package com.chala.posapp.repository;

import com.chala.posapp.entity.ForecastSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ForecastSnapshotRepository extends JpaRepository<ForecastSnapshot, Long> {
    boolean existsByExportJobId(Long exportJobId);
    List<ForecastSnapshot> findTop20ByEvaluatedAtIsNullAndWindowEndLessThanEqualOrderByWindowEndAsc(LocalDateTime now);
    List<ForecastSnapshot> findTop20ByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc();
    long countByEvaluatedAtIsNull();
    long countByEvaluatedAtIsNotNull();
    long countByEvaluatedAtIsNullAndWindowEndAfter(LocalDateTime now);
    List<ForecastSnapshot> findByEvaluatedAtIsNotNull();
    List<ForecastSnapshot> findByCreatedAtBefore(LocalDateTime cutoff);
}
