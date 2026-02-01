package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final AuthService authService;
    private final StockBatchRepository stockBatchRepository;

    // --- CREATE ---
    public ItemResponse createItem(ItemCreateRequest request) {
        String barcode = request.getBarcode().trim();

        if (itemRepository.existsByBarcode(barcode))
            throw new RuntimeException("Barcode already exists: " + barcode);

        SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                .orElseThrow(() -> new RuntimeException("SubCategory not found with ID: " + request.getSubCategoryId()));

        Item item = Item.builder()
                .barcode(barcode)
                .name(request.getName().trim())
                .subCategory(subCategory)
                .costPrice(request.getCostPrice()) // ✅ Fixed: BigDecimal conversion
                .sellingPrice(request.getSellingPrice()) // ✅ Fixed: BigDecimal conversion
                .reorderLevel(request.getReorderLevel())
                .imageUrl(request.getImageUrl())
                .active(true)
                .build();
        return mapToResponse(itemRepository.save(item));
    }

    // --- BULK CREATE ---
    @Transactional
    public List<ItemResponse> bulkCreate(List<ItemCreateRequest> requestList) {
        List<String> incomingBarcodes = requestList.stream()
                .map(ItemCreateRequest::getBarcode)
                .toList();

        if (!itemRepository.findAllByBarcodeIn(incomingBarcodes).isEmpty()) {
            throw new RuntimeException("Duplicate barcodes found in the system. Bulk upload aborted.");
        }

        List<Item> newItemList = requestList.stream()
                .map(req -> {
                    SubCategory subCat = subCategoryRepository.findById(req.getSubCategoryId())
                            .orElseThrow(() -> new RuntimeException("SubCategory ID " + req.getSubCategoryId() + " not found"));

                    return Item.builder()
                            .name(req.getName())
                            .barcode(req.getBarcode())
                            .subCategory(subCat)
                            .costPrice(req.getCostPrice())
                            .sellingPrice(req.getSellingPrice())
                            .reorderLevel(req.getReorderLevel())
                            .active(true)
                            .build();
                })
                .toList();

        return itemRepository.saveAll(newItemList).stream()
                .map(this::mapToResponse)
                .toList();
    }

    // --- READ / SEARCH ---
    public ItemResponse getItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        return mapToResponse(item);
    }

    public ItemResponse getByBarcode(String barcode, Long branchId) {
        Item item = itemRepository.findByBarcode(barcode.trim())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (branchId == null) {
            return mapToResponse(item);
        } else {
            Integer totalQty = stockBatchRepository.getTotalQuantityByItemAndBranch(branchId, item.getId());
            return mapToResponse(item, totalQty != null ? totalQty.doubleValue() : 0.0);
        }
    }

    public List<ItemResponse> searchByName(String name, Long branchId) {
        // 1. නම අනුව Items search කරනවා
        List<Item> items = itemRepository.findByNameContainingIgnoreCase(name.trim());

        return items.stream().map(item -> {
            ItemResponse response = new ItemResponse();

            // --- A. Basic Fields Mapping ---
            response.setId(item.getId());
            response.setBarcode(item.getBarcode());
            response.setName(item.getName());
            response.setImageUrl(item.getImageUrl());
            response.setReorderLevel(item.getReorderLevel());
            response.setActive(item.isActive());
            response.setCreatedAt(item.getCreatedAt());

            // --- B. Category Mapping (Null Safety included) ---
            if (item.getSubCategory() != null) {
                response.setSubCategoryId(item.getSubCategory().getId());
                response.setSubCategoryName(item.getSubCategory().getName());

                // SubCategory හරහා Parent Category එක ගන්නවා
                if (item.getSubCategory().getCategory() != null) {
                    response.setCategoryId(item.getSubCategory().getCategory().getId());
                    response.setCategoryName(item.getSubCategory().getCategory().getName());
                }
            }

            // --- C. Cost Price (Reference) ---
            response.setCostPrice(item.getCostPrice());

            // --- D. 🔥 BATCH & STOCK LOGIC ---
            List<StockBatchResponse> batchDTOs = new ArrayList<>();
            Double totalAvailableQty = 0.0;
            BigDecimal currentDisplayPrice = item.getSellingPrice(); // Default: Item Master Price

            // Branch ID එකක් එවලා තියෙනවා නම් විතරක් Stock check කරනවා
            if (branchId != null) {
                // 1. DB එකෙන් Batches ටික ගන්නවා (FIFO Order)
                List<StockBatch> activeBatches = stockBatchRepository
                        .findByBranchIdAndItemIdAndQuantityGreaterThanOrderByIdAsc(branchId, item.getId(), 0.0);

                // 2. Entity -> DTO Convert කරනවා
                batchDTOs = activeBatches.stream().map(batch -> new StockBatchResponse(
                        batch.getId(),
                        batch.getSellingPrice(),
                        batch.getQuantity(), // Entity එකේ field එක 'qty' හෝ 'quantity' විය හැක
                        batch.getExpireDate()
                )).collect(Collectors.toList());

                // 3. Java Code එකෙන්ම Total Qty එක එකතු කරනවා
                totalAvailableQty = batchDTOs.stream()
                        .mapToDouble(StockBatchResponse::getQty)
                        .sum();

                // 4. Selling Price Logic:
                // Stock තියෙනවා නම්, විකුණන්න තියෙන පරණම Batch එකේ (FIFO) මිල ගන්නවා.
                if (!batchDTOs.isEmpty()) {
                    currentDisplayPrice = batchDTOs.get(0).getPrice();
                }
            }

            // --- E. Final Setters ---
            response.setBatches(batchDTOs);           // List of Prices (Frontend Modal සඳහා)
            response.setAvailableQty(totalAvailableQty); // Total Stock
            response.setSellingPrice(currentDisplayPrice); // Item Card එකේ පෙන්වන මිල

            return response;

        }).collect(Collectors.toList());
    }

