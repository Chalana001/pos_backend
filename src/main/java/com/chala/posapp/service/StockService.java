package com.chala.posapp.service;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.StockResponse;
import com.chala.posapp.dto.StockUpsertRequest;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Stock;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;

    @Transactional
    public StockResponse upsertStock(StockUpsertRequest request) {
        // ensure item exists
        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        Stock stock = stockRepository.findByBranchIdAndItemId(request.getBranchId(), request.getItemId())
                .orElse(Stock.builder()
                        .branchId(request.getBranchId())
                        .itemId(item.getId())
                        .quantity(0)
                        .build());

        stock.setQuantity(request.getQuantity());
        Stock saved = stockRepository.save(stock);

        return map(saved);
    }

    public List<StockResponse> listBranchStock(Long branchId) {
        return stockRepository.findByBranchId(branchId)
                .stream().map(this::map).toList();
    }

    public StockResponse getStock(Long branchId, Long itemId) {
        Stock stock = stockRepository.findByBranchIdAndItemId(branchId, itemId)
                .orElseThrow(() -> new RuntimeException("Stock record not found"));
        return map(stock);
    }

    public List<LowStockResponse> lowStock(Long branchId) {
        return stockRepository.findLowStock(branchId)
                .stream()
                .map(v -> LowStockResponse.builder()
                        .itemId(v.getItemId())
                        .barcode(v.getBarcode())
                        .itemName(v.getItemName())
                        .stockQty(v.getStockQty())
                        .reorderLevel(v.getReorderLevel())
                        .build())
                .toList();
    }

    private StockResponse map(Stock stock) {
        return StockResponse.builder()
                .id(stock.getId())
                .branchId(stock.getBranchId())
                .itemId(stock.getItemId())
                .quantity(stock.getQuantity())
                .updatedAt(stock.getUpdatedAt())
                .build();
    }
}
