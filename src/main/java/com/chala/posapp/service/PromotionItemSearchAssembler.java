package com.chala.posapp.service;

import com.chala.posapp.dto.item.PromotionItemSearchPage;
import com.chala.posapp.dto.item.PromotionItemSearchResponse;
import com.chala.posapp.dto.stock.ItemStockAggregate;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Narrow, bulk-backed item reader for promotion search and category import.
 * Legacy POS search remains unchanged because promotion eligibility is its own contract.
 */
@Service
@RequiredArgsConstructor
public class PromotionItemSearchAssembler {

    public static final int DEFAULT_PAGE_SIZE = 500;
    public static final int MAX_PAGE_SIZE = 500;

    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final ItemRequestSnapshotResolver snapshotResolver;
    private final ServiceBranchAssignmentReader serviceAssignmentReader;
    private final SecurityUtils securityUtils;

    @Transactional(readOnly = true)
    public PromotionItemSearchPage search(
            String requestedName,
            Long requestedBranchId,
            Long categoryId,
            Long subCategoryId,
            Long cursor,
            Integer requestedPageSize) {
        validateRequest(requestedName, categoryId, subCategoryId, cursor, requestedPageSize);
        Long branchId = resolveBranchId(requestedBranchId);
        int pageSize = normalizePageSize(requestedPageSize);
        String name = requestedName == null ? "" : requestedName.trim();

        if (name.isEmpty() && (categoryId != null || subCategoryId != null)) {
            return searchCategory(name, branchId, categoryId, subCategoryId, cursor, pageSize);
        }

        ItemRequestSnapshot snapshot = snapshotResolver.resolve(branchId);
        List<Item> candidates = name.length() >= 3
                ? itemRepository.searchForPosFt(name + "*")
                : itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(name, name);
        List<Item> filtered = candidates.stream()
                .filter(item -> matchesCategory(item, categoryId, subCategoryId))
                .toList();
        return assemble(filtered, branchId, snapshot, null, false);
    }

    private PromotionItemSearchPage searchCategory(
            String name,
            Long branchId,
            Long categoryId,
            Long subCategoryId,
            Long cursor,
            int pageSize) {
        if (!name.isEmpty()) {
            throw new BadRequestException("Category import cannot combine a search name with a category filter");
        }
        long afterId = cursor == null ? 0L : cursor;
        List<Item> candidates = categoryId != null
                ? itemRepository.findPromotionCandidatesByCategoryAfterId(
                        categoryId, afterId, PageRequest.of(0, pageSize + 1))
                : itemRepository.findPromotionCandidatesBySubCategoryAfterId(
                        subCategoryId, afterId, PageRequest.of(0, pageSize + 1));
        boolean hasMore = candidates.size() > pageSize;
        List<Item> page = candidates.subList(0, Math.min(pageSize, candidates.size()));
        if (page.isEmpty()) {
            return new PromotionItemSearchPage(List.of(), null, false);
        }
        ItemRequestSnapshot snapshot = snapshotResolver.resolve(branchId);
        Long nextCursor = page.get(page.size() - 1).getId();
        return assemble(page, branchId, snapshot, nextCursor, hasMore);
    }

    private PromotionItemSearchPage assemble(
            List<Item> candidates,
            Long branchId,
            ItemRequestSnapshot snapshot,
            Long nextCursor,
            boolean hasMore) {
        List<Item> eligible = candidates.stream()
                .filter(item -> item.isActive() && item.isPosVisible())
                .filter(item -> snapshot.isItemTypeEnabled(item.getItemType()))
                .toList();
        if (eligible.isEmpty()) {
            return new PromotionItemSearchPage(List.of(), nextCursor, hasMore);
        }

        List<Long> serviceIds = branchId != null && branchId != 0L
                ? eligible.stream().filter(item -> item.getItemType() == ItemType.SERVICE).map(Item::getId).toList()
                : List.of();
        ServiceBranchAssignments assignments = serviceBranchAssignmentReader(serviceIds);
        List<Item> serviceEligible = eligible.stream()
                .filter(item -> item.getItemType() != ItemType.SERVICE
                        || branchId == null
                        || branchId == 0L
                        || assignments.isEligibleForBranch(item.getId(), branchId))
                .toList();

        List<Item> stockTracked = serviceEligible.stream()
                .filter(item -> requiresStockHistory(item, branchId))
                .toList();
        Map<Long, ItemStockAggregate> aggregates = loadAggregates(stockTracked, branchId);
        List<PromotionItemSearchResponse> result = serviceEligible.stream()
                .filter(item -> !requiresStockHistory(item, branchId)
                        || aggregates.containsKey(item.getId()))
                .map(item -> toResponse(item))
                .toList();
        return new PromotionItemSearchPage(result, nextCursor, hasMore);
    }

