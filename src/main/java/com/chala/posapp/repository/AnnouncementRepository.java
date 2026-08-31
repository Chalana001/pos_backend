package com.chala.posapp.repository;

import com.chala.posapp.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findAllByOrderByCreatedAtDesc();

    /**
     * Published and inside its window.
     *
     * <p>Audience filtering and severity ordering both happen in the service: MODULE
     * targeting needs the resolved module set, which SQL here cannot see, and ordering by
     * enum rank in HQL needs fragile literal syntax for a list that is only ever a handful
     * of rows.
     */
    @Query("""
            select a from Announcement a
            where a.published = true
              and (a.activeFrom is null or a.activeFrom <= :now)
              and (a.activeUntil is null or a.activeUntil > :now)
            order by a.createdAt desc
            """)
    List<Announcement> findLive(@Param("now") LocalDateTime now);
}
