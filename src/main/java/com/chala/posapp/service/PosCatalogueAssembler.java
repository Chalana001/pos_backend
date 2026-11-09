package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemIngredientResponse;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ProcessingOutputValue;
import com.chala.posapp.dto.item.StockProcessingOutputLinkResponse;
import com.chala.posapp.dto.stock.ItemStockAggregate;
import com.chala.posapp.dto.stock.ItemStockBatch;
import com.chala.posapp.dto.stock.StockBatchResponse;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the POS catalogue from tenant-local bulk snapshots without invoking
 * ItemService's per-item repository mapper.
 *
 * <p>All JPA entities are consumed and converted to response DTOs inside one
 * read-only tenant transaction. The request snapshot and every bulk result are
 * method-local, so concurrent tenants/branches cannot share request state.</p>
 */
@Service
@RequiredArgsConstructor
public class PosCatalogueAssembler {

    private static final ConversionService CONVERSION_SERVICE = DefaultConversionService.getSharedInstance();

    private final ItemRequestSnapshotResolver snapshotResolver;
    private final PosCatalogueCandidateReader candidateReader;
    private final ServiceBranchAssignmentReader serviceAssignmentReader;
    private final StockBatchRepository stockBatchRepository;
    private final RecipeIngredientReader recipeIngredientReader;
    private final ProcessingOutputReader processingOutputReader;

    /** Returns the complete eligible item catalogue, without imposing item ordering. */
    @Transactional(readOnly = true)
    public List<ItemResponse> assemble(Long branchId) {
        ItemRequestSnapshot snapshot = snapshotResolver.resolve(branchId);
        List<Item> candidates = candidateReader.loadCandidates();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Item> eligible = candidates.stream()
                .filter(item -> item.isActive() && item.isPosVisible())
                .filter(item -> snapshot.isItemTypeEnabled(item.getItemType()))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }

        List<Long> serviceIds = eligible.stream()
                .filter(item -> item.getItemType() == ItemType.SERVICE)
                .map(Item::getId)
                .toList();
        ServiceBranchAssignments serviceAssignments = serviceIds.isEmpty()
                ? ServiceBranchAssignments.empty()
                : serviceAssignmentReader.loadFor(serviceIds);

        List<Item> serviceEligible = eligible.stream()
                .filter(item -> item.getItemType() != ItemType.SERVICE
                        || serviceAssignments.isEligibleForBranch(item.getId(), branchId))
                .toList();
        if (serviceEligible.isEmpty()) {
            return List.of();
        }

        List<Item> stockTracked = serviceEligible.stream()
                .filter(item -> requiresStockBlock(item, branchId))
                .toList();
        Map<Long, ItemStockAggregate> aggregates = loadAggregates(stockTracked, branchId);
        List<Item> stockEligible = serviceEligible.stream()
                .filter(item -> !requiresStockBlock(item, branchId)
                        || aggregates.containsKey(item.getId()))
                .toList();
        if (stockEligible.isEmpty()) {
            return List.of();
        }

        List<Item> stockScopedEligible = stockEligible.stream()
                .filter(item -> branchId == null || !requiresStockBlock(item, branchId)
                        || aggregateFor(aggregates, item.getId()).batchCount() > 0)
                .toList();
        if (stockScopedEligible.isEmpty()) {
            return List.of();
        }

        List<Item> positiveBatchItems = branchId == null ? List.of()
                : stockScopedEligible.stream()
                        .filter(item -> requiresStockBlock(item, branchId))
                        .toList();
        Map<Long, List<StockBatchResponse>> batchesByItem = loadPositiveBatches(positiveBatchItems, branchId);

        List<Long> recipeIds = stockScopedEligible.stream()
                .filter(item -> item.getItemType() == ItemType.RECIPE)
                .map(Item::getId)
                .toList();
        RecipeIngredientResult recipeIngredients = recipeIds.isEmpty()
                ? RecipeIngredientResult.empty()
                : recipeIngredientReader.loadForRecipes(recipeIds);

        List<Long> processingSourceIds = stockScopedEligible.stream()
                .filter(Item::isStockProcessingEnabled)
                .map(Item::getId)
                .toList();
        ProcessingOutputResult processingOutputs = processingSourceIds.isEmpty()
                ? ProcessingOutputResult.empty()
                : processingOutputReader.loadForSources(processingSourceIds);

