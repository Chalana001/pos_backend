package com.chala.posapp.repository;

import com.chala.posapp.entity.ReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, Long> {
    List<ReportSchedule> findByRequestedByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<ReportSchedule> findByIdAndRequestedByUserId(Long id, Long userId);
    List<ReportSchedule> findTop20ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(LocalDateTime now);
}
