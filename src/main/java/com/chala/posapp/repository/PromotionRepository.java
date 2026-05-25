package com.chala.posapp.repository;

import com.chala.posapp.entity.Promotion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @EntityGraph(attributePaths = "targets")
    List<Promotion> findAllByOrderByActiveDescStartAtDescIdDesc();

    @EntityGraph(attributePaths = "targets")
    List<Promotion> findByActiveTrueAndStartAtLessThanEqualAndEndAtGreaterThanEqualOrderByPriorityDescIdDesc(
            LocalDateTime startAt,
            LocalDateTime endAt
    );
}
