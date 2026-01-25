package com.chala.posapp.service;

import com.chala.posapp.dto.CreateStockAdjustmentRequest;
import com.chala.posapp.dto.StockAdjustmentResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockAdjustmentService {

    private final StockAdjustmentRepository adjustmentRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final StockBatchRepository stockBatchRepository;

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

        // 1. SECURITY CHECKS (Same as before)
        if (user.getRole() == Role.CASHIER)
            throw new RuntimeException("Cashier cannot adjust stock");

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("User branch not assigned");
            if (!user.getBranchId().equals(request.getBranchId()))
                throw new RuntimeException("Managers can adjust only their branch");
        }

        // 2. VALIDATE ITEM
        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (!item.isActive())
            throw new RuntimeException("Item inactive");

        // Branch Validation (Optional but good safety)
        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new RuntimeException("Branch not found"));


        // 3. CALCULATE QUANTITY CHANGE
        // ADD නම් (+), REMOVE නම් (-) අගයක් එනවා
        int qtyChange = computeQtyChange(request.getType(), request.getQty());

        // Adjustment එක කරන්න කලින් බලනවා මොකක්ද කරන්න ඕන කියලා
        if (qtyChange > 0) {
            // --- CASE A: STOCK වැඩි කරනවා (Found Item / Correction) ---
            // අපි මේක අලුත් Batch එකක් විදියට දානවා.
            // Price විදියට ගන්නේ Item Master එකේ තියෙන Current Cost එක.

            StockBatch newBatch = StockBatch.builder()
                    .branch(branch)
                    .item(item)
                    .quantity(qtyChange)          // එකතු වෙන ගාන
                    .originalQuantity(qtyChange)
                    .costPrice(item.getCostPrice())      // Item Master Price
                    .sellingPrice(item.getSellingPrice())
                    .batchCode("ADJ-" + System.currentTimeMillis()) // Adjustment එකක් බව හඳුනාගන්න Code එකක්
                    .receivedAt(LocalDateTime.now())
                    .build();

            stockBatchRepository.save(newBatch);

        } else {
            // --- CASE B: STOCK අඩු කරනවා (Damaged / Lost / Expired) ---
            // මෙතනදී FIFO ක්‍රමයට පරණ Batches වලින් අඩු කරගෙන යන්න ඕන.

            int qtyToRemove = Math.abs(qtyChange); // ඍණ අගය ධන කරගන්නවා (eg: -5 -> 5)

            // 1. මුළු Stock එක ඇතිද බලනවා
            Integer currentTotalStock = stockBatchRepository.getTotalQuantityByItemAndBranch(request.getBranchId(), request.getItemId());
            if (currentTotalStock == null || currentTotalStock < qtyToRemove) {
                throw new RuntimeException("Not enough stock to reduce. Current: " + (currentTotalStock == null ? 0 : currentTotalStock));
            }

            // 2. Available Batches ගන්නවා (Received Date පිළිවෙලට)
            List<StockBatch> batches = stockBatchRepository.findAvailableBatches(request.getBranchId(), request.getItemId());

            for (StockBatch batch : batches) {
                if (qtyToRemove == 0) break; // අඩු කරලා ඉවර නම් Loop එක නවත්තනවා

                int availableInBatch = batch.getQuantity();

                if (availableInBatch >= qtyToRemove) {
                    // මේ Batch එකේ ඇති වෙන්න බඩු තියෙනවා
                    batch.setQuantity(availableInBatch - qtyToRemove);
                    qtyToRemove = 0; // ඔක්කොම අඩු කරා
                } else {
                    // මේ Batch එක මදි, තියෙන ටික ඔක්කොම ගන්නවා
                    batch.setQuantity(0);
                    qtyToRemove -= availableInBatch; // ඉතුරු ටික ඊළඟ Batch එකෙන් අඩු කරන්න තියාගන්නවා
                }
                stockBatchRepository.save(batch); // Update Batch
            }
        }

        // 4. SAVE LOG (Adjustment History)
        StockAdjustment adjustment = StockAdjustment.builder()
                .branchId(request.getBranchId())
                .itemId(request.getItemId())
                .type(request.getType())
                .qtyChange(qtyChange)
                .reason(request.getReason().trim())
                .userId(user.getId())
                .createdAt(LocalDateTime.now())
                .build();

        StockAdjustment saved = adjustmentRepository.save(adjustment);

        // 5. RETURN RESPONSE
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
        List<StockAdjustment> adjustments = adjustmentRepository.findByBranchIdOrderByCreatedAtDesc(branchId);

        // 1. අදාල Items ටික ඔක්කොම එක පාර ගන්නවා (Bulk Fetch)
        List<Long> itemIds = adjustments.stream().map(StockAdjustment::getItemId).distinct().toList();
        List<Item> items = itemRepository.findAllById(itemIds);

        // 2. Map එකක් හදාගන්නවා (ID -> Item) ලේසියෙන් හොයාගන්න
        Map<Long, Item> itemMap = items.stream()
                .collect(Collectors.toMap(Item::getId, item -> item));

        // 3. Loop කරලා Map කරනවා (DB calls යන්නේ නෑ)
        return adjustments.stream()
                .map(a -> map(a, itemMap.get(a.getItemId())))
                .toList();
    }

    public List<StockAdjustmentResponse> historyByItem(Long branchId, Long itemId) {
        return adjustmentRepository.findByBranchIdAndItemIdOrderByCreatedAtDesc(branchId, itemId).stream()
                .map(this::map)
                .toList();
    }

    // 1. Optimized Method (අපි අලුතෙන් හදන එක - Item එක එළියෙන් එවනවා)
// මේක පාවිච්චි කරන්නේ අර Bulk List ගන්න තැන්වල විතරයි.
    private StockAdjustmentResponse map(StockAdjustment a, Item item) {
        return StockAdjustmentResponse.builder()
                .id(a.getId())
                .branchId(a.getBranchId())
                .itemId(a.getItemId())
                .itemBarcode(item != null ? item.getBarcode() : "UNKNOWN")
                .itemName(item != null ? item.getName() : "Unknown Item")
                .type(a.getType())
                .qtyChange(a.getQtyChange())
                .reason(a.getReason())
                .userId(a.getUserId())
                .createdAt(a.getCreatedAt())
                .build();
    }

    // 2. Old Method (පරණ එක එහෙම්මම තියෙනවා - හැබැයි පොඩි වෙනසක් එක්ක)
// මේක පාවිච්චි කරන්නේ තනි Adjustment එකක් map කරන්න ඕන වුනාම.
    private StockAdjustmentResponse map(StockAdjustment a) {
        // මේ මෙතඩ් එක ඇතුලෙම Item එක හොයාගන්නවා
        Item item = itemRepository.findById(a.getItemId()).orElse(null);

        // ඊට පස්සේ අර උඩ තියෙන method එකටම call කරනවා (Reusing logic)
        return map(a, item);
    }
}
