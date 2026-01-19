package com.chala.posapp.repository;

import com.chala.posapp.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    boolean existsByCode(String code);
    Optional<Branch> findByCode(String code);
}
