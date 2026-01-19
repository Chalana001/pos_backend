package com.chala.posapp.repository;

import com.chala.posapp.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import com.chala.posapp.dto.report.CreditDueResponse;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    boolean existsByPhone(String phone);
    Optional<Customer> findByPhone(String phone);

    List<Customer> findByNameContainingIgnoreCase(String name);

    @Query(value = """
    SELECT id, name, due_amount
    FROM customers
    WHERE due_amount > 0
    ORDER BY due_amount DESC
""", nativeQuery = true)
    List<Object[]> creditDueRaw();

}