    private ServiceBranchAssignments serviceBranchAssignmentReader(Collection<Long> serviceIds) {
        return serviceAssignmentReader.loadFor(serviceIds);
    }

    private Map<Long, ItemStockAggregate> loadAggregates(List<Item> items, Long branchId) {
        if (branchId == null || items.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = items.stream().map(Item::getId).toList();
        List<ItemStockAggregate> rows = branchId == 0L
                ? stockBatchRepository.aggregateStockAcrossBranches(ids)
                : stockBatchRepository.aggregateStockForBranch(ids, branchId);
        return rows.stream().collect(Collectors.toMap(ItemStockAggregate::itemId, Function.identity()));
    }

    private boolean requiresStockHistory(Item item, Long branchId) {
        return branchId != null && item.getItemType() != ItemType.SERVICE && item.getItemType() != ItemType.RECIPE;
    }

    private boolean matchesCategory(Item item, Long categoryId, Long subCategoryId) {
        SubCategory subCategory = item.getSubCategory();
        Category category = subCategory == null ? null : subCategory.getCategory();
        return (categoryId == null || category != null && categoryId.equals(category.getId()))
                && (subCategoryId == null || subCategory != null && subCategoryId.equals(subCategory.getId()));
    }

    private PromotionItemSearchResponse toResponse(Item item) {
        SubCategory subCategory = item.getSubCategory();
        Category category = subCategory == null ? null : subCategory.getCategory();
        return new PromotionItemSearchResponse(
                item.getId(), item.getName(), item.getAltName(), item.getBarcode(),
                category == null ? null : category.getId(),
                category == null ? "N/A" : category.getName(),
                subCategory == null ? null : subCategory.getId(),
                subCategory == null ? "N/A" : subCategory.getName(),
                item.getCostPrice(), item.getSellingPrice(), item.getItemType(), item.getDefaultUnit());
    }

    private Long resolveBranchId(Long requestedBranchId) {
        User user = securityUtils.getOptionalCurrentUser();
        if (user != null && (user.getRole() == Role.MANAGER || user.getRole() == Role.CASHIER)) {
            if (user.getBranchId() == null) {
                throw new BadRequestException("User branch not assigned");
            }
            return user.getBranchId();
        }
        return requestedBranchId;
    }

    private int normalizePageSize(Integer requestedPageSize) {
        if (requestedPageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requestedPageSize <= 0) {
            throw new BadRequestException("pageSize must be greater than zero");
        }
        return Math.min(requestedPageSize, MAX_PAGE_SIZE);
    }

    private void validateRequest(String name, Long categoryId, Long subCategoryId, Long cursor, Integer pageSize) {
        if (categoryId != null && subCategoryId != null) {
            throw new BadRequestException("categoryId and subCategoryId cannot be combined");
        }
        if ((name == null || name.isBlank()) && categoryId == null && subCategoryId == null) {
            throw new BadRequestException("name or category filter is required");
        }
        if (cursor != null && (categoryId == null && subCategoryId == null || name != null && !name.isBlank())) {
            throw new BadRequestException("cursor requires a blank-name category filter");
        }
        if (pageSize != null && pageSize <= 0) {
            throw new BadRequestException("pageSize must be greater than zero");
        }
    }
}
