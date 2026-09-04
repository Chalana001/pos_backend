package com.chala.posapp.repository;

import com.chala.posapp.entity.Promotion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Every finder here filters {@code deletedAt IS NULL}. A retired promotion keeps its row so
 * that reporting can still resolve the terms behind a discount it gave (see
 * {@link Promotion#getDeletedAt()}), but it must never price another sale or appear in the
 * admin list — so there is deliberately no unfiltered lookup on this interface.
 */
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @EntityGraph(attributePaths = "targets")
    List<Promotion> findByDeletedAtIsNullOrderByActiveDescStartAtDescIdDesc();

    @EntityGraph(attributePaths = "targets")
    List<Promotion> findByActiveTrueAndDeletedAtIsNullAndStartAtLessThanEqualAndEndAtGreaterThanEqualOrderByPriorityDescIdDesc(
            LocalDateTime startAt,
            LocalDateTime endAt
    );

    @EntityGraph(attributePaths = "targets")
    Optional<Promotion> findByIdAndDeletedAtIsNull(Long id);
}
