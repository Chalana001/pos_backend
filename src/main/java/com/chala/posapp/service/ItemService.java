package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.Stock;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final AuthService authService;

    public ItemResponse createItem(ItemCreateRequest request) {
        String barcode = request.getBarcode().trim();

        if (itemRepository.existsByBarcode(barcode))
            throw new RuntimeException("Barcode already exists: " + barcode);

        Item item = Item.builder()
                .barcode(barcode)
                .name(request.getName().trim())
                .category(request.getCategory())
                .costPrice(request.getCostPrice())
                .sellingPrice(request.getSellingPrice())
                .reorderLevel(request.getReorderLevel())
                .imageUrl(request.getImageUrl())
                .active(true)
                .build();

        return map(itemRepository.save(item));
    }

    @Transactional
    public @Nullable ItemResponse createWithStocks(@Valid ItemCreateWithStocksRequest request) {

        String barcode = request.getItemCreateRequest().getBarcode().trim();

        if (itemRepository.existsByBarcode(barcode)) {
            throw new RuntimeException("Barcode already exists: " + barcode);
        }

        // ✅ 1) create item
        ItemResponse item = createItem(request.getItemCreateRequest());

        // ✅ 2) create/update stocks per branch
        for (StockLineRequest s : request.getStocks()) {

            Stock stock = stockRepository
                    .findByBranchIdAndItemId(s.getBranchId(), item.getId())
                    .orElseGet(() -> Stock.builder()
                            .branchId(s.getBranchId())
                            .itemId(item.getId())
                            .quantity(0)
                            .build());

            stock.setQuantity(Math.max(0, s.getQuantity())); // ✅ avoid negative qty
            stockRepository.save(stock);
        }
        return item;
    }

    public ItemResponse getItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        return map(item);
    }
    public ItemResponse getByBarcode(String barcode, Long branchId) {
        Item item = itemRepository.findByBarcode(barcode.trim())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (branchId == null) return map(item);
        return map(item, branchId);
    }

    public List<ItemResponse> searchByName(String name, Long branchId) {
        var items = itemRepository.findByNameContainingIgnoreCase(name.trim());

        if (branchId == null) return items.stream().map(this::map).toList();
        return items.stream().map(i -> map(i, branchId)).toList();
    }


//    public ItemResponse getByBarcode(String barcode) {
//        Item item = itemRepository.findByBarcode(barcode.trim())
//                .orElseThrow(() -> new RuntimeException("Item not found"));
//        return map(item);
//    }

    public List<ItemResponse> listAll(Boolean activeOnly) {
        return itemRepository.findAll().stream()
                .filter(i -> activeOnly == null || !activeOnly || i.isActive())
                .map(this::map)
                .toList();
    }

//    public List<ItemResponse> searchByName(String name) {
//        return itemRepository.findByNameContainingIgnoreCase(name.trim())
//                .stream().map(this::map).toList();
//    }

    @Transactional
    public ItemResponse updateItem(Long id, ItemUpdateRequest request) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (request.getName() != null) item.setName(request.getName().trim());
        if (request.getCategory() != null) item.setCategory(request.getCategory());
        if (request.getCostPrice() != null) item.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) item.setSellingPrice(request.getSellingPrice());
        if (request.getReorderLevel() != null) item.setReorderLevel(request.getReorderLevel());
        if (request.getImageUrl() != null) item.setImageUrl(request.getImageUrl());
        if (request.getActive() != null) item.setActive(request.getActive());

        return map(item);
    }

    public void deactivateItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        item.setActive(false);
        itemRepository.save(item);
    }

    private ItemResponse map(Item item) {
        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .name(item.getName())
                .category(item.getCategory())
                .costPrice(item.getCostPrice())
                .sellingPrice(item.getSellingPrice())
                .reorderLevel(item.getReorderLevel())
                .imageUrl(item.getImageUrl())
                .active(item.isActive())
                .createdAt(item.getCreatedAt())
                .availableQty(0.0) // or 0
                .build();
    }

    private ItemResponse map(Item item, Long branchId) {
        Double qty = stockRepository.findByBranchIdAndItemId(branchId, item.getId())
                .map(stock -> (double) stock.getQuantity())
                .orElse(0.0);

        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .name(item.getName())
                .category(item.getCategory())
                .costPrice(item.getCostPrice())
                .sellingPrice(item.getSellingPrice())
                .reorderLevel(item.getReorderLevel())
                .imageUrl(item.getImageUrl())
                .active(item.isActive())
                .createdAt(item.getCreatedAt())
                .availableQty(qty)
                .build();
    }

    public List<ItemWithStockResponse> itemsWithStock(Long branchId) {

        User user = authService.getLoggedUser();

        // cashier must use own branch
        if (user.getRole() == Role.CASHIER) {
            if (user.getBranchId() == null) throw new RuntimeException("Cashier branch not assigned");
            branchId = user.getBranchId();
        }

        // manager default own branch if not provided
        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null) throw new RuntimeException("Manager branch not assigned");
            branchId = user.getBranchId();
        }

        // admin logic:
        // - if branchId provided => branch stock
        // - if no branchId => total stock
        List<Object[]> raw;
        if (branchId != null) {
            raw = itemRepository.itemsWithBranchStockRaw(branchId);
        } else {
            if (user.getRole() != Role.ADMIN)
                throw new RuntimeException("Only admin can view all branches total stock");
            raw = itemRepository.itemsWithTotalStockRaw();
        }

        return raw.stream().map(obj -> {
            Object[] r = (Object[]) obj;

            return ItemWithStockResponse.builder()
                    .id(((Number) r[0]).longValue())
                    .barcode((String) r[1])
                    .name((String) r[2])
                    .category((String) r[3])
                    .costPrice(((Number) r[4]).doubleValue())
                    .sellingPrice(((Number) r[5]).doubleValue())
                    .reorderLevel(((Number) r[6]).intValue())
                    .active(Boolean.TRUE.equals(r[7]))
                    .createdAt(toLocalDateTime(r[8]))
                    .quantity(r.length >= 10 && r[9] != null ? ((Number) r[9]).intValue() : 0)
                    .build();
        }).toList();
    }


    private LocalDateTime toLocalDateTime(Object v) {
        if (v == null) return null;

        if (v instanceof LocalDateTime ldt) return ldt;

        if (v instanceof Timestamp ts) return ts.toLocalDateTime();

        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();

        if (v instanceof Date d) return Instant.ofEpochMilli(d.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        // ✅ Some DB drivers return numeric epoch time
        if (v instanceof Number n) {
            long epoch = n.longValue();

            // epoch seconds vs millis heuristic
            if (epoch < 100000000000L) { // < ~1973 in ms, so likely seconds
                return Instant.ofEpochSecond(epoch)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime();
            }
            return Instant.ofEpochMilli(epoch)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        }

        throw new IllegalArgumentException("Unsupported created_at type: " + v.getClass());
    }

}
