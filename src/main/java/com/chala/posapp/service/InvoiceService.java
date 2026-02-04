package com.chala.posapp.service;

import com.chala.posapp.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final OrderRepository orderRepository;

    public String generateInvoiceNo(Long branchId) {

        LocalDate today = LocalDate.now();
        long count = orderRepository.countByBranchId(branchId) + 1;
        return String.format("INV-%d-%02d-B%d-%06d",
                today.getYear(),
                today.getMonthValue(),
                branchId,
                count
        );
    }
}
