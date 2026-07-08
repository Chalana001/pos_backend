package com.chala.posapp.service;

import com.chala.posapp.dto.stock.CreateStockAdjustmentRequest;
import com.chala.posapp.dto.stock.StockAdjustmentResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockAdjustment;
import com.chala.posapp.entity.stock.StockAdjustmentDirection;
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
import com.chala.posapp.audit.Audited;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockAdjustmentService {

    private final StockAdjustmentRepository adjustmentRepository;
    private final ItemRepository itemRepository;
    private final SecurityUtils securityUtils;
    private final BranchRepository branchRepository;
    private final StockBatchRepository stockBatchRepository;
    private final UserRepository userRepository;

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead

    // DUP-05 FIX: securityUtils.enforceBranchAccess() centralised in SecurityUtils

    private int computeQtyChange(StockAdjustmentType type, StockAdjustmentDirection direction, int qty) {
        StockAdjustmentDirection effectiveDirection = direction != null ? direction : defaultDirection(type);
        if (effectiveDirection == StockAdjustmentDirection.REMOVE) {
            return -qty;
        }
        return qty;
    }

    private StockAdjustmentDirection defaultDirection(StockAdjustmentType type) {
        if (type == StockAdjustmentType.EXPIRED
                || type == StockAdjustmentType.DAMAGED
                || type == StockAdjustmentType.LOST) {
            return StockAdjustmentDirection.REMOVE;
        }
        return StockAdjustmentDirection.ADD;
    }

    @Audited(entity = "STOCK_ADJUSTMENT", action = "CREATE",
             summaryExpression = "'Branch=' + #request.branchId + ' item=' + #request.itemId + ' qty=' + #request.quantity")
    @Transactional
    public StockAdjustmentResponse create(CreateStockAdjustmentRequest request) {
        User user = securityUtils.getCurrentUser();

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
        if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
            throw new BadRequestException("Stock adjustments are only applicable for stock-tracked grocery items");
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
        int qtyChange = computeQtyChange(request.getType(), request.getDirection(), normalizedQty);

        if (qtyChange < 0) {
            int qtyToRemove = Math.abs(qtyChange);
            if (batch.getQuantity() < qtyToRemove) {
                throw new BadRequestException("Not enough stock in the selected batch to reduce. Available: " + batch.getQuantity());
            }
        }

        batch.setQuantity(batch.getQuantity() + qtyChange);
        stockBatchRepository.save(batch);

        BigDecimal signedDisplayQty = qtyChange < 0 ? request.getQty().negate() : request.getQty();

        StockAdjustment adjustment = StockAdjustment.builder()
                .branchId(request.getBranchId())
                .itemId(request.getItemId())
                .type(request.getType())
                .qtyChange(qtyChange)
                .displayQtyChange(signedDisplayQty.stripTrailingZeros())
                .qtyUnit(QuantityConversionUtil.isMeasuredItem(item.getItemType())
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
        Long allowedBranchId = securityUtils.enforceBranchAccess(branchId);
        List<StockAdjustment> adjustments = adjustmentRepository.findByBranchIdOrderByCreatedAtDesc(allowedBranchId);

        List<Long> itemIds = adjustments.stream().map(StockAdjustment::getItemId).distinct().toList();
        List<Item> items = itemRepository.findAllById(itemIds);

        Map<Long, Item> itemMap = items.stream()
                .collect(Collectors.toMap(Item::getId, item -> item));

        return adjustments.stream()
                .map(adjustment -> map(adjustment, itemMap.get(adjustment.getItemId())))
                .toList();
    }

    public List<StockAdjustmentResponse> historyByItem(Long branchId, Long itemId) {
        Long allowedBranchId = securityUtils.enforceBranchAccess(branchId);
        return adjustmentRepository.findByBranchIdAndItemIdOrderByCreatedAtDesc(allowedBranchId, itemId).stream()
                .map(this::map)
                .toList();
    }

    public Page<StockAdjustmentResponse> historyPage(
            Long branchId,
            String search,
            StockAdjustmentType type,
            Long userId,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        Long allowedBranchId = securityUtils.enforceBranchAccess(branchId);
        Long filterBranchId = allowedBranchId != null && allowedBranchId > 0 ? allowedBranchId : null;
        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();

        Page<StockAdjustment> adjustments = adjustmentRepository.findHistory(
                filterBranchId,
                normalizedSearch,
                type,
                userId,
                fromDateTime,
                toDateTime,
                PageRequest.of(page, size)
        );

        List<Long> itemIds = adjustments.getContent().stream().map(StockAdjustment::getItemId).distinct().toList();
        List<Long> userIds = adjustments.getContent().stream().map(StockAdjustment::getUserId).distinct().toList();
        List<Long> branchIds = adjustments.getContent().stream().map(StockAdjustment::getBranchId).distinct().toList();

        Map<Long, Item> itemMap = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, item -> item));
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        Map<Long, Branch> branchMap = branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, branch -> branch));

        List<StockAdjustmentResponse> content = adjustments.getContent().stream()
                .map(adjustment -> map(
                        adjustment,
                        itemMap.get(adjustment.getItemId()),
                        userMap.get(adjustment.getUserId()),
                        branchMap.get(adjustment.getBranchId())
                ))
                .toList();

        return new PageImpl<>(content, adjustments.getPageable(), adjustments.getTotalElements());
    }

    private StockAdjustmentResponse map(StockAdjustment adjustment, Item item) {
        User actor = userRepository.findById(adjustment.getUserId()).orElse(null);
        Branch branch = branchRepository.findById(adjustment.getBranchId()).orElse(null);
        return map(adjustment, item, actor, branch);
    }

    private StockAdjustmentResponse map(StockAdjustment adjustment, Item item, User actor, Branch branch) {
        return StockAdjustmentResponse.builder()
                .id(adjustment.getId())
                .branchId(adjustment.getBranchId())
                .branchName(branch != null ? branch.getName() : null)
                .itemId(adjustment.getItemId())
                .itemBarcode(item != null ? item.getBarcode() : "UNKNOWN")
                .itemName(item != null ? item.getName() : "Unknown Item")
                .altName(item != null ? item.getAltName() : null)
                .type(adjustment.getType())
                .qtyChange(adjustment.getQtyChange())
                .displayQtyChange(adjustment.getDisplayQtyChange())
                .qtyUnit(adjustment.getQtyUnit())
                .reason(adjustment.getReason())
                .userId(adjustment.getUserId())
                .username(actor != null ? actor.getUsername() : null)
                .createdAt(adjustment.getCreatedAt())
                .build();
    }

    private StockAdjustmentResponse map(StockAdjustment adjustment) {
        Item item = itemRepository.findById(adjustment.getItemId()).orElse(null);
        return map(adjustment, item);
    }
}
