package com.chala.posapp.repository;

import com.chala.posapp.entity.OrderReturn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, Long> {

    // All returns for a given original order
    List<OrderReturn> findByOriginalOrderIdOrderByCreatedAtDesc(Long originalOrderId);

    // Lookup by return number (for reprint / detail view)
    Optional<OrderReturn> findByReturnNo(String returnNo);

    // Does a return number already exist? (uniqueness guard)
    boolean existsByReturnNo(String returnNo);

    // How many returns exist for a given original order (used to build RTN number suffix)
    long countByOriginalOrderId(Long originalOrderId);
}
