package com.chala.posapp.service;

import com.chala.posapp.dto.CreditPaymentRequest;
import com.chala.posapp.dto.CreditPaymentResponse;
import com.chala.posapp.entity.CreditPayment;
import com.chala.posapp.entity.Customer;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CreditPaymentRepository;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CreditService {

    private final CustomerRepository customerRepository;
    private final CreditPaymentRepository creditPaymentRepository;
    private final UserRepository userRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    @Transactional
    public CreditPaymentResponse settleCredit(CreditPaymentRequest request) {
        User user = getLoggedUser();

        if (user.getBranchId() == null)
            throw new NotAssignedException("User branch not assigned");

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        if (!customer.isActive())
            throw new RuntimeException("Customer inactive");

        if (request.getAmount() <= 0)
            throw new RuntimeException("Invalid amount");

        if (customer.getDueAmount() <= 0)
            throw new RuntimeException("Customer has no due");

        double newDue = customer.getDueAmount() - request.getAmount();
        if (newDue < 0) newDue = 0;

        customer.setDueAmount(newDue);

        CreditPayment payment = CreditPayment.builder()
                .customerId(customer.getId())
                .branchId(user.getBranchId())
                .cashierUserId(user.getId())
                .amount(request.getAmount())
                .note(request.getNote())
                .build();

        creditPaymentRepository.save(payment);

        return CreditPaymentResponse.builder()
                .id(payment.getId())
                .customerId(payment.getCustomerId())
                .branchId(payment.getBranchId())
                .cashierUserId(payment.getCashierUserId())
                .amount(payment.getAmount())
                .note(payment.getNote())
                .paidAt(payment.getPaidAt())
                .build();
    }

    public List<CreditPaymentResponse> paymentHistory(Long customerId) {
        return creditPaymentRepository.findByCustomerIdOrderByPaidAtDesc(customerId).stream()
                .map(p -> CreditPaymentResponse.builder()
                        .id(p.getId())
                        .customerId(p.getCustomerId())
                        .branchId(p.getBranchId())
                        .cashierUserId(p.getCashierUserId())
                        .amount(p.getAmount())
                        .note(p.getNote())
                        .paidAt(p.getPaidAt())
                        .build())
                .toList();
    }
}
