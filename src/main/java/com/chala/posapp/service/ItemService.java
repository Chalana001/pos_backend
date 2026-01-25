package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository; // ✅ New Import
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final StockBatchRepository stockBatchRepository; // ✅ Stock Batches වලින් Qty ගන්න
    private final AuthService authService;

    // --- CREATE ---

    public ItemResponse createItem(ItemCreateRequest request) {
        String barcode = request.getBarcode().trim();

        if (itemRepository.existsByBarcode(barcode))
            throw new RuntimeException("Barcode already exists: " + barcode);

        Item item = Item.builder()
                .barcode(barcode)
                .name(request.getName().trim())
                .category(request.getCategory())
                .costPrice(BigDecimal.valueOf(request.getCostPrice()))
                .sellingPrice(BigDecimal.valueOf(request.getSellingPrice()))
                .reorderLevel(request.getReorderLevel())
                .imageUrl(request.getImageUrl())
                .active(true)
                .build();
        return mapToResponse(itemRepository.save(item));
    }

    @Transactional
    public List<ItemResponse> bulkCreate(List<ItemCreateRequest> requestList) {
        // 1. DUPLICATE CHECK
        List<String> incomingBarcodes = requestList.stream()
                .map(ItemCreateRequest::getBarcode)
                .toList();

        List<Item> existingItems = itemRepository.findAllByBarcodeIn(incomingBarcodes);

        if (!existingItems.isEmpty()) {
            List<String> existingCodes = existingItems.stream().map(Item::getBarcode).toList();
            throw new RuntimeException("Items already exist: " + existingCodes);
        }

        // 2. MAPPING
        List<Item> newItemList = requestList.stream()
                .map(req -> Item.builder()
                        .name(req.getName())
                        .barcode(req.getBarcode())
                        .costPrice(BigDecimal.valueOf(req.getCostPrice()))
                        .sellingPrice(BigDecimal.valueOf(req.getSellingPrice()))
                        .reorderLevel(req.getReorderLevel())
                        .category(req.getCategory())
                        .active(true)
                        .build())
                .toList();

        // 3. BULK SAVE
        List<Item> savedItems = itemRepository.saveAll(newItemList);

        return savedItems.stream()
                .map(this::mapToResponse)
                .toList();
    }

    // --- READ / SEARCH ---

    public ItemResponse getItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        return mapToResponse(item);
    }

    // ✅ Fixed: Branch ID එකක් ආවොත් Stock එක Batch එකෙන් ගන්නවා
    public ItemResponse getByBarcode(String barcode, Long branchId) {
        Item item = itemRepository.findByBarcode(barcode.trim())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (branchId == null) {
            return mapToResponse(item);
        } else {
            // Batch System Logic: Total quantity for this item in this branch
            Integer totalQty = stockBatchRepository.getTotalQuantityByItemAndBranch(branchId, item.getId());
            return mapToResponse(item, totalQty != null ? totalQty.doubleValue() : 0.0);
        }
    }

    // ✅ Fixed: List එකක් එනකොටත් Branch ID තිබුනොත් Stock එක ගන්නවා
    public List<ItemResponse> searchByName(String name, Long branchId) {
        List<Item> items = itemRepository.findByNameContainingIgnoreCase(name.trim());

        if (branchId == null) {
            return items.stream().map(this::mapToResponse).toList();
        } else {
            return items.stream().map(item -> {
                Integer totalQty = stockBatchRepository.getTotalQuantityByItemAndBranch(branchId, item.getId());
                return mapToResponse(item, totalQty != null ? totalQty.doubleValue() : 0.0);
            }).toList();
        }
    }

    public List<ItemResponse> listAll(Boolean activeOnly) {
        return itemRepository.findAll().stream()
                .filter(i -> activeOnly == null || !activeOnly || i.isActive())
                .map(this::mapToResponse)
                .toList();
    }

    // --- UPDATE ---

    @Transactional
    public ItemResponse updateItem(Long id, ItemUpdateRequest request) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (request.getName() != null && !request.getName().isBlank()) item.setName(request.getName().trim());
        if (request.getCategory() != null) item.setCategory(request.getCategory().trim());
        if (request.getCostPrice() != null) item.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) item.setSellingPrice(request.getSellingPrice());
        if (request.getReorderLevel() != null) item.setReorderLevel(request.getReorderLevel());
        if (request.getImageUrl() != null) item.setImageUrl(request.getImageUrl());
        if (request.getActive() != null) item.setActive(request.getActive());

        Item savedItem = itemRepository.save(item);
        return mapToResponse(savedItem);
    }

    public void deactivateItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        item.setActive(false);
        itemRepository.save(item);
    }

    // --- REPORTING (Items With Stock) ---

    public List<ItemWithStockResponse> itemsWithStock(Long branchId) {
        User user = authService.getLoggedUser();

        // Security Checks
        if (user.getRole() == Role.CASHIER) {
            if (user.getBranchId() == null) throw new RuntimeException("Cashier branch not assigned");
            branchId = user.getBranchId();
        }
        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null) throw new RuntimeException("Manager branch not assigned");
            branchId = user.getBranchId();
        }

        List<Object[]> raw;
        if (branchId != null) {
            // ⚠️ Repository Query එක අනිවාර්යයෙන් වෙනස් කරන්න ඕන Batch Table එකට (SUM)
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

    // --- MAPPERS ---

    // 1. Stock නැතුව යවන එක
    private ItemResponse mapToResponse(Item item) {
        return mapToResponse(item, null);
    }

    // 2. Stock එක්ක යවන එක (Overloaded Method) ✅
    private ItemResponse mapToResponse(Item item, Double qty) {
        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .name(item.getName())
                .category(item.getCategory())
                .costPrice(item.getCostPrice() != null ? item.getCostPrice().doubleValue() : 0.0)
                .sellingPrice(item.getSellingPrice() != null ? item.getSellingPrice().doubleValue() : 0.0)
                .reorderLevel(item.getReorderLevel())
                .imageUrl(item.getImageUrl())
                .active(item.isActive())
                .createdAt(item.getCreatedAt())
                .availableQty(qty) // මෙතනට Qty පාස් කරනවා
                .build();
    }

    // --- UTIL ---
    private LocalDateTime toLocalDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (v instanceof Date d) return Instant.ofEpochMilli(d.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime();
        if (v instanceof Number n) {
            long epoch = n.longValue();
            if (epoch < 100000000000L) {
                return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime();
            }
            return Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        throw new IllegalArgumentException("Unsupported created_at type: " + v.getClass());
    }
}