package com.chala.posapp.repository;

import com.chala.posapp.entity.OrderPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderPaymentRepository extends JpaRepository<OrderPayment, Long> {
    Optional<OrderPayment> findByOrderId(Long orderId);
}
