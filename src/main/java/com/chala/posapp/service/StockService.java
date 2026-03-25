package com.chala.posapp.service;

import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.stock.StockAddRequest;
import com.chala.posapp.dto.stock.StockResponse;
import com.chala.posapp.dto.stock.StockResponseWithItems;
import com.chala.posapp.entity.*;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockService {

    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private boolean isAdminLike(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
    }

    private Long enforceBranchAccess(Long branchId) {
        User user = getLoggedUser();

        if (isAdminLike(user)) {
            return branchId;
        }

        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }

        if (!user.getBranchId().equals(branchId)) {
            throw new BadRequestException("Cannot access another branch");
        }

        return branchId;
    }

//    @Transactional
//    public StockResponse addStock(StockAddRequest request) {
//
//        Item item = itemRepository.findById(request.getItemId())
//                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
//
//        Branch branch = branchRepository.findById(request.getBranchId())
//                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
//
//        Supplier supplier = null;
//        if (request.getSupplierId() != null) {
//            supplier = supplierRepository.findById(request.getSupplierId())
//                    .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
//        }
//
//        StockBatch batch = StockBatch.builder()
//                .branch(branch)
//                .item(item)
//                .quantity(request.getQuantity())
//                .originalQuantity(request.getQuantity())
//                .costPrice(request.getCostPrice())
//                .sellingPrice(request.getSellingPrice())
//                .batchCode(request.getBatchCode())
//                .supplier(supplier)
//                .expireDate(request.getExpireDate())
//                .receivedAt(LocalDateTime.now())
//                .build();
//
//        StockBatch savedBatch = stockBatchRepository.save(batch);
//
//        return mapToResponse(savedBatch);
//    }

    public Page<StockResponseWithItems> listBranchStock(Long branchId, String search, int page, int size) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        Long filterBranchId = (allowedBranchId != null && allowedBranchId > 0) ? allowedBranchId : null;
        Pageable pageable = PageRequest.of(page, size);
        return stockBatchRepository.getStockSummary(filterBranchId, search, pageable);
    }


    public List<LowStockResponse> lowStock(Long branchId) {
        return stockBatchRepository.findLowStockItems(enforceBranchAccess(branchId));
    }

    private StockResponse mapToResponse(StockBatch batch) {
        return StockResponse.builder()
                .id(batch.getId())

                .branchId(batch.getBranch().getId())
                .itemId(batch.getItem().getId())

                .batchCode(batch.getBatchCode())
                .quantity(batch.getQuantity())
                .costPrice(batch.getCostPrice())
                .sellingPrice(batch.getSellingPrice())

                .receivedAt(batch.getReceivedAt())
                .expireDate(batch.getExpireDate())
                .build();
    }
}
