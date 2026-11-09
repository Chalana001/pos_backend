package com.chala.posapp.service;

import com.chala.posapp.dto.item.PurchaseItemSearchResponse;
import com.chala.posapp.dto.stock.ItemStockAggregate;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assembles the purchase form's narrow item-search result without loading the
 * full POS response graph or issuing stock queries per result row.
 */
@Service
@RequiredArgsConstructor
public class PurchaseSearchAssembler {

    private static final ConversionService CONVERSION_SERVICE = DefaultConversionService.getSharedInstance();

    private final ItemRepository itemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final ItemRequestSnapshotResolver snapshotResolver;
    private final SecurityUtils securityUtils;

    @Transactional(readOnly = true)
    public List<PurchaseItemSearchResponse> search(String name, Long requestedBranchId) {
        Long branchId = resolveBranchId(requestedBranchId);
        String searchTerm = name.trim();
        List<Item> candidates = searchTerm.length() >= 3
                ? itemRepository.searchForPurchaseFt(searchTerm + "*")
                : itemRepository.searchForPurchase(searchTerm);

        ItemRequestSnapshot snapshot = snapshotResolver.resolve(branchId);
        List<Item> eligible = candidates.stream()
                .filter(Item::isActive)
                .filter(item -> isStockTracked(item) && snapshot.isItemTypeEnabled(item.getItemType()))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }

        Map<Long, ItemStockAggregate> stockByItem = loadStock(eligible, branchId);
        return eligible.stream()
                .map(item -> map(item, stockByItem.get(item.getId()), branchId != null))
                .toList();
    }

    private Long resolveBranchId(Long requestedBranchId) {
        User user = securityUtils.getOptionalCurrentUser();
        if (user != null && (user.getRole() == Role.MANAGER || user.getRole() == Role.CASHIER)) {
            if (user.getBranchId() == null) {
                throw new BadRequestException("User branch not assigned");
            }
            // Preserve purchase search behavior: the client cannot choose a
            // different scope for a manager or cashier.
            return user.getBranchId();
        }
        return requestedBranchId;
    }

    private Map<Long, ItemStockAggregate> loadStock(List<Item> items, Long branchId) {
        if (branchId == null) {
            return Map.of();
        }
        List<Long> itemIds = items.stream().map(Item::getId).toList();
        List<ItemStockAggregate> aggregates = branchId == 0L
                ? stockBatchRepository.aggregateStockAcrossBranches(itemIds)
                : stockBatchRepository.aggregateStockForBranch(itemIds, branchId);
        return aggregates.stream().collect(Collectors.toMap(ItemStockAggregate::itemId, Function.identity()));
    }

    private PurchaseItemSearchResponse map(Item item, ItemStockAggregate stock, boolean includeStock) {
        if (!includeStock) {
            return new PurchaseItemSearchResponse(
                    item.getId(), item.getName(), item.getAltName(), item.getBarcode(), item.getItemType(),
                    item.getCostPrice(), item.getSellingPrice(), item.getDefaultUnit(), null, null, false);
        }

        long total = stock == null || stock.totalQuantity() == null ? 0L : stock.totalQuantity();
        // The legacy ItemService path receives an Integer from
        // getTotalQuantityByItemAndScope; keep its narrowing semantics exactly.
        Integer availableBaseQty = CONVERSION_SERVICE.convert(total, Integer.class);
        boolean hasNegativeStock = stock != null && stock.negativeBatchCount() != null
                && stock.negativeBatchCount() > 0;
        return new PurchaseItemSearchResponse(
                item.getId(), item.getName(), item.getAltName(), item.getBarcode(), item.getItemType(),
                item.getCostPrice(), item.getSellingPrice(), item.getDefaultUnit(),
                QuantityConversionUtil.toDisplayQuantity(item, availableBaseQty),
                availableBaseQty, hasNegativeStock);
    }

    private boolean isStockTracked(Item item) {
        return item.getItemType() == ItemType.NORMAL
                || item.getItemType() == ItemType.WEIGHT
                || item.getItemType() == ItemType.VOLUME;
    }
}
