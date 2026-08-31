package com.chala.posapp.repository;

import com.chala.posapp.entity.ReportExportJob;
import com.chala.posapp.entity.ReportExportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface ReportExportJobRepository extends JpaRepository<ReportExportJob, Long> {
    Page<ReportExportJob> findByRequestedByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Optional<ReportExportJob> findByIdAndRequestedByUserId(Long id, Long userId);
    List<ReportExportJob> findTop5ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(ReportExportStatus status, LocalDateTime now);
    List<ReportExportJob> findByStatusAndStartedAtBefore(ReportExportStatus status, LocalDateTime cutoff);
    List<ReportExportJob> findByCompletedAtBefore(LocalDateTime cutoff);
    long countByStatus(ReportExportStatus status);
    long countByStatusAndStartedAtBefore(ReportExportStatus status, LocalDateTime cutoff);

    @Modifying
    @Query("update ReportExportJob j set j.status = :processing, j.attemptCount = j.attemptCount + 1, j.startedAt = :now, j.errorMessage = null where j.id = :id and j.status = :queued")
    int claim(@Param("id") Long id, @Param("queued") ReportExportStatus queued,
              @Param("processing") ReportExportStatus processing, @Param("now") LocalDateTime now);

    @Modifying
    @Query("update ReportExportJob j set j.status = :queued, j.startedAt = null, j.errorMessage = :message, j.nextAttemptAt = :now where j.id = :id and j.status = :processing and j.startedAt < :cutoff")
    int recover(@Param("id") Long id, @Param("processing") ReportExportStatus processing,
                @Param("queued") ReportExportStatus queued, @Param("cutoff") LocalDateTime cutoff,
                @Param("now") LocalDateTime now, @Param("message") String message);
}
