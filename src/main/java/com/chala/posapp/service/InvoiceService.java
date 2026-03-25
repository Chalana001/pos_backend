package com.chala.posapp.service;

import com.chala.posapp.entity.BranchSequence;
import com.chala.posapp.repository.BranchSequenceRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final BranchSequenceRepository sequenceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateInvoiceNo(Long branchId) {

        String tenantId = TenantContext.getTenant();

        BranchSequence seq = sequenceRepository.findByBranchIdAndTenantIdWithLock(branchId, tenantId)
                .orElseGet(() -> BranchSequence.builder()
                        .branchId(branchId)
                        .nextInvoiceNumber(1L)
                        .build());

        long currentCount = seq.getNextInvoiceNumber();

        seq.setNextInvoiceNumber(currentCount + 1);
        sequenceRepository.save(seq);

        LocalDate today = LocalDate.now();
        return String.format("INV-%d-%02d-B%d-%06d",
                today.getYear(),
                today.getMonthValue(),
                branchId,
                currentCount
        );
    }
}