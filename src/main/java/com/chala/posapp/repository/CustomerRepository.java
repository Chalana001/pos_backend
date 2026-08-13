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

    // PERF-08 FIX: FULLTEXT search for POS customer lookup (search length >= 3).
    // Requires V11 migration: ALTER TABLE customers ADD FULLTEXT INDEX ft_customers_name_phone (name, phone).
    // Falls back to findByNameContainingIgnoreCase() in CustomerService for short (<3 char) inputs.
    @Query(value = """
        SELECT * FROM customers c
        WHERE MATCH(c.name, c.phone) AGAINST (:search IN BOOLEAN MODE)
          AND c.active = true
        ORDER BY MATCH(c.name, c.phone) AGAINST (:search IN BOOLEAN MODE) DESC
        LIMIT 30
        """, nativeQuery = true)
    List<Customer> searchFt(@Param("search") String search);

    @Query(value = """
        SELECT
            c.id,
            c.name,
            COALESCE(SUM(o.due_amount), 0) AS due_amount
        FROM customers c
        JOIN orders o ON o.customer_id = c.id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.due_amount > 0
          AND o.status = 'COMPLETED'
          AND c.active = true
          AND c.deleted_at IS NULL
        GROUP BY c.id, c.name
        HAVING COALESCE(SUM(o.due_amount), 0) > 0
        ORDER BY COALESCE(SUM(o.due_amount), 0) DESC
        """, nativeQuery = true)
    List<Object[]> creditDueRaw(@Param("branchId") Long branchId);

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
