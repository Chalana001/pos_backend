package com.chala.posapp.service;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.GrnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GrnNumberService {

    private final GrnRepository grnRepository;
    private final BranchRepository branchRepository; // Branch Code එක ගන්න ඕන නිසා

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateGrnNo(Long branchId) {

        // 1. Branch Code එක ලබා ගැනීම (Code එකක් නැත්නම් ID එක පාවිච්චි කරනවා)
        String branchCode = branchRepository.findById(branchId)
                .map(branch -> branch.getCode() != null ? branch.getCode().toUpperCase() : "BR" + branch.getId())
                .orElse("UNK");

        // 2. ඒ Branch එකේ අන්තිම GRN එක ගන්නවා
        // Repository එකේ method නම: findTopByBranchIdOrderByIdDesc
        return grnRepository.findTopByBranchIdOrderByIdDesc(branchId)
                .map(lastGrn -> {
                    String lastNo = lastGrn.getGrnNo();

                    // Format: "COL-GRN-0001" -> Split by "-"
                    String[] parts = lastNo.split("-");

                    // Array එකේ අන්තිම කෑල්ල තමයි Number එක (Example: parts[2] -> "0005")
                    if (parts.length > 0) {
                        try {
                            String numberPart = parts[parts.length - 1]; // අන්තිම කොටස ගන්නවා
                            long number = Long.parseLong(numberPart);

                            // අලුත් නම්බර් එක හදනවා (Prefix එකත් එක්ක)
                            return String.format("%s-GRN-%04d", branchCode, number + 1);
                        } catch (NumberFormatException e) {
                            return getDefault(branchCode);
                        }
                    }
                    return getDefault(branchCode);
                })
                .orElse(getDefault(branchCode)); // මුකුත් නැත්නම් 0001 න් පටන් ගන්නවා
    }

    // Default Format එක හදන Helper Method එක
    private String getDefault(String branchCode) {
        return String.format("%s-GRN-0001", branchCode);
    }
}