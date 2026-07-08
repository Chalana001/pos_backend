package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemDeleteCheckResponse;
import com.chala.posapp.dto.item.ItemIngredientRequest;
import com.chala.posapp.dto.item.ItemIngredientResponse;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ItemUpdateRequest;
import com.chala.posapp.dto.item.StockProcessingOutputLinkRequest;
import com.chala.posapp.dto.item.StockProcessingOutputLinkResponse;
import com.chala.posapp.dto.stock.StockBatchResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.BranchServiceItem;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemOverheadCostMode;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.RecipeIngredient;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.StockProcessingOutputLink;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.BranchServiceItemRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.StockProcessingOutputLinkRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final StockProcessingOutputLinkRepository stockProcessingOutputLinkRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final AppConfigurationService appConfigurationService;
    private final SecurityUtils securityUtils;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public ItemResponse createItem(ItemCreateRequest request) {
        String barcode = request.getBarcode() != null ? request.getBarcode().trim() : "";

        if (barcode.isEmpty()) {
            barcode = generateUniqueBarcode(new HashSet<>());
        } else if (itemRepository.existsByBarcode(barcode)) {
            throw new AlreadyExistsException("Item with barcode '" + barcode + "' already exists!");
        }

        SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found with ID: " + request.getSubCategoryId()));

        ItemType itemType = request.getItemType() != null ? request.getItemType() : ItemType.NORMAL;
        validateItemTypeAllowed(itemType);
        MeasurementUnit defaultUnit = QuantityConversionUtil.normalizeItemUnit(itemType, request.getDefaultUnit());
        ItemOverheadCostMode overheadCostMode = normalizeOverheadCostMode(itemType, request.getOverheadCostMode());
        BigDecimal overheadCostValue = normalizeOverheadCostValue(itemType, overheadCostMode, request.getOverheadCostValue());

        Item item = Item.builder()
                .barcode(barcode)
                .name(request.getName().trim())
                .altName(normalizeAltName(request.getAltName()))
                .subCategory(subCategory)
                .costPrice(request.getCostPrice())
                .sellingPrice(request.getSellingPrice())
                .reorderLevel(QuantityConversionUtil.normalizeReorderLevel(itemType, defaultUnit, request.getReorderLevel()))
                .itemType(itemType)
                .defaultUnit(defaultUnit)
                .imageUrl(request.getImageUrl())
                .kotEnabled(resolveKotEnabled(request.getIsKotEnabled()))
                .active(request.getActive() == null || request.getActive())
                .posVisible(request.getPosVisible() == null || request.getPosVisible())
                .stockProcessingEnabled(Boolean.TRUE.equals(request.getStockProcessingEnabled()))
                .overheadCostMode(overheadCostMode)
                .overheadCostValue(overheadCostValue)
                .build();

        Item savedItem = itemRepository.save(item);
        syncServiceBranches(savedItem, request.getBranchIds());
        syncRecipeIngredients(savedItem, request.getIngredients());
        syncProcessingOutputs(savedItem, request.getProcessingOutputs());
        createZeroStockBatchesIfRequired(savedItem);
        return mapToResponse(savedItem, null, List.of());
    }

    @Transactional
    public List<ItemResponse> bulkCreate(List<ItemCreateRequest> requestList) {
        Set<String> reservedBarcodes = requestList.stream()
                .map(ItemCreateRequest::getBarcode)
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(barcode -> !barcode.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));

        requestList.forEach(req -> {
            if (req.getBarcode() == null || req.getBarcode().trim().isEmpty()) {
                req.setBarcode(generateUniqueBarcode(reservedBarcodes));
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
                    validateItemTypeAllowed(itemType);
                    MeasurementUnit defaultUnit = QuantityConversionUtil.normalizeItemUnit(itemType, req.getDefaultUnit());
                    ItemOverheadCostMode overheadCostMode = normalizeOverheadCostMode(itemType, req.getOverheadCostMode());
                    BigDecimal overheadCostValue = normalizeOverheadCostValue(itemType, overheadCostMode, req.getOverheadCostValue());

                    return Item.builder()
                            .name(req.getName().trim())
                            .altName(normalizeAltName(req.getAltName()))
                            .barcode(req.getBarcode())
                            .subCategory(subCategory)
                            .costPrice(req.getCostPrice())
                            .sellingPrice(req.getSellingPrice())
                            .reorderLevel(QuantityConversionUtil.normalizeReorderLevel(itemType, defaultUnit, req.getReorderLevel()))
                            .itemType(itemType)
                            .defaultUnit(defaultUnit)
                            .imageUrl(req.getImageUrl())
                            .kotEnabled(resolveKotEnabled(req.getIsKotEnabled()))
                            .active(req.getActive() == null || req.getActive())
                            .posVisible(req.getPosVisible() == null || req.getPosVisible())
                            .stockProcessingEnabled(Boolean.TRUE.equals(req.getStockProcessingEnabled()))
                            .overheadCostMode(overheadCostMode)
                            .overheadCostValue(overheadCostValue)
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
                    syncProcessingOutputs(item, sourceRequest != null ? sourceRequest.getProcessingOutputs() : null);
                    createZeroStockBatchesIfRequired(item);
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

        if (!item.isActive() || !item.isPosVisible()) {
            throw new ResourceNotFoundException("Item not available in POS");
        }

        if (!appConfigurationService.isItemTypeEnabled(item.getItemType(), branchId)) {
            throw new ResourceNotFoundException("Item type is disabled");
        }

        if (item.getItemType() == ItemType.SERVICE && branchId != null && branchId != 0L
                && !branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(branchId, item.getId())) {
            throw new ResourceNotFoundException("Service item not available in this branch");
        }

        if (branchId == null || item.getItemType() == ItemType.RECIPE || item.getItemType() == ItemType.SERVICE) {
            return mapToResponse(item, null, List.of(), branchId);
        }

        List<StockBatch> batches = branchId == 0L
                ? stockBatchRepository.findAvailableBatchesForScope(null, item.getId())
                : stockBatchRepository.findAvailableBatchesForScope(branchId, item.getId());

        return mapToResponse(item, availableQuantity(batches), batchesToResponse(item, batches), branchId);
    }

    public List<ItemResponse> searchByName(String name, Long branchId) {
        String searchTerm = name.trim();
        List<Item> items = itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(searchTerm, searchTerm);

        return items.stream()
                .map(item -> {
                    if (!appConfigurationService.isItemTypeEnabled(item.getItemType(), branchId)) {
                        return null;
                    }

                    List<StockBatch> batches = List.of();
                    Integer totalQty = null;

                    if (branchId != null && item.getItemType() != ItemType.RECIPE && item.getItemType() != ItemType.SERVICE) {
                        batches = branchId == 0L
                                ? stockBatchRepository.findAvailableBatchesForScope(null, item.getId())
                                : stockBatchRepository.findAvailableBatchesForScope(branchId, item.getId());
                        totalQty = availableQuantity(batches);
                    }

                    return mapToResponse(item, totalQty, batchesToResponse(item, batches), branchId);
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<ItemResponse> searchForPurchase(String name, Long branchId) {
        Long scopedBranchId = resolvePurchaseSearchBranchId(branchId);
        String searchTerm = name.trim();
        // PERF-08 FIX: use FULLTEXT index for searches >= 3 chars (fast), fall back to LIKE for short terms
        List<Item> items = searchTerm.length() >= 3
                ? itemRepository.searchForPurchaseFt(searchTerm + "*")
                : itemRepository.searchForPurchase(searchTerm);

        return items.stream()
                .map(item -> {
                    if (!item.isActive() || !isStockTrackedItem(item)) {
                        return null;
                    }

                    if (!appConfigurationService.isItemTypeEnabled(item.getItemType(), scopedBranchId)) {
                        return null;
                    }

                    List<StockBatch> batches = List.of();
                    Integer totalQty = null;

                    if (scopedBranchId != null) {
                        Long scopeId = scopedBranchId == 0L ? null : scopedBranchId;
                        batches = stockBatchRepository.findAvailableBatchesForScope(scopeId, item.getId());
                        totalQty = stockBatchRepository.getTotalQuantityByItemAndScope(scopeId, item.getId());
                    }

                    return mapToResponse(item, totalQty, batchesToResponse(item, batches), scopedBranchId);
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Long resolvePurchaseSearchBranchId(Long requestedBranchId) {
        User user = securityUtils.getOptionalCurrentUser();
        if (user != null && (user.getRole() == Role.MANAGER || user.getRole() == Role.CASHIER)) {
            if (user.getBranchId() == null) {
                throw new BadRequestException("User branch not assigned");
            }
            return user.getBranchId();
        }
        return requestedBranchId;
    }

    // BUG-07/08 FIX: Removed duplicate securityUtils.getOptionalCurrentUser() — use SecurityUtils instead
    // Note: uses getOptionalCurrentUser() because item-search endpoints may be called without auth

    public List<ItemResponse> searchForPos(String name, Long branchId) {
        String searchTerm = name.trim();
        // PERF-08 FIX: use FULLTEXT index for searches >= 3 chars, fall back to LIKE for 1-2 char inputs
        List<Item> items = searchTerm.length() >= 3
                ? itemRepository.searchForPosFt(searchTerm + "*")
                : itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(searchTerm, searchTerm);

        return items.stream()
                .map(item -> {
                    if (!item.isActive() || !item.isPosVisible()) {
                        return null;
                    }

                    if (!appConfigurationService.isItemTypeEnabled(item.getItemType(), branchId)) {
                        return null;
                    }

                    if (item.getItemType() == ItemType.SERVICE && branchId != null && branchId != 0L
                            && !branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(branchId, item.getId())) {
                        return null;
                    }

                    List<StockBatch> batches = List.of();
                    Integer totalQty = null;
                    boolean everStocked = true;

                    if (branchId != null && item.getItemType() != ItemType.RECIPE && item.getItemType() != ItemType.SERVICE) {
                        Long scopeBranchId = branchId == 0L ? null : branchId;
                        batches = stockBatchRepository.findAvailableBatchesForScope(scopeBranchId, item.getId());
                        totalQty = availableQuantity(batches);
                        everStocked = batches != null && !batches.isEmpty()
                                || stockBatchRepository.existsBatchForScope(scopeBranchId, item.getId());
                    }

                    if (item.getItemType() != ItemType.SERVICE
                            && item.getItemType() != ItemType.RECIPE
                            && !everStocked) {
                        return null;
                    }

                    return mapToResponse(item, totalQty, batchesToResponse(item, batches), branchId);
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

        if (request.getAltName() != null) {
            item.setAltName(normalizeAltName(request.getAltName()));
        }

        if (request.getSubCategoryId() != null) {
            SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found"));
            item.setSubCategory(subCategory);
        }

        ItemType itemType = request.getItemType() != null ? request.getItemType() : item.getItemType();
        validateItemTypeAllowed(itemType);
        MeasurementUnit defaultUnit = request.getDefaultUnit() != null
                ? QuantityConversionUtil.normalizeItemUnit(itemType, request.getDefaultUnit())
                : QuantityConversionUtil.normalizeItemUnit(itemType, item.getDefaultUnit());

        item.setItemType(itemType);
        item.setDefaultUnit(defaultUnit);
        ItemOverheadCostMode overheadCostMode = request.getOverheadCostMode() != null
                ? normalizeOverheadCostMode(itemType, request.getOverheadCostMode())
                : normalizeOverheadCostMode(itemType, item.getOverheadCostMode());
        BigDecimal requestedOverheadValue = request.getOverheadCostValue() != null
                ? request.getOverheadCostValue()
                : item.getOverheadCostValue();
        BigDecimal overheadCostValue = normalizeOverheadCostValue(itemType, overheadCostMode, requestedOverheadValue);
        item.setOverheadCostMode(overheadCostMode);
        item.setOverheadCostValue(overheadCostValue);

        if (request.getCostPrice() != null) item.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) item.setSellingPrice(request.getSellingPrice());
        if (request.getReorderLevel() != null) {
            item.setReorderLevel(QuantityConversionUtil.normalizeReorderLevel(itemType, defaultUnit, request.getReorderLevel()));
        }
        if (request.getImageUrl() != null) item.setImageUrl(request.getImageUrl());
        if (request.getIsKotEnabled() != null) item.setKotEnabled(resolveKotEnabled(request.getIsKotEnabled()));
        if (request.getActive() != null) item.setActive(request.getActive());
        if (request.getPosVisible() != null) item.setPosVisible(request.getPosVisible());
        if (request.getStockProcessingEnabled() != null) {
            item.setStockProcessingEnabled(request.getStockProcessingEnabled());
        }

        Item savedItem = itemRepository.save(item);
        if (request.getBranchIds() != null || previousItemType != itemType) {
            syncServiceBranches(savedItem, request.getBranchIds());
        }
        if (request.getIngredients() != null || previousItemType != itemType) {
            syncRecipeIngredients(savedItem, request.getIngredients());
        }
        if (request.getProcessingOutputs() != null || previousItemType != itemType || request.getStockProcessingEnabled() != null) {
            syncProcessingOutputs(savedItem, request.getProcessingOutputs());
        }
        return mapToResponse(savedItem, null, List.of());
    }

    public ItemDeleteCheckResponse checkDelete(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        List<String> reasons = collectDeleteBlockers(id);
        return ItemDeleteCheckResponse.builder()
                .itemId(item.getId())
                .itemName(item.getName())
                .canDelete(reasons.isEmpty())
                .reasons(reasons)
                .build();
    }

    @Transactional
    public void deleteItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        List<String> reasons = collectDeleteBlockers(id);
        if (!reasons.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Item cannot be deleted because it has history or active references");
        }

        branchServiceItemRepository.deleteByItemId(id);
        recipeIngredientRepository.deleteByParentItemId(id);
        recipeIngredientRepository.flush();
        stockProcessingOutputLinkRepository.deleteBySourceItemId(id);
        stockProcessingOutputLinkRepository.flush();

        List<StockBatch> stockBatches = stockBatchRepository.findByItemId(id);
        if (!stockBatches.isEmpty()) {
            stockBatchRepository.deleteAll(stockBatches);
            stockBatchRepository.flush();
        }

        itemRepository.delete(item);
    }

    @Transactional
    public void deactivateItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        item.setActive(false);
        item.setPosVisible(false);
        itemRepository.save(item);
    }

    private List<String> collectDeleteBlockers(Long itemId) {
        List<String> reasons = new ArrayList<>();

        addReason(reasons, countRefs("order_items", "item_id", itemId), "Item has order history");
        addReason(reasons, countRefs("order_item_stock_usages", "item_id", itemId), "Item has stock usage history");
        addReason(reasons, countStockBatchBlockers(itemId), "Item has real stock batches");
        addReason(reasons, countRefs("grn_items", "item_id", itemId), "Item has purchase/GRN history");
        addReason(reasons, countRefs("pending_order_items", "item_id", itemId), "Item is used in pending table orders");
        addReason(reasons, countRefs("recipe_ingredients", "ingredient_id", itemId), "Item is used as a recipe ingredient");
        addReason(reasons, countRefs("stock_processing_output_links", "output_item_id", itemId), "Item is linked as a stock processing output");
        addReason(reasons, countRefs("stock_processings", "source_item_id", itemId), "Item has stock processing history as a source");
        addReason(reasons, countRefs("stock_processing_outputs", "output_item_id", itemId), "Item has stock processing output history");
        addReason(reasons, countRefs("stock_adjustments", "item_id", itemId), "Item has stock adjustment history");
        addReason(reasons, countRefs("stock_transfer_items", "item_id", itemId), "Item has stock transfer history");
        addReason(reasons, countRefs("promotion_targets", "item_id", itemId), "Item is used in promotion rules");
        addReason(reasons, countRefs("warranties", "item_id", itemId), "Item has warranty history");
        addReason(reasons, countRefs("stock_override_audits", "sale_item_id", itemId), "Item has stock override sale history");
        addReason(reasons, countRefs("stock_override_audits", "stock_item_id", itemId), "Item has stock override ingredient history");
        addReason(reasons, countSupplierItemRefs(itemId), "Item is linked to suppliers");

        return reasons;
    }

    private void addReason(List<String> reasons, long count, String message) {
        if (count > 0) {
            reasons.add(message + " (" + count + ")");
        }
    }

    private long countRefs(String tableName, String columnName, Long itemId) {
        Number count = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM " + tableName + " WHERE " + columnName + " = :itemId")
                .setParameter("itemId", itemId)
                .getSingleResult();
        return count.longValue();
    }

    private long countStockBatchBlockers(Long itemId) {
        Number count = (Number) entityManager.createNativeQuery("""
                        SELECT COUNT(*)
                        FROM stock_batches
                        WHERE item_id = :itemId
                          AND (
                            COALESCE(quantity, 0) <> 0
                            OR COALESCE(original_quantity, 0) <> 0
                            OR COALESCE(source_type, 'PURCHASE') <> 'AUTO'
                          )
                        """)
                .setParameter("itemId", itemId)
                .getSingleResult();
        return count.longValue();
    }

    private long countSupplierItemRefs(Long itemId) {
        Number count = (Number) entityManager.createNativeQuery("""
                        SELECT COUNT(*)
                        FROM supplier_items
                        WHERE item_id = :itemId
                        """)
                .setParameter("itemId", itemId)
                .getSingleResult();
        return count.longValue();
    }

    private List<StockBatchResponse> batchesToResponse(Item item, List<StockBatch> batches) {
        if (batches == null || batches.isEmpty()) {
            return new ArrayList<>();
        }

        return batches.stream()
                .map(batch -> new StockBatchResponse(
                        batch.getId(),
                        batch.getSellingPrice(),
                        batch.getQuantity() == null ? 0 : batch.getQuantity(),
                        QuantityConversionUtil.toDisplayQuantity(item, batch.getQuantity() == null ? 0 : batch.getQuantity()),
                        item.getDefaultUnit(),
                        batch.getExpireDate()
                ))
                .collect(Collectors.toList());
    }

    private Integer availableQuantity(List<StockBatch> batches) {
        if (batches == null || batches.isEmpty()) {
            return 0;
        }
        return batches.stream()
                .filter(batch -> batch.getQuantity() != null && batch.getQuantity() > 0)
                .mapToInt(StockBatch::getQuantity)
                .sum();
    }

    private ItemResponse mapToResponse(Item item, Integer availableBaseQty, List<StockBatchResponse> batches) {
        return mapToResponse(item, availableBaseQty, batches, null);
    }

    private ItemResponse mapToResponse(Item item, Integer availableBaseQty, List<StockBatchResponse> batches, Long branchId) {
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
        List<StockProcessingOutputLinkResponse> processingOutputs = item.isStockProcessingEnabled()
                ? mapProcessingOutputResponses(stockProcessingOutputLinkRepository.findBySourceItemIdOrderByIdAsc(item.getId()))
                : List.of();

        BigDecimal availableQty = availableBaseQty == null
                ? null
                : QuantityConversionUtil.toDisplayQuantity(item, availableBaseQty);

        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .name(item.getName())
                .altName(item.getAltName())
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
                .kotEnabled(appConfigurationService.isKotEnabled(branchId) && item.isKotEnabled())
                .active(item.isActive())
                .posVisible(item.isPosVisible())
                .stockProcessingEnabled(item.isStockProcessingEnabled())
                .overheadCostMode(item.getOverheadCostMode())
                .overheadCostValue(item.getOverheadCostValue())
                .createdAt(item.getCreatedAt())
                .branchIds(branchIds)
                .ingredients(ingredients)
                .processingOutputs(processingOutputs)
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

    private void createZeroStockBatchesIfRequired(Item item) {
        if (!shouldCreateZeroStockBatches(item)) {
            return;
        }

        List<Branch> activeBranches = branchRepository.findAll().stream()
                .filter(Branch::isActive)
                .toList();
        if (activeBranches.isEmpty()) {
            return;
        }

        List<StockBatch> zeroBatches = activeBranches.stream()
                .map(branch -> StockBatch.builder()
                        .branch(branch)
                        .item(item)
                        .quantity(0)
                        .originalQuantity(0)
                        .costPrice(item.getCostPrice())
                        .sellingPrice(item.getSellingPrice())
                        .sourceType(StockBatchSourceType.AUTO)
                        .batchCode("AUTO-ZERO-" + branch.getId() + "-" + item.getId())
                        .build())
                .toList();
        stockBatchRepository.saveAll(zeroBatches);
    }

    private boolean shouldCreateZeroStockBatches(Item item) {
        if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
            return false;
        }

        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            return false;
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        Boolean result = TenantContext.callWith("MASTER",
                () -> tx.execute(status ->
                        tenantSubscriptionRepository.findByTenantId(tenantId)
                                .map(TenantSubscription::getPlan)
                                .map(plan -> plan.getName() == null ? "" : plan.getName().trim().toUpperCase())
                                .map(planName -> planName.equals("FREE")
                                        || planName.equals("STANDARD")
                                        || planName.equals("MONTHLY_DEMO")
                                        || planName.equals("MONTHLY_LITE")
                                        || planName.equals("YEARLY_LITE")
                                        || planName.equals("MONTHLY_BASIC"))
                                .orElse(false)
                ));
        return Boolean.TRUE.equals(result);
    }

    private void validateItemTypeAllowed(ItemType itemType) {
        if (!appConfigurationService.isItemTypeEnabled(itemType)) {
            throw new BadRequestException(itemType.name() + " items are disabled in app configuration");
        }

        String planName = currentPlanName();
        if (isFreePlan(planName) && itemType != ItemType.NORMAL) {
            throw new BadRequestException("FREE plan supports NORMAL items only");
        }
        if (isStandardPlan(planName)
                && itemType != ItemType.NORMAL
                && itemType != ItemType.WEIGHT
                && itemType != ItemType.VOLUME
                && itemType != ItemType.SERVICE) {
            throw new BadRequestException("STANDARD plan supports NORMAL, WEIGHT, VOLUME and SERVICE items only");
        }
    }

    private String normalizeAltName(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private boolean resolveKotEnabled(Boolean requestedKotEnabled) {
        if (!Boolean.TRUE.equals(requestedKotEnabled)) {
            return false;
        }
        if (!appConfigurationService.isKotEnabled()) {
            throw new BadRequestException("KOT is disabled in app configuration");
        }
        return true;
    }

    private ItemOverheadCostMode normalizeOverheadCostMode(ItemType itemType, ItemOverheadCostMode mode) {
        if (itemType != ItemType.RECIPE) {
            return ItemOverheadCostMode.NONE;
        }
        return mode == null ? ItemOverheadCostMode.NONE : mode;
    }

    private BigDecimal normalizeOverheadCostValue(ItemType itemType, ItemOverheadCostMode mode, BigDecimal value) {
        if (itemType != ItemType.RECIPE || mode == null || mode == ItemOverheadCostMode.NONE) {
            return BigDecimal.ZERO;
        }
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Recipe overhead value must be greater than zero");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String currentPlanName() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            return "";
        }
        // tenant_subscriptions lives in pos_master — switch context before the transaction opens.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        String plan = TenantContext.callWith("MASTER",
                () -> tx.execute(status ->
                        tenantSubscriptionRepository.findByTenantId(tenantId)
                                .map(TenantSubscription::getPlan)
                                .map(p -> p.getName() == null ? "" : p.getName().trim().toUpperCase())
                                .orElse("")
                ));
        return plan == null ? "" : plan;
    }

    private boolean isFreePlan(String planName) {
        return "FREE".equals(planName) || "MONTHLY_DEMO".equals(planName);
    }

    private boolean isStandardPlan(String planName) {
        return "STANDARD".equals(planName)
                || "MONTHLY_LITE".equals(planName)
                || "YEARLY_LITE".equals(planName)
                || "MONTHLY_BASIC".equals(planName);
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

    private void syncProcessingOutputs(Item item, List<StockProcessingOutputLinkRequest> outputRequests) {
        if (!item.isStockProcessingEnabled()) {
            stockProcessingOutputLinkRepository.deleteBySourceItemId(item.getId());
            stockProcessingOutputLinkRepository.flush();
            return;
        }

        if (!isStockTrackedItem(item)) {
            throw new BadRequestException("Only normal, weight, or volume stock items can be used as stock processing sources");
        }

        if (outputRequests == null) {
            return;
        }

        stockProcessingOutputLinkRepository.deleteBySourceItemId(item.getId());
        stockProcessingOutputLinkRepository.flush();
        if (outputRequests.isEmpty()) {
            return;
        }

        Set<Long> distinctOutputIds = new HashSet<>();
        List<StockProcessingOutputLink> outputLinks = new ArrayList<>();

        for (StockProcessingOutputLinkRequest outputRequest : outputRequests) {
            if (outputRequest.getOutputItemId() == null) {
                throw new BadRequestException("Processing output item is required");
            }
            if (!distinctOutputIds.add(outputRequest.getOutputItemId())) {
                throw new BadRequestException("Duplicate processing output item found");
            }
            if (outputRequest.getOutputItemId().equals(item.getId())) {
                throw new BadRequestException("Processing source item cannot be used as its own output");
            }

            Item outputItem = itemRepository.findById(outputRequest.getOutputItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Processing output item not found: " + outputRequest.getOutputItemId()));

            if (!outputItem.isActive()) {
                throw new BadRequestException("Processing output item is inactive: " + outputItem.getName());
            }
            if (!isStockTrackedItem(outputItem)) {
                throw new BadRequestException("Processing outputs must be normal, weight, or volume stock items");
            }

            int defaultQuantity = QuantityConversionUtil.normalizeQuantity(
                    outputItem,
                    outputRequest.getDefaultQty(),
                    QuantityConversionUtil.primaryDisplayUnit(outputItem)
            );

            outputLinks.add(StockProcessingOutputLink.builder()
                    .sourceItemId(item.getId())
                    .outputItemId(outputItem.getId())
                    .defaultQuantity(defaultQuantity)
                    .defaultSellingPrice(outputRequest.getDefaultSellingPrice())
                    .waste(Boolean.TRUE.equals(outputRequest.getWaste()))
                    .build());
        }

        stockProcessingOutputLinkRepository.saveAll(outputLinks);
    }

    private List<StockProcessingOutputLinkResponse> mapProcessingOutputResponses(List<StockProcessingOutputLink> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }

        Map<Long, Item> outputMap = itemRepository.findAllById(
                        links.stream()
                                .map(StockProcessingOutputLink::getOutputItemId)
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(Item::getId, output -> output));

        return links.stream()
                .map(link -> {
                    Item output = outputMap.get(link.getOutputItemId());
                    if (output == null) {
                        throw new ResourceNotFoundException("Processing output item not found: " + link.getOutputItemId());
                    }
                    BigDecimal effectiveSellingPrice = link.getDefaultSellingPrice() != null
                            ? link.getDefaultSellingPrice()
                            : output.getSellingPrice();
                    return StockProcessingOutputLinkResponse.builder()
                            .outputItemId(output.getId())
                            .outputBarcode(output.getBarcode())
                            .outputName(output.getName())
                            .itemType(output.getItemType())
                            .defaultUnit(output.getDefaultUnit())
                            .defaultQty(QuantityConversionUtil.toDisplayQuantity(output, link.getDefaultQuantity()))
                            .defaultQtyUnit(QuantityConversionUtil.primaryDisplayUnit(output))
                            .defaultSellingPrice(effectiveSellingPrice)
                            .waste(link.isWaste())
                            .build();
                })
                .toList();
    }

    private boolean isStockTrackedItem(Item item) {
        return item.getItemType() == ItemType.NORMAL
                || item.getItemType() == ItemType.WEIGHT
                || item.getItemType() == ItemType.VOLUME;
    }

    public String generateFiveDigitBarcode() {
        return generateUniqueBarcode(new HashSet<>());
    }

    private String generateUniqueBarcode(Set<String> reservedBarcodes) {
        String newBarcode;
        Random random = new Random();
        do {
            int randomNumber = 10000 + random.nextInt(90000);
            newBarcode = String.valueOf(randomNumber);
        } while (reservedBarcodes.contains(newBarcode) || itemRepository.existsByBarcode(newBarcode));
        reservedBarcodes.add(newBarcode);
        return newBarcode;
    }

    public List<ItemResponse> getRecentlyAddedItems(int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id"));

        return itemRepository.findAll(pageable).stream()
                .map(item -> mapToResponse(item, null, List.of()))
                .toList();
    }
}
