package com.chala.posapp.repository;

import com.chala.posapp.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    boolean existsByCode(String code);
    Optional<Branch> findByCode(String code);

    @Query(value = "SELECT COUNT(*) FROM branches", nativeQuery = true)
    long countAllNative();
}