        List<ItemResponse> responses = new ArrayList<>(stockScopedEligible.size());
        for (Item item : stockScopedEligible) {
            ItemStockAggregate aggregate = requiresStockBlock(item, branchId)
                    ? aggregateFor(aggregates, item.getId())
                    : null;
            Integer availableBaseQty = aggregate == null ? null : toLegacyInteger(aggregate.totalQuantity());
            boolean hasNegativeStock = aggregate != null && aggregate.negativeBatchCount() > 0;
            List<Long> assignedBranchIds = item.getItemType() == ItemType.SERVICE
                    ? serviceAssignments.branchIdsFor(item.getId())
                    : List.of();
            List<ItemIngredientResponse> ingredients = item.getItemType() == ItemType.RECIPE
                    ? recipeIngredients.ingredientsFor(item.getId()).stream()
                            .map(value -> value.toResponse())
                            .toList()
                    : List.of();
            List<StockProcessingOutputLinkResponse> outputs = item.isStockProcessingEnabled()
                    ? processingOutputs.outputsFor(item.getId()).stream()
                            .map(ProcessingOutputValue::toResponse)
                            .toList()
                    : List.of();

            responses.add(map(item, availableBaseQty, hasNegativeStock,
                    batchesByItem.getOrDefault(item.getId(), List.of()), assignedBranchIds, ingredients, outputs,
                    snapshot));
        }
        return responses;
    }

    private Map<Long, ItemStockAggregate> loadAggregates(List<Item> items, Long branchId) {
        if (branchId == null || items.isEmpty()) {
            return Map.of();
        }
        List<Long> itemIds = items.stream().map(Item::getId).toList();
        List<ItemStockAggregate> rows = branchId == 0L
                ? stockBatchRepository.aggregateStockAcrossBranches(itemIds)
                : stockBatchRepository.aggregateStockForBranch(itemIds, branchId);
        return rows.stream().collect(Collectors.toMap(ItemStockAggregate::itemId, Function.identity()));
    }

    private Map<Long, List<StockBatchResponse>> loadPositiveBatches(List<Item> items, Long branchId) {
        if (branchId == null || items.isEmpty()) {
            return Map.of();
        }
        List<Long> itemIds = items.stream().map(Item::getId).toList();
        List<ItemStockBatch> rows = branchId == 0L
                ? stockBatchRepository.findAvailableBatchesAcrossBranches(itemIds)
                : stockBatchRepository.findAvailableBatchesForBranch(itemIds, branchId);
        Map<Long, Item> itemsById = items.stream().collect(Collectors.toMap(Item::getId, Function.identity()));
        Map<Long, List<StockBatchResponse>> grouped = new HashMap<>();
        for (ItemStockBatch row : rows) {
            Item item = itemsById.get(row.itemId());
            int quantity = row.quantity() == null ? 0 : row.quantity();
            grouped.computeIfAbsent(row.itemId(), ignored -> new ArrayList<>())
                    .add(new StockBatchResponse(
                            row.batchId(), row.sellingPrice(), quantity,
                            QuantityConversionUtil.toDisplayQuantity(item, quantity),
                            item.getDefaultUnit(), row.expireDate()));
        }
        return grouped;
    }

    private ItemResponse map(
            Item item,
            Integer availableBaseQty,
            boolean hasNegativeStock,
            List<StockBatchResponse> batches,
            List<Long> branchIds,
            List<ItemIngredientResponse> ingredients,
            List<StockProcessingOutputLinkResponse> processingOutputs,
            ItemRequestSnapshot snapshot) {
        SubCategory subCategory = item.getSubCategory();
        Category category = subCategory == null ? null : subCategory.getCategory();
        return ItemResponse.builder()
                .id(item.getId())
                .barcode(item.getBarcode())
                .name(item.getName())
                .altName(item.getAltName())
                .subCategoryId(subCategory == null ? null : subCategory.getId())
                .subCategoryName(subCategory == null ? "N/A" : subCategory.getName())
                .categoryId(category == null ? null : category.getId())
                .categoryName(category == null ? "N/A" : category.getName())
                .costPrice(item.getCostPrice())
                .sellingPrice(item.getSellingPrice())
                .availableQty(availableBaseQty == null ? null : QuantityConversionUtil.toDisplayQuantity(item, availableBaseQty))
                .availableBaseQty(availableBaseQty)
                .hasNegativeStock(hasNegativeStock)
                .reorderLevel(QuantityConversionUtil.toDisplayQuantity(item.getItemType(), item.getDefaultUnit(), item.getReorderLevel()))
                .reorderLevelBaseQty(item.getReorderLevel())
                .itemType(item.getItemType())
                .defaultUnit(item.getDefaultUnit())
                .imageUrl(item.getImageUrl())
                .kotEnabled(snapshot.isKotEnabled() && item.isKotEnabled())
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
                .labelExpiry(null)
                .build();
    }

    private boolean requiresStockBlock(Item item, Long branchId) {
        return branchId != null
                && item.getItemType() != ItemType.RECIPE
                && item.getItemType() != ItemType.SERVICE;
    }

    private ItemStockAggregate aggregateFor(Map<Long, ItemStockAggregate> aggregates, Long itemId) {
        return aggregates.get(itemId);
    }

    private Integer toLegacyInteger(Long totalQuantity) {
        return CONVERSION_SERVICE.convert(totalQuantity, Integer.class);
    }
}
