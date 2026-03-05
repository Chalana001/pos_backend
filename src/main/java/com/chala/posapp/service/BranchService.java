package com.chala.posapp.service;

import com.chala.posapp.dto.branch.BranchCreateRequest;
import com.chala.posapp.dto.branch.BranchResponse;
import com.chala.posapp.dto.branch.BranchUpdateRequest;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;

    public BranchResponse createBranch(BranchCreateRequest request) {
        String code = request.getCode().trim().toUpperCase();

        if (branchRepository.existsByCode(code))
            throw new AlreadyExistsException("Branch code already exists: " + code);

        Branch branch = Branch.builder()
                .code(code)
                .name(request.getName().trim())
                .address(request.getAddress())
                .phone(request.getPhone())
                .active(true)
                .build();

        Branch saved = branchRepository.save(branch);
        return mapToResponse(saved);
    }

    public BranchResponse getBranch(Long id) {
        Branch branch = branchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        return mapToResponse(branch);
    }

    public List<BranchResponse> getAllBranches(Boolean activeOnly) {
        System.out.println("called branche in service");
        List<Branch> branches = branchRepository.findAll();
        System.out.println(branches);
        return branches.stream()
                .filter(b -> activeOnly == null || !activeOnly || b.isActive())
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public BranchResponse updateBranch(Long id, BranchUpdateRequest request) {
        Branch branch = branchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        if (request.getName() != null) branch.setName(request.getName().trim());
        if (request.getAddress() != null) branch.setAddress(request.getAddress());
        if (request.getPhone() != null) branch.setPhone(request.getPhone());
        if (request.getActive() != null) branch.setActive(request.getActive());

        return mapToResponse(branch);
    }

    public void deleteBranch(Long id) {
        Branch branch = branchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        branch.setActive(false);
        branchRepository.save(branch);
    }

    private BranchResponse mapToResponse(Branch branch) {
        return BranchResponse.builder()
                .id(branch.getId())
                .code(branch.getCode())
                .name(branch.getName())
                .address(branch.getAddress())
                .phone(branch.getPhone())
                .active(branch.isActive())
                .createdAt(branch.getCreatedAt())
                .build();
    }
}
