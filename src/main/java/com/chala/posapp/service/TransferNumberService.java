package com.chala.posapp.service;

import com.chala.posapp.repository.StockTransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferNumberService {

    private final StockTransferRepository stockTransferRepository;

    public String generateTransferNo(Long fromBranchId) {
        LocalDate today = LocalDate.now();
        long count = stockTransferRepository.countByFromBranchId(fromBranchId) + 1;

        return String.format("TR-%d-%02d-B%d-%06d",
                today.getYear(),
                today.getMonthValue(),
                fromBranchId,
                count
        );
    }
}
