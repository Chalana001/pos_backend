package com.chala.posapp.repository;

import com.chala.posapp.entity.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    List<PaymentMethod> findAllByOrderBySortOrderAscDisplayNameAsc();

    List<PaymentMethod> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    Optional<PaymentMethod> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByDisplayNameIgnoreCase(String displayName);
}
