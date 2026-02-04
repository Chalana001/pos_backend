package com.chala.posapp.service;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.GrnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class GrnNumberService {

    private final GrnRepository grnRepository;
    private final BranchRepository branchRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateGrnNo(Long branchId) {

        String branchCode = branchRepository.findById(branchId)
                .map(branch -> branch.getCode() != null ? branch.getCode().toUpperCase() : "BR" + branch.getId())
                .orElse("UNK");

        return grnRepository.findTopByBranchIdOrderByIdDesc(branchId)
                .map(lastGrn -> {
                    String lastNo = lastGrn.getGrnNo();

                    String[] parts = lastNo.split("-");

                    if (parts.length > 0) {
                        try {
                            String numberPart = parts[parts.length - 1];
                            long number = Long.parseLong(numberPart);

                            // අලුත් නම්බර් එක හදනවා (Prefix එකත් එක්ක)
                            return String.format("%s-GRN-%04d", branchCode, number + 1);
                        } catch (NumberFormatException e) {
                            return getDefault(branchCode);
                        }
                    }
                    return getDefault(branchCode);
                })
                .orElse(getDefault(branchCode));
    }

    private String getDefault(String branchCode) {
        return String.format("%s-GRN-0001", branchCode);
    }
}