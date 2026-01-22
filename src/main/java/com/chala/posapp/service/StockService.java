package com.chala.posapp.service;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.StockResponse;
import com.chala.posapp.dto.StockResponseWithItems;
import com.chala.posapp.dto.StockUpsertRequest;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.Stock;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;
    private final AuthService authService;

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

    public List<StockResponseWithItems> listBranchStock(Long branchId) {

        if (branchId == null) branchId = 0L;

        User user = authService.getLoggedUser();

        // cashier must use own branch
        if (user.getRole() == Role.CASHIER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Cashier branch not assigned");
            branchId = user.getBranchId();
        }

        List<Stock> stockList = (branchId == 0)
                ? stockRepository.findAll()
                : stockRepository.findByBranchId(branchId);

        if (stockList.isEmpty()) return List.of();

        List<Long> itemIds = stockList.stream()
                .map(Stock::getItemId)
                .distinct()
                .toList();

        Map<Long, Item> itemMap = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));

        List<StockResponseWithItems> response = new ArrayList<>();

        for (Stock stock : stockList) {
            Item item = itemMap.get(stock.getItemId());
            if (item == null) continue;

            response.add(new StockResponseWithItems(
                    stock.getId(),
                    stock.getItemId(),
                    item.getBarcode(),
                    item.getName(),
                    item.getCostPrice(),
                    item.getSellingPrice(),
                    stock.getQuantity(),
                    stock.getUpdatedAt()
            ));
        }

        return response;
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
