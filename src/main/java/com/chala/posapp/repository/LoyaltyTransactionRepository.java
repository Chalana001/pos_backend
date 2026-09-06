package com.chala.posapp.repository;

import com.chala.posapp.entity.LoyaltyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    List<LoyaltyTransaction> findByCustomerIdOrderByAtDescIdDesc(Long customerId);

    List<LoyaltyTransaction> findByOrderIdAndReversedAtIsNull(Long orderId);
}
