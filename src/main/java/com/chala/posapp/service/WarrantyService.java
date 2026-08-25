package com.chala.posapp.service;

import com.chala.posapp.dto.warranty.WarrantyClaimRequest;
import com.chala.posapp.dto.warranty.WarrantyClaimListResponse;
import com.chala.posapp.dto.warranty.WarrantyClaimResponse;
import com.chala.posapp.dto.warranty.WarrantyClaimUpdateRequest;
import com.chala.posapp.dto.warranty.WarrantyResponse;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.Warranty;
import com.chala.posapp.entity.WarrantyClaim;
import com.chala.posapp.entity.WarrantyClaimStatus;
import com.chala.posapp.entity.WarrantyStatus;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.entity.Item;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.WarrantyClaimRepository;
import com.chala.posapp.repository.WarrantyRepository;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarrantyService {

    private final WarrantyRepository warrantyRepository;
    private final WarrantyClaimRepository warrantyClaimRepository;
    private final SecurityUtils securityUtils;
    private final ItemRepository itemRepository;
    private final ReportCacheInvalidator reportCacheInvalidator;

    public Page<WarrantyResponse> list(String search, int page, int size, String branchId) {
        User user = securityUtils.getCurrentUser();
        Long resolvedBranchId = resolveBranchFilter(user, branchId);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, size),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return warrantyRepository.search(search == null ? "" : search.trim(), resolvedBranchId, pageable)
                .map(this::map);
    }

    public Page<WarrantyClaimListResponse> listClaimQueue(
            String search,
            WarrantyClaimStatus status,
            int page,
            int size,
            String branchId
    ) {
        User user = securityUtils.getCurrentUser();
        Long resolvedBranchId = resolveBranchFilter(user, branchId);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, size),
                Sort.by(Sort.Direction.DESC, "receivedAt")
        );
        return warrantyClaimRepository.searchQueue(
                search == null ? "" : search.trim(),
                status,
                resolvedBranchId,
                pageable
        );
    }

    public WarrantyResponse get(Long id) {
        User user = securityUtils.getCurrentUser();
        Warranty warranty = getEntity(id);
        ensureBranchAccess(user, warranty.getBranchId());
        return map(warranty);
    }

    public List<WarrantyClaimResponse> listClaims(Long warrantyId) {
        User user = securityUtils.getCurrentUser();
        Warranty warranty = getEntity(warrantyId);
        ensureBranchAccess(user, warranty.getBranchId());
        return warrantyClaimRepository.findByWarrantyIdOrderByCreatedAtDesc(warrantyId)
                .stream()
                .map(this::mapClaim)
                .toList();
    }

    @Transactional
    public WarrantyClaimResponse createClaim(Long warrantyId, WarrantyClaimRequest request) {
        User user = securityUtils.getCurrentUser();
        Warranty warranty = getEntity(warrantyId);
        ensureBranchAccess(user, warranty.getBranchId());
        ensureClaimable(warranty);

        boolean hasActiveClaim = warrantyClaimRepository.existsByWarrantyIdAndStatusIn(
                warrantyId,
                List.of(WarrantyClaimStatus.OPEN, WarrantyClaimStatus.IN_PROGRESS)
        );
        if (hasActiveClaim) {
            throw new BadRequestException("This warranty already has an active claim");
        }

        WarrantyClaim claim = warrantyClaimRepository.save(WarrantyClaim.builder()
                .claimNo(buildClaimNo(warranty))
                .warrantyId(warranty.getId())
                .branchId(warranty.getBranchId())
                .actionType(request.getActionType())
                .issueDescription(request.getIssueDescription().trim())
                .status(WarrantyClaimStatus.OPEN)
                .receivedAt(LocalDateTime.now())
                .build());
        warranty.setStatus(WarrantyStatus.CLAIMED);
        warrantyRepository.save(warranty);
        reportCacheInvalidator.warrantyChanged();

        return mapClaim(claim);
    }

    @Transactional
    public WarrantyClaimResponse updateClaim(Long warrantyId, Long claimId, WarrantyClaimUpdateRequest request) {
        User user = securityUtils.getCurrentUser();
        Warranty warranty = getEntity(warrantyId);
        ensureBranchAccess(user, warranty.getBranchId());

        WarrantyClaim claim = warrantyClaimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Warranty claim not found"));
        if (!claim.getWarrantyId().equals(warrantyId)) {
            throw new BadRequestException("Claim does not belong to this warranty");
        }

        WarrantyClaimStatus nextStatus = request.getStatus();
        if (claim.getStatus() == WarrantyClaimStatus.COMPLETED
                || claim.getStatus() == WarrantyClaimStatus.REJECTED
                || claim.getStatus() == WarrantyClaimStatus.CANCELED) {
            throw new BadRequestException("Closed warranty claims cannot be changed");
        }
        if (nextStatus == WarrantyClaimStatus.OPEN) {
            throw new BadRequestException("Claim is already open");
        }

        claim.setStatus(nextStatus);
        claim.setResolutionNote(normalizeNullable(request.getResolutionNote()));
        if (nextStatus == WarrantyClaimStatus.COMPLETED
                || nextStatus == WarrantyClaimStatus.REJECTED
                || nextStatus == WarrantyClaimStatus.CANCELED) {
            claim.setCompletedAt(LocalDateTime.now());
            warranty.setStatus(isExpired(warranty) ? WarrantyStatus.EXPIRED : WarrantyStatus.ACTIVE);
        } else {
            warranty.setStatus(WarrantyStatus.CLAIMED);
        }

        warrantyRepository.save(warranty);
        reportCacheInvalidator.warrantyChanged();

        return mapClaim(warrantyClaimRepository.save(claim));
    }

    private WarrantyResponse map(Warranty warranty) {
        String altName = itemRepository.findById(warranty.getItemId())
                .map(Item::getAltName).orElse(null);
        return WarrantyResponse.builder()
                .id(warranty.getId())
                .warrantyNo(warranty.getWarrantyNo())
                .orderId(warranty.getOrderId())
                .orderItemId(warranty.getOrderItemId())
                .branchId(warranty.getBranchId())
                .invoiceNo(warranty.getInvoiceNo())
                .customerId(warranty.getCustomerId())
                .customerName(warranty.getCustomerName())
                .itemId(warranty.getItemId())
                .itemName(warranty.getItemName())
                .altName(altName)
                .barcode(warranty.getBarcode())
                .warrantyLabel(warranty.getWarrantyLabel())
                .periodValue(warranty.getPeriodValue())
                .periodUnit(warranty.getPeriodUnit())
                .startDate(warranty.getStartDate())
                .endDate(warranty.getEndDate())
                .status(resolveStatus(warranty))
                .createdAt(warranty.getCreatedAt())
                .build();
    }

    private WarrantyStatus resolveStatus(Warranty warranty) {
        if ((warranty.getStatus() == WarrantyStatus.ACTIVE || warranty.getStatus() == WarrantyStatus.CLAIMED)
                && isExpired(warranty)) {
            return WarrantyStatus.EXPIRED;
        }
        return warranty.getStatus();
    }

    private Warranty getEntity(Long id) {
        return warrantyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warranty not found"));
    }

    private void ensureClaimable(Warranty warranty) {
        WarrantyStatus status = resolveStatus(warranty);
        if (status == WarrantyStatus.VOID) {
            throw new BadRequestException("Void warranties cannot be claimed");
        }
        if (status == WarrantyStatus.EXPIRED) {
            throw new BadRequestException("Expired warranties cannot be claimed");
        }
    }

    private boolean isExpired(Warranty warranty) {
        return warranty.getEndDate() != null && warranty.getEndDate().isBefore(LocalDate.now());
    }

    private String buildClaimNo(Warranty warranty) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return "CLM-" + warranty.getWarrantyNo() + "-" + suffix;
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private WarrantyClaimResponse mapClaim(WarrantyClaim claim) {
        return WarrantyClaimResponse.builder()
                .id(claim.getId())
                .claimNo(claim.getClaimNo())
                .warrantyId(claim.getWarrantyId())
                .branchId(claim.getBranchId())
                .actionType(claim.getActionType())
                .status(claim.getStatus())
                .issueDescription(claim.getIssueDescription())
                .resolutionNote(claim.getResolutionNote())
                .receivedAt(claim.getReceivedAt())
                .completedAt(claim.getCompletedAt())
                .createdAt(claim.getCreatedAt())
                .build();
    }

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead

    // DUP-05 FIX: securityUtils.requireAssignedBranch() centralised in SecurityUtils

    private void ensureBranchAccess(User user, Long branchId) {
        if (securityUtils.isAdminLike(user)) {
            return;
        }
        if (!securityUtils.requireAssignedBranch(user).equals(branchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
    }

    private Long resolveBranchFilter(User user, String branchId) {
        if (!securityUtils.isAdminLike(user)) {
            return securityUtils.requireAssignedBranch(user);
        }
        if (branchId == null || branchId.isBlank() || "0".equals(branchId)) {
            return null;
        }
        try {
            return Long.parseLong(branchId);
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Invalid branchId");
        }
    }
}
