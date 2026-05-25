package com.chala.posapp.repository;

import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.CustomerNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    boolean existsByPhone(String phone);
    Optional<Customer> findByPhone(String phone);

    List<Customer> findByNameContainingIgnoreCase(String name);

    @Query(value = """
    SELECT id, name, due_amount
    FROM customers
    WHERE tenant_id = :tenantId
      AND due_amount > 0
    ORDER BY due_amount DESC
""", nativeQuery = true)
    List<Object[]> creditDueRaw(@Param("tenantId") String tenantId);

    @Query("""
        SELECT c
        FROM Customer c
        WHERE (:search IS NULL OR :search = ''
            OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.address) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:activeOnly IS NULL OR :activeOnly = false OR c.active = true)
          AND (:active IS NULL OR c.active = :active)
          AND (:from IS NULL OR c.createdAt >= :from)
          AND (:to IS NULL OR c.createdAt <= :to)
        """)
    Page<Customer> searchCustomers(
            @Param("search") String search,
            @Param("activeOnly") Boolean activeOnly,
            @Param("active") Boolean active,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

}
