package com.chala.posapp.repository;

import com.chala.posapp.entity.CustomerNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerNoteRepository extends JpaRepository<CustomerNote, Long> {
    Page<CustomerNote> findByCustomerId(Long customerId, Pageable pageable);
}
