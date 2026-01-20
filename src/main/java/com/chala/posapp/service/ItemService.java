package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.Item;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;

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

}
