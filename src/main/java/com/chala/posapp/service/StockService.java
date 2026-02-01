package com.chala.posapp.service;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.StockAddRequest;
import com.chala.posapp.dto.StockResponse;
import com.chala.posapp.dto.StockResponseWithItems;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public StockResponse addStock(StockAddRequest request) {

        // 1. Validate Item
        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        // 2. Validate Branch
        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new RuntimeException("Branch not found"));

        // 3. Validate Supplier (Optional)
        Supplier supplier = null;
        if (request.getSupplierId() != null) {
            supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new RuntimeException("Supplier not found"));
        }

        // 4. Create New Batch (අලුත් බැච් එකක් හදනවා)
        StockBatch batch = StockBatch.builder()
                .branch(branch)
                .item(item)
                .quantity(request.getQuantity())     // අලුතෙන් ආපු ප්‍රමාණය
                .originalQuantity(request.getQuantity()) // Reference එකට මුල් ගාන තියාගන්නවා
                .costPrice(request.getCostPrice())   // අද ආපු Cost එක
                .sellingPrice(request.getSellingPrice()) // අද ආපු Selling Price එක
                .batchCode(request.getBatchCode())   // GRN Code එක
                .supplier(supplier)
                .expireDate(request.getExpireDate())
                .receivedAt(LocalDateTime.now())     // දැන් ආවේ
                .build();

        // 5. Save (Database එකට අලුත් Row එකක් වැටෙනවා)
        StockBatch savedBatch = stockBatchRepository.save(batch);

        return mapToResponse(savedBatch);
    }

    public List<StockResponseWithItems> listBranchStock(Long branchId) {
        // Branch ID එක 0 හෝ 0 ට අඩු නම් අපි ඒක NULL කරගන්නවා.
        // මොකද Repository එක බලාපොරොත්තු වෙන්නේ NULL අගයක් All Branches පෙන්නන්න.
        Long filterBranchId = (branchId != null && branchId > 0) ? branchId : null;
        return stockBatchRepository.getStockSummary(filterBranchId);
    }

//    public StockResponse getStock(Long branchId, Long itemId) {
//        Stock stock = stockRepository.findByBranchIdAndItemId(branchId, itemId)
//                .orElseThrow(() -> new RuntimeException("Stock record not found"));
//        return map(stock);
//    }

    public List<LowStockResponse> lowStock(Long branchId) {
        return stockBatchRepository.findLowStockItems(branchId);
    }

    private StockResponse mapToResponse(StockBatch batch) {
        return StockResponse.builder()
                .id(batch.getId())

                // දැන් ID ගන්නේ Relationship Object එක ඇතුලෙන්
                .branchId(batch.getBranch().getId())
                .itemId(batch.getItem().getId())

                // අලුත් Fields
                .batchCode(batch.getBatchCode())
                .quantity(batch.getQuantity())
                .costPrice(batch.getCostPrice())
                .sellingPrice(batch.getSellingPrice())

                // Dates
                .receivedAt(batch.getReceivedAt())
                .expireDate(batch.getExpireDate())
                .build();
    }
}
