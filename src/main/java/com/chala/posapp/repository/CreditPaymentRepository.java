package com.chala.posapp.repository;

import com.chala.posapp.entity.CreditPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditPaymentRepository extends JpaRepository<CreditPayment, Long> {
    List<CreditPayment> findByCustomerIdOrderByPaidAtDesc(Long customerId);
}
