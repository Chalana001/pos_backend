package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ItemUpdateRequest;
import com.chala.posapp.dto.item.ItemWithStockResponse;
import com.chala.posapp.dto.stock.StockBatchResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final AuthService authService;
    private final StockBatchRepository stockBatchRepository;

    public ItemResponse createItem(ItemCreateRequest request) {

        String barcode = request.getBarcode() != null ? request.getBarcode().trim() : "";

        if (barcode.isEmpty()) {
            barcode = generateFiveDigitBarcode();
        } else {
            if (itemRepository.existsByBarcode(barcode)) {
                throw new AlreadyExistsException("Item with barcode '" + barcode + "' already exists!");
            }
        }

        SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                .orElseThrow(() -> new RuntimeException("SubCategory not found with ID: " + request.getSubCategoryId()));

        Item item = Item.builder()
                .barcode(barcode)
                .name(request.getName().trim())
                .subCategory(subCategory)
                .costPrice(request.getCostPrice())
                .sellingPrice(request.getSellingPrice())
                .reorderLevel(request.getReorderLevel())
                .imageUrl(request.getImageUrl())
                .active(true)
                .build();
        return mapToResponse(itemRepository.save(item));
    }

    @Transactional
    public List<ItemResponse> bulkCreate(List<ItemCreateRequest> requestList) {

        requestList.forEach(req -> {
            if (req.getBarcode() == null || req.getBarcode().trim().isEmpty()) {
                req.setBarcode(generateFiveDigitBarcode());
            } else {
                req.setBarcode(req.getBarcode().trim());
            }
        });

        List<String> incomingBarcodes = requestList.stream()
                .map(ItemCreateRequest::getBarcode)
                .toList();

        if (!itemRepository.findAllByBarcodeIn(incomingBarcodes).isEmpty()) {
            throw new AlreadyExistsException("Duplicate barcodes found in the system. Bulk upload aborted.");
        }

        List<Item> newItemList = requestList.stream()
                .map(req -> {
                    SubCategory subCat = subCategoryRepository.findById(req.getSubCategoryId())
                            .orElseThrow(() -> new RuntimeException("SubCategory ID " + req.getSubCategoryId() + " not found"));

                    return Item.builder()
                            .name(req.getName().trim())
                            .barcode(req.getBarcode())
                            .subCategory(subCat)
                            .costPrice(req.getCostPrice())
                            .sellingPrice(req.getSellingPrice())
                            .reorderLevel(req.getReorderLevel())
                            .imageUrl(req.getImageUrl())
                            .active(true)
                            .build();
                })
                .toList();

        return itemRepository.saveAll(newItemList).stream()
                .map(this::mapToResponse)
                .toList();
    }

    public ItemResponse getItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        return mapToResponse(item);
    }

    public ItemResponse getByBarcode(String barcode, Long branchId) {
        Item item = itemRepository.findByBarcode(barcode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (branchId == null) {
            return mapToResponse(item);
        } else {
            Integer totalQty = stockBatchRepository.getTotalQuantityByItemAndBranch(branchId, item.getId());
            return mapToResponse(item, totalQty != null ? totalQty.doubleValue() : 0.0);
        }
    }

    public List<ItemResponse> searchByName(String name, Long branchId) {

        List<Item> items = itemRepository.findByNameContainingIgnoreCase(name.trim());

        return items.stream().map(item -> {
            ItemResponse response = new ItemResponse();

            response.setId(item.getId());
            response.setBarcode(item.getBarcode());
            response.setName(item.getName());
            response.setImageUrl(item.getImageUrl());
            response.setReorderLevel(item.getReorderLevel());
            response.setActive(item.isActive());
            response.setCreatedAt(item.getCreatedAt());

            if (item.getSubCategory() != null) {
                response.setSubCategoryId(item.getSubCategory().getId());
                response.setSubCategoryName(item.getSubCategory().getName());

                if (item.getSubCategory().getCategory() != null) {
                    response.setCategoryId(item.getSubCategory().getCategory().getId());
                    response.setCategoryName(item.getSubCategory().getCategory().getName());
                }
            }

            response.setCostPrice(item.getCostPrice());

            List<StockBatchResponse> batchDTOs = new ArrayList<>();
            Double totalAvailableQty = 0.0;
            BigDecimal currentDisplayPrice = item.getSellingPrice();

            if (branchId != null) {

                List<StockBatch> activeBatches = stockBatchRepository
                        .findByBranchIdAndItemIdAndQuantityGreaterThanOrderByIdAsc(branchId, item.getId(), 0.0);

                batchDTOs = activeBatches.stream().map(batch -> new StockBatchResponse(
                        batch.getId(),
                        batch.getSellingPrice(),
                        batch.getQuantity(),
                        batch.getExpireDate()
                )).collect(Collectors.toList());

                totalAvailableQty = batchDTOs.stream()
                        .mapToDouble(StockBatchResponse::getQty)
                        .sum();

                if (!batchDTOs.isEmpty()) {
                    currentDisplayPrice = batchDTOs.get(0).getPrice();
                }
            }

            response.setBatches(batchDTOs);
            response.setAvailableQty(totalAvailableQty);
            response.setSellingPrice(currentDisplayPrice);

            return response;

        }).collect(Collectors.toList());
    }

    public List<ItemResponse> searchForBarcodePrint(String query) {

        String searchTerm = query.trim();

        List<Item> items = itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(searchTerm, searchTerm);

        return items.stream().map(item -> {
            ItemResponse response = new ItemResponse();

            response.setId(item.getId());
            response.setBarcode(item.getBarcode());
            response.setName(item.getName());
            response.setSellingPrice(item.getSellingPrice());

            return response;
        }).collect(Collectors.toList());
    }

    public List<ItemResponse> listAll(Boolean activeOnly) {
        return itemRepository.findAll().stream()
                .filter(i -> activeOnly == null || !activeOnly || i.isActive())
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public ItemResponse updateItem(Long id, ItemUpdateRequest request) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (request.getName() != null && !request.getName().isBlank()) item.setName(request.getName().trim());

        if (request.getSubCategoryId() != null) {
            SubCategory subCat = subCategoryRepository.findById(request.getSubCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found"));
            item.setSubCategory(subCat);
        }

        if (request.getCostPrice() != null) item.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) item.setSellingPrice(request.getSellingPrice());
        if (request.getReorderLevel() != null) item.setReorderLevel(request.getReorderLevel());
        if (request.getImageUrl() != null) item.setImageUrl(request.getImageUrl());
        if (request.getActive() != null) item.setActive(request.getActive());

        return mapToResponse(itemRepository.save(item));
    }

    public List<ItemWithStockResponse> itemsWithStock(Long branchId) {
        User user = authService.getLoggedUser();
        if (user.getRole() == Role.CASHIER || user.getRole() == Role.MANAGER) {
            branchId = user.getBranchId();
        }

        List<Object[]> raw = (branchId != null)
                ? itemRepository.itemsWithBranchStockRaw(branchId)
                : itemRepository.itemsWithTotalStockRaw();

        return raw.stream().map(r -> ItemWithStockResponse.builder()

                .id(r[0] != null ? ((Number) r[0]).longValue() : null)

                .barcode(r[1] != null ? r[1].toString() : null)
                .name(r[2] != null ? r[2].toString() : null)
                .categoryName(r[3] != null ? r[3].toString() : null)
                .subCategoryName(r[4] != null ? r[4].toString() : null)

                .costPrice(r[5] != null ? new BigDecimal(r[5].toString()) : BigDecimal.ZERO)
                .sellingPrice(r[6] != null ? new BigDecimal(r[6].toString()) : BigDecimal.ZERO)

                .reorderLevel(r[7] != null ? ((Number) r[7]).intValue() : 0)

                .active(Boolean.TRUE.equals(r[8]))

                .createdAt(toLocalDateTime(r[9]))

                .quantity(r.length >= 11 && r[10] != null ? ((Number) r[10]).intValue() : 0)
                .build()).toList();
    }

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
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        item.setActive(false);
        itemRepository.save(item);
    }

    private LocalDateTime toLocalDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        return Instant.ofEpochMilli(((Number) v).longValue()).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    public String generateFiveDigitBarcode() {
        String newBarcode;
        Random random = new Random();
        do {
            int randomNumber = 10000 + random.nextInt(90000);
            newBarcode = String.valueOf(randomNumber);
        } while (itemRepository.existsByBarcode(newBarcode));
        return newBarcode;
    }

    public List<ItemResponse> getRecentlyAddedItems(int limit) {

        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id"));

        return itemRepository.findAll(pageable).stream()
                .map(this::mapToResponse)
                .toList();
    }
}