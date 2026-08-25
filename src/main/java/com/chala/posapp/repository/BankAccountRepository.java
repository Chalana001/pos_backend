package com.chala.posapp.repository;

import com.chala.posapp.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    List<BankAccount> findAllByOrderByNameAsc();
    List<BankAccount> findByActiveTrueOrderByNameAsc();
    Optional<BankAccount> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}
