package com.chala.posapp.service;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.StockAddRequest;
import com.chala.posapp.dto.StockResponse;
import com.chala.posapp.dto.StockResponseWithItems;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.ResourceNotFoundException;
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

        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        Supplier supplier = null;
        if (request.getSupplierId() != null) {
            supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
        }

        StockBatch batch = StockBatch.builder()
                .branch(branch)
                .item(item)
                .quantity(request.getQuantity())
                .originalQuantity(request.getQuantity())
                .costPrice(request.getCostPrice())
                .sellingPrice(request.getSellingPrice())
                .batchCode(request.getBatchCode())
                .supplier(supplier)
                .expireDate(request.getExpireDate())
                .receivedAt(LocalDateTime.now())
                .build();

        StockBatch savedBatch = stockBatchRepository.save(batch);

        return mapToResponse(savedBatch);
    }

    public List<StockResponseWithItems> listBranchStock(Long branchId) {

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
