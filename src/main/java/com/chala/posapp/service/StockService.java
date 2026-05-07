package com.chala.posapp.service;

import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.stock.StockAddRequest;
import com.chala.posapp.dto.stock.StockItemBatchDetailsResponse;
import com.chala.posapp.dto.stock.StockItemDetailsResponse;
import com.chala.posapp.dto.stock.StockPurchaseHistoryResponse;
import com.chala.posapp.dto.stock.StockResponse;
import com.chala.posapp.dto.stock.StockResponseWithItems;
import com.chala.posapp.dto.stock.StockValueResponse;
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
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chala.posapp.util.QuantityConversionUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockService {

    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final GrnItemRepository grnItemRepository;

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

    public Page<StockResponseWithItems> listBranchStock(
            Long branchId,
            String search,
            String stockStatus,
            Long categoryId,
            Long subCategoryId,
            int page,
            int size
    ) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        Long filterBranchId = (allowedBranchId != null && allowedBranchId > 0) ? allowedBranchId : null;
        Pageable pageable = PageRequest.of(page, size);
        String normalizedStatus = stockStatus == null || stockStatus.isBlank()
                ? "ALL"
                : stockStatus.trim().toUpperCase();
        return stockBatchRepository.getStockSummary(filterBranchId, search, normalizedStatus, categoryId, subCategoryId, pageable)
                .map(row -> {
                    ItemType type = row.getItemType();

                    row.setDisplayQuantity(QuantityConversionUtil.toDisplayQuantity(
                            type,
                            row.getDefaultUnit(),
                            row.getTotalQuantity() != null ? row.getTotalQuantity().intValue() : 0
                    ));
                    return row;
                });
    }

    public List<LowStockResponse> lowStock(Long branchId) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        Long filterBranchId = allowedBranchId != null && allowedBranchId > 0 ? allowedBranchId : null;
        return stockBatchRepository.findLowStockItems(filterBranchId);
    }

    public StockValueResponse getStockValue(
            Long branchId,
            String search,
            String stockStatus,
            Long categoryId,
            Long subCategoryId
    ) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        Long filterBranchId = allowedBranchId != null && allowedBranchId > 0 ? allowedBranchId : null;
        String normalizedStatus = stockStatus == null || stockStatus.isBlank()
                ? "ALL"
                : stockStatus.trim().toUpperCase();
        BigDecimal stockValue = stockBatchRepository.getStockValue(
                filterBranchId,
                search,
                normalizedStatus,
                categoryId,
                subCategoryId
        );
        return new StockValueResponse(stockValue != null ? stockValue : BigDecimal.ZERO);
    }

    public StockItemDetailsResponse getItemDetails(Long branchId, Long itemId) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        Long filterBranchId = allowedBranchId != null && allowedBranchId > 0 ? allowedBranchId : null;

        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        List<StockBatch> activeBatches = stockBatchRepository.findAvailableBatchesForScope(filterBranchId, itemId);
        if (activeBatches.isEmpty() && (filterBranchId != null || stockBatchRepository.getTotalQuantityByItemAndScope(null, itemId) == 0)) {
            throw new ResourceNotFoundException("No stock found for item");
        }

        int totalQuantity = stockBatchRepository.getTotalQuantityByItemAndScope(filterBranchId, itemId);
        return StockItemDetailsResponse.builder()
                .itemId(item.getId())
                .barcode(item.getBarcode())
                .itemName(item.getName())
                .itemType(item.getItemType())
                .defaultUnit(item.getDefaultUnit())
                .reorderLevel(item.getReorderLevel())
                .categoryId(item.getSubCategory() != null && item.getSubCategory().getCategory() != null ? item.getSubCategory().getCategory().getId() : null)
                .categoryName(item.getSubCategory() != null && item.getSubCategory().getCategory() != null ? item.getSubCategory().getCategory().getName() : null)
                .subCategoryId(item.getSubCategory() != null ? item.getSubCategory().getId() : null)
                .subCategoryName(item.getSubCategory() != null ? item.getSubCategory().getName() : null)
                .totalQuantity(totalQuantity)
                .displayQuantity(QuantityConversionUtil.toDisplayQuantity(item.getItemType(), item.getDefaultUnit(), totalQuantity))
                .activeBatches(activeBatches.stream().map(this::mapBatchDetails).toList())
                .build();
    }

    public Page<StockPurchaseHistoryResponse> getItemPurchaseHistory(
            Long branchId,
            Long itemId,
            String search,
            Long supplierId,
            PurchaseStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        Long filterBranchId = allowedBranchId != null && allowedBranchId > 0 ? allowedBranchId : null;
        itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        Pageable pageable = PageRequest.of(page, size);
        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();

        return grnItemRepository.findPurchaseHistoryByItem(
                filterBranchId,
                itemId,
                normalizedSearch,
                supplierId,
                status,
                fromDateTime,
                toDateTime,
                pageable
        );
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

    private StockItemBatchDetailsResponse mapBatchDetails(StockBatch batch) {
        return StockItemBatchDetailsResponse.builder()
                .batchId(batch.getId())
                .batchCode(batch.getBatchCode())
                .branchId(batch.getBranch().getId())
                .branchName(batch.getBranch().getName())
                .costPrice(batch.getCostPrice())
                .sellingPrice(batch.getSellingPrice())
                .qty(batch.getQuantity())
                .displayQty(QuantityConversionUtil.toDisplayQuantity(batch.getItem().getItemType(), batch.getItem().getDefaultUnit(), batch.getQuantity()))
                .displayUnit(batch.getItem().getItemType() == ItemType.WEIGHT ? MeasurementUnit.KG : batch.getItem().getDefaultUnit())
                .receivedAt(batch.getReceivedAt())
                .expiry(batch.getExpireDate())
                .build();
    }
}
