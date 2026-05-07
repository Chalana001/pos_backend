package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemIngredientRequest;
import com.chala.posapp.dto.item.ItemIngredientResponse;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ItemUpdateRequest;
import com.chala.posapp.dto.stock.StockBatchResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.BranchServiceItem;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.RecipeIngredient;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.BranchServiceItemRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemService {

    private final ItemRepository itemRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BranchRepository branchRepository;
    private final BranchServiceItemRepository branchServiceItemRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;

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

        ItemType itemType = request.getItemType() != null ? request.getItemType() : ItemType.NORMAL;
        MeasurementUnit defaultUnit = QuantityConversionUtil.normalizeItemUnit(itemType, request.getDefaultUnit());

        Item item = Item.builder()
                .barcode(barcode)
                .name(request.getName().trim())
                .subCategory(subCategory)
                .costPrice(request.getCostPrice())
                .sellingPrice(request.getSellingPrice())
                .reorderLevel(QuantityConversionUtil.normalizeReorderLevel(itemType, defaultUnit, request.getReorderLevel()))
                .itemType(itemType)
                .defaultUnit(defaultUnit)
                .imageUrl(request.getImageUrl())
                .kotEnabled(Boolean.TRUE.equals(request.getIsKotEnabled()))
                .active(request.getActive() == null || request.getActive())
                .build();

        Item savedItem = itemRepository.save(item);
        syncServiceBranches(savedItem, request.getBranchIds());
        syncRecipeIngredients(savedItem, request.getIngredients());
        return mapToResponse(savedItem, null, List.of());
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

        validateNoDuplicateIncomingBarcodes(incomingBarcodes);

        if (!itemRepository.findAllByBarcodeIn(incomingBarcodes).isEmpty()) {
            throw new AlreadyExistsException("Duplicate barcodes found in the system. Bulk upload aborted.");
        }

        List<Item> newItemList = requestList.stream()
                .map(req -> {
                    SubCategory subCategory = subCategoryRepository.findById(req.getSubCategoryId())
                            .orElseThrow(() -> new ResourceNotFoundException("SubCategory ID " + req.getSubCategoryId() + " not found"));

                    ItemType itemType = req.getItemType() != null ? req.getItemType() : ItemType.NORMAL;
                    MeasurementUnit defaultUnit = QuantityConversionUtil.normalizeItemUnit(itemType, req.getDefaultUnit());

                    return Item.builder()
                            .name(req.getName().trim())
                            .barcode(req.getBarcode())
                            .subCategory(subCategory)
                            .costPrice(req.getCostPrice())
                            .sellingPrice(req.getSellingPrice())
                            .reorderLevel(QuantityConversionUtil.normalizeReorderLevel(itemType, defaultUnit, req.getReorderLevel()))
                            .itemType(itemType)
                            .defaultUnit(defaultUnit)
                            .imageUrl(req.getImageUrl())
                            .kotEnabled(Boolean.TRUE.equals(req.getIsKotEnabled()))
                            .active(req.getActive() == null || req.getActive())
                            .build();
                })
                .toList();

        return itemRepository.saveAll(newItemList).stream()
                .map(item -> {
                    ItemCreateRequest sourceRequest = requestList.stream()
                            .filter(req -> item.getBarcode().equals(req.getBarcode()))
                            .findFirst()
                            .orElse(null);
                    syncServiceBranches(item, sourceRequest != null ? sourceRequest.getBranchIds() : null);
                    syncRecipeIngredients(item, sourceRequest != null ? sourceRequest.getIngredients() : null);
                    return mapToResponse(item, null, List.of());
                })
                .toList();
    }

    public Page<ItemResponse> getAllItems(
            String search,
            int page,
            int size,
            Long categoryId,
            Long subCategoryId,
            String itemType,
            Boolean active,
            Boolean kotEnabled,
            String priceField,
            String priceOperator,
            BigDecimal priceAmount
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        ItemType normalizedItemType = parseItemType(itemType);
        String normalizedPriceField = normalizePriceField(priceField);
        String normalizedPriceOperator = normalizeFilter(priceOperator);
        BigDecimal normalizedPriceAmount = priceAmount != null && priceAmount.compareTo(BigDecimal.ZERO) >= 0
                ? priceAmount
                : null;
        Page<Item> itemPage = itemRepository.searchItemsWithFilters(
                search != null ? search.trim() : "",
                categoryId,
                subCategoryId,
                normalizedItemType,
                active,
                kotEnabled,
                normalizedPriceField,
                normalizedPriceOperator,
                normalizedPriceAmount,
                pageable
        );

        return itemPage.map(item -> mapToResponse(item, null, List.of()));
    }

    private ItemType parseItemType(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("ALL")) {
            return null;
        }
        return ItemType.valueOf(value.trim().toUpperCase());
    }

    private String normalizePriceField(String value) {
        if (value == null || value.isBlank()) {
            return "SELLING";
        }
        String normalized = value.trim().toUpperCase();
        return normalized.equals("COST") ? "COST" : "SELLING";
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return "ALL";
        }
        return value.trim().toUpperCase();
    }

    public ItemResponse getItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        return mapToResponse(item, null, List.of());
    }

    public ItemResponse getByBarcode(String barcode, Long branchId) {
        Item item = itemRepository.findByBarcode(barcode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (item.getItemType() == ItemType.SERVICE && branchId != null && branchId != 0L
                && !branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(branchId, item.getId())) {
            throw new ResourceNotFoundException("Service item not available in this branch");
        }

        if (branchId == null || item.getItemType() == ItemType.RECIPE || item.getItemType() == ItemType.SERVICE) {
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

                    if (branchId != null && item.getItemType() != ItemType.RECIPE && item.getItemType() != ItemType.SERVICE) {
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
                    if (!item.isActive()) {
                        return null;
                    }

                    if (item.getItemType() == ItemType.SERVICE && branchId != null && branchId != 0L
                            && !branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(branchId, item.getId())) {
                        return null;
                    }

                    List<StockBatch> batches = List.of();
                    Integer totalQty = null;

                    if (branchId != null && item.getItemType() != ItemType.RECIPE && item.getItemType() != ItemType.SERVICE) {
                        batches = branchId == 0L
                                ? stockBatchRepository.findByItemId(item.getId())
                                : stockBatchRepository.findByBranchIdAndItemId(branchId, item.getId());
                        totalQty = totalQuantity(batches);
                    }

                    if (item.getItemType() != ItemType.SERVICE
                            && item.getItemType() != ItemType.RECIPE
                            && (batches == null || batches.isEmpty())) {
                        return null;
                    }

                    return mapToResponse(item, totalQty, activeBatchesToResponse(item, batches));
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private void validateNoDuplicateIncomingBarcodes(List<String> incomingBarcodes) {
        Set<String> uniqueBarcodes = new HashSet<>();
        for (String barcode : incomingBarcodes) {
            if (!uniqueBarcodes.add(barcode)) {
                throw new AlreadyExistsException("Duplicate barcodes found in the request. Bulk upload aborted.");
            }
        }
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
        ItemType previousItemType = item.getItemType();

        if (request.getName() != null && !request.getName().isBlank()) {
            item.setName(request.getName().trim());
        }

        if (request.getSubCategoryId() != null) {
            SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found"));
            item.setSubCategory(subCategory);
        }

        ItemType itemType = request.getItemType() != null ? request.getItemType() : item.getItemType();
        MeasurementUnit defaultUnit = request.getDefaultUnit() != null
                ? QuantityConversionUtil.normalizeItemUnit(itemType, request.getDefaultUnit())
                : QuantityConversionUtil.normalizeItemUnit(itemType, item.getDefaultUnit());

        item.setItemType(itemType);
        item.setDefaultUnit(defaultUnit);

        if (request.getCostPrice() != null) item.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) item.setSellingPrice(request.getSellingPrice());
        if (request.getReorderLevel() != null) {
            item.setReorderLevel(QuantityConversionUtil.normalizeReorderLevel(itemType, defaultUnit, request.getReorderLevel()));
        }
        if (request.getImageUrl() != null) item.setImageUrl(request.getImageUrl());
        if (request.getIsKotEnabled() != null) item.setKotEnabled(request.getIsKotEnabled());
        if (request.getActive() != null) item.setActive(request.getActive());

        Item savedItem = itemRepository.save(item);
        if (request.getBranchIds() != null || previousItemType != itemType) {
            syncServiceBranches(savedItem, request.getBranchIds());
        }
        if (request.getIngredients() != null || previousItemType != itemType) {
            syncRecipeIngredients(savedItem, request.getIngredients());
        }
        return mapToResponse(savedItem, null, List.of());
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
        List<Long> branchIds = item.getItemType() == ItemType.SERVICE
                ? branchServiceItemRepository.findByItemId(item.getId()).stream()
                .filter(BranchServiceItem::isActive)
                .map(BranchServiceItem::getBranchId)
                .distinct()
                .sorted()
                .toList()
                : List.of();
        List<ItemIngredientResponse> ingredients = item.getItemType() == ItemType.RECIPE
                ? mapRecipeIngredientResponses(recipeIngredientRepository.findByParentItemId(item.getId()))
                : List.of();

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
                .reorderLevel(QuantityConversionUtil.toDisplayQuantity(item.getItemType(), item.getDefaultUnit(), item.getReorderLevel()))
                .reorderLevelBaseQty(item.getReorderLevel())
                .itemType(item.getItemType())
                .defaultUnit(item.getDefaultUnit())
                .imageUrl(item.getImageUrl())
                .kotEnabled(item.isKotEnabled())
                .active(item.isActive())
                .createdAt(item.getCreatedAt())
                .branchIds(branchIds)
                .ingredients(ingredients)
                .batches(batches)
                .build();
    }

    private void syncServiceBranches(Item item, List<Long> branchIds) {
        if (item.getItemType() != ItemType.SERVICE) {
            branchServiceItemRepository.deleteByItemId(item.getId());
            branchServiceItemRepository.flush();
            return;
        }

        if (branchIds == null || branchIds.isEmpty()) {
            throw new BadRequestException("Service items must be assigned to at least one branch");
        }

        List<Long> distinctBranchIds = branchIds.stream().distinct().toList();
        List<Branch> branches = branchRepository.findAllById(distinctBranchIds);
        if (branches.size() != distinctBranchIds.size()) {
            throw new ResourceNotFoundException("One or more branches not found");
        }

        branchServiceItemRepository.deleteByItemId(item.getId());
        branchServiceItemRepository.flush();
        List<BranchServiceItem> assignments = branches.stream()
                .map(branch -> BranchServiceItem.builder()
                        .branchId(branch.getId())
                        .itemId(item.getId())
                        .active(true)
                        .build())
                .toList();
        branchServiceItemRepository.saveAll(assignments);
    }

    private void syncRecipeIngredients(Item item, List<ItemIngredientRequest> ingredientRequests) {
        if (item.getItemType() != ItemType.RECIPE) {
            recipeIngredientRepository.deleteByParentItemId(item.getId());
            recipeIngredientRepository.flush();
            return;
        }

        if (ingredientRequests == null) {
            return;
        }

        recipeIngredientRepository.deleteByParentItemId(item.getId());
        recipeIngredientRepository.flush();
        if (ingredientRequests.isEmpty()) {
            return;
        }

        Set<Long> distinctIngredientIds = new HashSet<>();
        List<RecipeIngredient> ingredients = new ArrayList<>();

        for (ItemIngredientRequest ingredientRequest : ingredientRequests) {
            if (!distinctIngredientIds.add(ingredientRequest.getIngredientItemId())) {
                throw new BadRequestException("Duplicate ingredient item found in recipe");
            }

            Item ingredientItem = itemRepository.findById(ingredientRequest.getIngredientItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ingredient item not found: " + ingredientRequest.getIngredientItemId()));

            if (!ingredientItem.isActive()) {
                throw new BadRequestException("Ingredient item is inactive: " + ingredientItem.getName());
            }
            if (ingredientItem.getId().equals(item.getId())) {
                throw new BadRequestException("Recipe item cannot include itself as an ingredient");
            }
            if (ingredientItem.getItemType() == ItemType.SERVICE || ingredientItem.getItemType() == ItemType.RECIPE) {
                throw new BadRequestException("Recipe ingredients must be stock-tracked grocery items");
            }

            int normalizedQuantity = QuantityConversionUtil.normalizeQuantity(
                    ingredientItem,
                    ingredientRequest.getQuantity(),
                    ingredientRequest.getQtyUnit()
            );

            ingredients.add(RecipeIngredient.builder()
                    .parentItemId(item.getId())
                    .ingredientId(ingredientItem.getId())
                    .quantity(normalizedQuantity)
                    .build());
        }

        recipeIngredientRepository.saveAll(ingredients);
    }

    private List<ItemIngredientResponse> mapRecipeIngredientResponses(List<RecipeIngredient> recipeIngredients) {
        if (recipeIngredients == null || recipeIngredients.isEmpty()) {
            return List.of();
        }

        Map<Long, Item> ingredientMap = itemRepository.findAllById(
                        recipeIngredients.stream()
                                .map(RecipeIngredient::getIngredientId)
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(Item::getId, ingredient -> ingredient));

        return recipeIngredients.stream()
                .map(recipeIngredient -> mapIngredientResponse(recipeIngredient, ingredientMap.get(recipeIngredient.getIngredientId())))
                .toList();
    }

    private ItemIngredientResponse mapIngredientResponse(RecipeIngredient recipeIngredient, Item ingredient) {
        if (ingredient == null) {
            throw new ResourceNotFoundException("Ingredient item not found: " + recipeIngredient.getIngredientId());
        }

        return ItemIngredientResponse.builder()
                .ingredientItemId(ingredient.getId())
                .ingredientBarcode(ingredient.getBarcode())
                .ingredientName(ingredient.getName())
                .quantity(QuantityConversionUtil.toDisplayQuantity(ingredient, recipeIngredient.getQuantity()))
                .baseQuantity(recipeIngredient.getQuantity())
                .qtyUnit(ingredient.getDefaultUnit())
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
