package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ItemUpdateRequest;
import com.chala.posapp.dto.stock.StockBatchResponse;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemService {

    private final ItemRepository itemRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final StockBatchRepository stockBatchRepository;

    @Transactional
    public ItemResponse createItem(ItemCreateRequest request) {
        String barcode = request.getBarcode() != null ? request.getBarcode().trim() : "";

        if (barcode.isEmpty()) {
            barcode = generateFiveDigitBarcode();
        } else if (itemRepository.existsByBarcode(barcode)) {
            throw new AlreadyExistsException("Item with barcode '" + barcode + "' already exists!");
        }

        SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found with ID: " + request.getSubCategoryId()));

        boolean weightItem = Boolean.TRUE.equals(request.getWeightItem());
        MeasurementUnit defaultUnit = QuantityConversionUtil.normalizeItemUnit(weightItem, request.getDefaultUnit());

        Item item = Item.builder()
                .barcode(barcode)
                .name(request.getName().trim())
                .subCategory(subCategory)
                .costPrice(request.getCostPrice())
                .sellingPrice(request.getSellingPrice())
                .reorderLevel(QuantityConversionUtil.normalizeReorderLevel(weightItem, defaultUnit, request.getReorderLevel()))
                .weightItem(weightItem)
                .defaultUnit(defaultUnit)
                .imageUrl(request.getImageUrl())
                .active(request.getActive() == null || request.getActive())
                .build();

        return mapToResponse(itemRepository.save(item), null, List.of());
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
                    SubCategory subCategory = subCategoryRepository.findById(req.getSubCategoryId())
                            .orElseThrow(() -> new ResourceNotFoundException("SubCategory ID " + req.getSubCategoryId() + " not found"));

                    boolean weightItem = Boolean.TRUE.equals(req.getWeightItem());
                    MeasurementUnit defaultUnit = QuantityConversionUtil.normalizeItemUnit(weightItem, req.getDefaultUnit());

                    return Item.builder()
                            .name(req.getName().trim())
                            .barcode(req.getBarcode())
                            .subCategory(subCategory)
                            .costPrice(req.getCostPrice())
                            .sellingPrice(req.getSellingPrice())
                            .reorderLevel(QuantityConversionUtil.normalizeReorderLevel(weightItem, defaultUnit, req.getReorderLevel()))
                            .weightItem(weightItem)
                            .defaultUnit(defaultUnit)
                            .imageUrl(req.getImageUrl())
                            .active(req.getActive() == null || req.getActive())
                            .build();
                })
                .toList();

        return itemRepository.saveAll(newItemList).stream()
                .map(item -> mapToResponse(item, null, List.of()))
                .toList();
    }

    public Page<ItemResponse> getAllItems(String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Item> itemPage = (search != null && !search.trim().isEmpty())
                ? itemRepository.searchItems(search.trim(), pageable)
                : itemRepository.findAll(pageable);

        return itemPage.map(item -> mapToResponse(item, null, List.of()));
    }

    public ItemResponse getItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        return mapToResponse(item, null, List.of());
    }

    public ItemResponse getByBarcode(String barcode, Long branchId) {
        Item item = itemRepository.findByBarcode(barcode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (branchId == null) {
            return mapToResponse(item, null, List.of());
        }

        List<StockBatch> batches = branchId == 0L
                ? stockBatchRepository.findByItemId(item.getId())
                : stockBatchRepository.findByBranchIdAndItemId(branchId, item.getId());

        return mapToResponse(item, totalQuantity(batches), activeBatchesToResponse(item, batches));
    }

    public List<ItemResponse> searchByName(String name, Long branchId) {
        String searchTerm = name.trim();
        List<Item> items = itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(searchTerm, searchTerm);

        return items.stream()
                .map(item -> {
                    List<StockBatch> batches = List.of();
                    Integer totalQty = null;

                    if (branchId != null) {
                        batches = branchId == 0L
                                ? stockBatchRepository.findByItemId(item.getId())
                                : stockBatchRepository.findByBranchIdAndItemId(branchId, item.getId());
                        totalQty = totalQuantity(batches);
                    }

                    return mapToResponse(item, totalQty, activeBatchesToResponse(item, batches));
                })
                .collect(Collectors.toList());
    }

    public List<ItemResponse> searchForPos(String name, Long branchId) {
        String searchTerm = name.trim();
        List<Item> items = itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(searchTerm, searchTerm);

        return items.stream()
                .map(item -> {
                    List<StockBatch> batches = List.of();
                    Integer totalQty = null;

                    if (branchId != null) {
                        batches = branchId == 0L
                                ? stockBatchRepository.findByItemId(item.getId())
                                : stockBatchRepository.findByBranchIdAndItemId(branchId, item.getId());
                        totalQty = totalQuantity(batches);
                    }
                    if (batches == null || batches.isEmpty()) {
                        return null;
                    }

                    return mapToResponse(item, totalQty, activeBatchesToResponse(item, batches));
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<ItemResponse> searchForBarcodePrint(String query) {
        String searchTerm = query.trim();
        List<Item> items = itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(searchTerm, searchTerm);

        return items.stream()
                .map(item -> mapToResponse(item, null, List.of()))
                .collect(Collectors.toList());
    }

    public List<ItemResponse> listAll(Boolean activeOnly) {
        return itemRepository.findAll().stream()
                .filter(item -> activeOnly == null || !activeOnly || item.isActive())
                .map(item -> mapToResponse(item, null, List.of()))
                .toList();
    }

    @Transactional
    public ItemResponse updateItem(Long id, ItemUpdateRequest request) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (request.getName() != null && !request.getName().isBlank()) {
            item.setName(request.getName().trim());
        }

        if (request.getSubCategoryId() != null) {
            SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found"));
            item.setSubCategory(subCategory);
        }

        boolean weightItem = request.getWeightItem() != null ? request.getWeightItem() : item.isWeightItem();
        MeasurementUnit defaultUnit = request.getDefaultUnit() != null
                ? QuantityConversionUtil.normalizeItemUnit(weightItem, request.getDefaultUnit())
                : QuantityConversionUtil.normalizeItemUnit(weightItem, item.getDefaultUnit());

        item.setWeightItem(weightItem);
        item.setDefaultUnit(defaultUnit);

        if (request.getCostPrice() != null) item.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) item.setSellingPrice(request.getSellingPrice());
        if (request.getReorderLevel() != null) {
            item.setReorderLevel(QuantityConversionUtil.normalizeReorderLevel(weightItem, defaultUnit, request.getReorderLevel()));
        }
        if (request.getImageUrl() != null) item.setImageUrl(request.getImageUrl());
        if (request.getActive() != null) item.setActive(request.getActive());

        return mapToResponse(itemRepository.save(item), null, List.of());
    }

    private List<StockBatchResponse> activeBatchesToResponse(Item item, List<StockBatch> batches) {
        if (batches == null || batches.isEmpty()) {
            return new ArrayList<>();
        }

        return batches.stream()
                .filter(batch -> batch.getQuantity() != null && batch.getQuantity() > 0)
                .map(batch -> new StockBatchResponse(
                        batch.getId(),
                        batch.getSellingPrice(),
                        batch.getQuantity(),
                        QuantityConversionUtil.toDisplayQuantity(item, batch.getQuantity()),
                        item.getDefaultUnit(),
                        batch.getExpireDate()
                ))
                .collect(Collectors.toList());
    }

    private Integer totalQuantity(List<StockBatch> batches) {
        if (batches == null || batches.isEmpty()) {
            return 0;
        }
        return batches.stream()
                .filter(batch -> batch.getQuantity() != null)
                .mapToInt(StockBatch::getQuantity)
                .sum();
    }

    private ItemResponse mapToResponse(Item item, Integer availableBaseQty, List<StockBatchResponse> batches) {
        SubCategory subCategory = item.getSubCategory();
        Category category = subCategory != null ? subCategory.getCategory() : null;

        BigDecimal availableQty = availableBaseQty == null
                ? null
                : QuantityConversionUtil.toDisplayQuantity(item, availableBaseQty);

        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .name(item.getName())
                .subCategoryId(subCategory != null ? subCategory.getId() : null)
                .subCategoryName(subCategory != null ? subCategory.getName() : "N/A")
                .categoryId(category != null ? category.getId() : null)
                .categoryName(category != null ? category.getName() : "N/A")
                .costPrice(item.getCostPrice())
                .sellingPrice(item.getSellingPrice())
                .availableQty(availableQty)
                .availableBaseQty(availableBaseQty)
                .reorderLevel(QuantityConversionUtil.toDisplayQuantity(item.isWeightItem(), item.getDefaultUnit(), item.getReorderLevel()))
                .reorderLevelBaseQty(item.getReorderLevel())
                .weightItem(item.isWeightItem())
                .defaultUnit(item.getDefaultUnit())
                .imageUrl(item.getImageUrl())
                .active(item.isActive())
                .createdAt(item.getCreatedAt())
                .batches(batches)
                .build();
    }

    @Transactional
    public void deactivateItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        item.setActive(false);
        itemRepository.save(item);
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
                .map(item -> mapToResponse(item, null, List.of()))
                .toList();
    }
}
