package com.chala.posapp.service;

import com.chala.posapp.dto.stock.CreateStockAdjustmentRequest;
import com.chala.posapp.dto.stock.StockAdjustmentResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockAdjustment;
import com.chala.posapp.entity.stock.StockAdjustmentType;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockAdjustmentRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockAdjustmentService {

    private final StockAdjustmentRepository adjustmentRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final StockBatchRepository stockBatchRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private int computeQtyChange(StockAdjustmentType type, int qty) {
        if (type == StockAdjustmentType.EXPIRED
                || type == StockAdjustmentType.DAMAGED
                || type == StockAdjustmentType.LOST) {
            return -qty;
        }
        return qty;
    }

    @Transactional
    public StockAdjustmentResponse create(CreateStockAdjustmentRequest request) {
        User user = getLoggedUser();

        if (user.getRole() == Role.CASHIER) {
            throw new BadRequestException("Cashier cannot adjust stock");
        }

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null) {
                throw new NotAssignedException("User branch not assigned");
            }
            if (!user.getBranchId().equals(request.getBranchId())) {
                throw new BadRequestException("Managers can adjust only their branch");
            }
        }

        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (!item.isActive()) {
            throw new BadRequestException("Item inactive");
        }

        // ✅ අලුතින් දැමූ කොටස: SERVICE අයිටම් වලට Stock අදාළ නොවන නිසා එය Block කිරීම
        if (item.getItemType() == ItemType.SERVICE) {
            throw new BadRequestException("Stock adjustments are not applicable for SERVICE items");
        }

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        if (request.getBatchId() == null) {
            throw new BadRequestException("Batch ID is required");
        }

        StockBatch batch = stockBatchRepository.findById(request.getBatchId())
                .orElseThrow(() -> new ResourceNotFoundException("Stock batch not found"));

        if (!batch.getBranch().getId().equals(branch.getId())) {
            throw new BadRequestException("Selected batch does not belong to this branch");
        }
        if (!batch.getItem().getId().equals(request.getItemId())) {
            throw new BadRequestException("Selected batch does not belong to this item");
        }

        int normalizedQty = QuantityConversionUtil.normalizeQuantity(item, request.getQty(), request.getQtyUnit());
        int qtyChange = computeQtyChange(request.getType(), normalizedQty);

        if (qtyChange < 0) {
            int qtyToRemove = Math.abs(qtyChange);
            if (batch.getQuantity() < qtyToRemove) {
                throw new BadRequestException("Not enough stock in the selected batch to reduce. Available: " + batch.getQuantity());
            }
        }

        batch.setQuantity(batch.getQuantity() + qtyChange);
        stockBatchRepository.save(batch);

        BigDecimal signedDisplayQty = request.getType() == StockAdjustmentType.EXPIRED
                || request.getType() == StockAdjustmentType.DAMAGED
                || request.getType() == StockAdjustmentType.LOST
                ? request.getQty().negate()
                : request.getQty();

        StockAdjustment adjustment = StockAdjustment.builder()
                .branchId(request.getBranchId())
                .itemId(request.getItemId())
                .type(request.getType())
                .qtyChange(qtyChange)
                .displayQtyChange(signedDisplayQty.stripTrailingZeros())
                .qtyUnit(item.getItemType() == ItemType.WEIGHT
                        ? (request.getQtyUnit() == null ? item.getDefaultUnit() : request.getQtyUnit())
                        : item.getDefaultUnit())
                .reason(request.getReason().trim())
                .userId(user.getId())
                .createdAt(LocalDateTime.now())
                .build();

        StockAdjustment saved = adjustmentRepository.save(adjustment);
        return map(saved, item);
    }

    public List<StockAdjustmentResponse> historyByBranch(Long branchId) {
        List<StockAdjustment> adjustments = adjustmentRepository.findByBranchIdOrderByCreatedAtDesc(branchId);

        List<Long> itemIds = adjustments.stream().map(StockAdjustment::getItemId).distinct().toList();
        List<Item> items = itemRepository.findAllById(itemIds);

        Map<Long, Item> itemMap = items.stream()
                .collect(Collectors.toMap(Item::getId, item -> item));

        return adjustments.stream()
                .map(adjustment -> map(adjustment, itemMap.get(adjustment.getItemId())))
                .toList();
    }

    public List<StockAdjustmentResponse> historyByItem(Long branchId, Long itemId) {
        return adjustmentRepository.findByBranchIdAndItemIdOrderByCreatedAtDesc(branchId, itemId).stream()
                .map(this::map)
                .toList();
    }

    private StockAdjustmentResponse map(StockAdjustment adjustment, Item item) {
        return StockAdjustmentResponse.builder()
                .id(adjustment.getId())
                .branchId(adjustment.getBranchId())
                .itemId(adjustment.getItemId())
                .itemBarcode(item != null ? item.getBarcode() : "UNKNOWN")
                .itemName(item != null ? item.getName() : "Unknown Item")
                .type(adjustment.getType())
                .qtyChange(adjustment.getQtyChange())
                .displayQtyChange(adjustment.getDisplayQtyChange())
                .qtyUnit(adjustment.getQtyUnit())
                .reason(adjustment.getReason())
                .userId(adjustment.getUserId())
                .createdAt(adjustment.getCreatedAt())
                .build();
    }

    private StockAdjustmentResponse map(StockAdjustment adjustment) {
        Item item = itemRepository.findById(adjustment.getItemId()).orElse(null);
        return map(adjustment, item);
    }
}
