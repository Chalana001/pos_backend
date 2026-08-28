package com.chala.posapp.repository;

import com.chala.posapp.entity.AnnouncementDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnnouncementDismissalRepository extends JpaRepository<AnnouncementDismissal, Long> {

    List<AnnouncementDismissal> findByTenantId(String tenantId);

    boolean existsByAnnouncementIdAndTenantId(Long announcementId, String tenantId);

    long countByAnnouncementId(Long announcementId);

    @Query("select d.announcementId from AnnouncementDismissal d where d.tenantId = :tenantId")
    List<Long> dismissedIdsFor(@Param("tenantId") String tenantId);
}
