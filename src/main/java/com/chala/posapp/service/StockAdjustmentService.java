package com.chala.posapp.service;

import com.chala.posapp.dto.CreateStockAdjustmentRequest;
import com.chala.posapp.dto.StockAdjustmentResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockAdjustmentService {

    private final StockAdjustmentRepository adjustmentRepository;
    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private int computeQtyChange(StockAdjustmentType type, int qty) {
        // remove types => negative
        if (type == StockAdjustmentType.EXPIRED ||
                type == StockAdjustmentType.DAMAGED ||
                type == StockAdjustmentType.LOST) {
            return -qty;
        }
        // add types => positive
        if (type == StockAdjustmentType.FOUND) return qty;

        // MANUAL => assume user uses positive qty but we treat as add (optional)
        // if you want remove manual, UI can send type LOST/DAMAGED etc
        return qty;
    }

    @Transactional
    public StockAdjustmentResponse create(CreateStockAdjustmentRequest request) {

        User user = getLoggedUser();

// cashier blocked
        if (user.getRole() == Role.CASHIER)
            throw new RuntimeException("Cashier cannot adjust stock");

// ✅ If NOT ADMIN, must have branch and can only adjust own branch
        if (user.getRole() == Role.MANAGER) {

            if (user.getBranchId() == null)
                throw new RuntimeException("User branch not assigned");

            if (!user.getBranchId().equals(request.getBranchId()))
                throw new RuntimeException("Managers can adjust only their branch");
        }


        // Validate item
        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (!item.isActive())
            throw new RuntimeException("Item inactive");

        int qtyChange = computeQtyChange(request.getType(), request.getQty());

        // Get stock record (create if missing)
        Stock stock = stockRepository.findByBranchIdAndItemId(request.getBranchId(), request.getItemId())
                .orElse(Stock.builder()
                        .branchId(request.getBranchId())
                        .itemId(request.getItemId())
                        .quantity(0)
                        .build());

        int newQty = stock.getQuantity() + qtyChange;

        // block negative stock
        if (newQty < 0)
            throw new RuntimeException("Cannot reduce stock below 0. Current: " + stock.getQuantity());

        stock.setQuantity(newQty);
        stockRepository.save(stock);

        // Save adjustment log
        StockAdjustment adjustment = StockAdjustment.builder()
                .branchId(request.getBranchId())
                .itemId(request.getItemId())
                .type(request.getType())
                .qtyChange(qtyChange)
                .reason(request.getReason().trim())
                .userId(user.getId())
                .build();

        StockAdjustment saved = adjustmentRepository.save(adjustment);

        return StockAdjustmentResponse.builder()
                .id(saved.getId())
                .branchId(saved.getBranchId())
                .itemId(saved.getItemId())
                .itemBarcode(item.getBarcode())
                .itemName(item.getName())
                .type(saved.getType())
                .qtyChange(saved.getQtyChange())
                .reason(saved.getReason())
                .userId(saved.getUserId())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    public List<StockAdjustmentResponse> historyByBranch(Long branchId) {
        return adjustmentRepository.findByBranchIdOrderByCreatedAtDesc(branchId).stream()
                .map(this::map)
                .toList();
    }

    public List<StockAdjustmentResponse> historyByItem(Long branchId, Long itemId) {
        return adjustmentRepository.findByBranchIdAndItemIdOrderByCreatedAtDesc(branchId, itemId).stream()
                .map(this::map)
                .toList();
    }

    private StockAdjustmentResponse map(StockAdjustment a) {
        Item item = itemRepository.findById(a.getItemId()).orElse(null);

        return StockAdjustmentResponse.builder()
                .id(a.getId())
                .branchId(a.getBranchId())
                .itemId(a.getItemId())
                .itemBarcode(item != null ? item.getBarcode() : null)
                .itemName(item != null ? item.getName() : null)
                .type(a.getType())
                .qtyChange(a.getQtyChange())
                .reason(a.getReason())
                .userId(a.getUserId())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