//    public List<ItemResponse> searchByName(String name, Long branchId) {
//        List<Item> items = itemRepository.findByNameContainingIgnoreCase(name.trim());
//
//        return items.stream().map(item -> {
//            Double qty = null;
//            if (branchId != null) {
//                Integer totalQty = stockBatchRepository.getTotalQuantityByItemAndBranch(branchId, item.getId());
//                qty = totalQty != null ? totalQty.doubleValue() : 0.0;
//            }
//            return mapToResponse(item, qty);
//        }).toList();
//    }

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

        if (request.getSubCategoryId() != null) {
            SubCategory subCat = subCategoryRepository.findById(request.getSubCategoryId())
                    .orElseThrow(() -> new RuntimeException("SubCategory not found"));
            item.setSubCategory(subCat);
        }

        if (request.getCostPrice() != null) item.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) item.setSellingPrice(request.getSellingPrice());
        if (request.getReorderLevel() != null) item.setReorderLevel(request.getReorderLevel());
        if (request.getImageUrl() != null) item.setImageUrl(request.getImageUrl());
        if (request.getActive() != null) item.setActive(request.getActive());

        return mapToResponse(itemRepository.save(item));
    }

    // --- REPORTING ---
    public List<ItemWithStockResponse> itemsWithStock(Long branchId) {
        User user = authService.getLoggedUser();
        if (user.getRole() == Role.CASHIER || user.getRole() == Role.MANAGER) {
            branchId = user.getBranchId();
        }

        List<Object[]> raw = (branchId != null)
                ? itemRepository.itemsWithBranchStockRaw(branchId)
                : itemRepository.itemsWithTotalStockRaw();

        return raw.stream().map(r -> ItemWithStockResponse.builder()
                // Safe ID conversion
                .id(r[0] != null ? ((Number) r[0]).longValue() : null)

                // ✅ FIX: Use .toString() instead of (String) cast
                // This handles cases where barcode/names are returned as Numbers
                .barcode(r[1] != null ? r[1].toString() : null)
                .name(r[2] != null ? r[2].toString() : null)
                .categoryName(r[3] != null ? r[3].toString() : null)
                .subCategoryName(r[4] != null ? r[4].toString() : null)

                // ✅ FIX: Null-safe Price conversion
                // If r[5] is null, this would have thrown a NullPointerException before
                .costPrice(r[5] != null ? new BigDecimal(r[5].toString()) : BigDecimal.ZERO)
                .sellingPrice(r[6] != null ? new BigDecimal(r[6].toString()) : BigDecimal.ZERO)

                // Safe Integer conversion
                .reorderLevel(r[7] != null ? ((Number) r[7]).intValue() : 0)

                // Boolean conversion
                .active(Boolean.TRUE.equals(r[8]))

                // Date conversion
                .createdAt(toLocalDateTime(r[9]))

                // Quantity conversion
                .quantity(r.length >= 11 && r[10] != null ? ((Number) r[10]).intValue() : 0)
                .build()).toList();
    }

    // --- MAPPERS ---
    private ItemResponse mapToResponse(Item item) {
        return mapToResponse(item, null);
    }

    private ItemResponse mapToResponse(Item item, Double qty) {
        SubCategory sc = item.getSubCategory();
        Category c = (sc != null) ? sc.getCategory() : null;

        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .name(item.getName())
                .subCategoryId(sc != null ? sc.getId() : null)
                .subCategoryName(sc != null ? sc.getName() : "N/A")
                .categoryId(c != null ? c.getId() : null)
                .categoryName(c != null ? c.getName() : "N/A")
                .costPrice(item.getCostPrice())
                .sellingPrice(item.getSellingPrice())
                .reorderLevel(item.getReorderLevel())
                .imageUrl(item.getImageUrl())
                .active(item.isActive())
                .createdAt(item.getCreatedAt())
                .availableQty(qty)
                .build();
    }

    public void deactivateItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        item.setActive(false);
        itemRepository.save(item);
    }

    private LocalDateTime toLocalDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        return Instant.ofEpochMilli(((Number) v).longValue()).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}