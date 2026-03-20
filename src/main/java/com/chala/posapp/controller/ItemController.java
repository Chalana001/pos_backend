package com.chala.posapp.controller;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ItemUpdateRequest;
import com.chala.posapp.service.ItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
@Validated
public class ItemController {

    private final ItemService itemService;

    // --- CREATE ---
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemCreateRequest request) {
        return ResponseEntity.ok(itemService.createItem(request));
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<List<ItemResponse>> bulkCreate(
            @RequestBody @NotEmpty(message = "List cannot be empty")
            List<@Valid ItemCreateRequest> requestList) {
        List<ItemResponse> responses = itemService.bulkCreate(requestList);
        return ResponseEntity.ok(responses);
    }

    // --- READ (PAGINATED LIST) 🟢 අලුත් එක ---
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    public ResponseEntity<Page<ItemResponse>> getAllItems(
            @RequestParam(value = "search", required = false, defaultValue = "") String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(itemService.getAllItems(search, page, size));
    }

    // --- READ (ALL ITEMS - WITHOUT PAGINATION) 🔴 Path එක වෙනස් කළා ---
//    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
//    @GetMapping("/all")
//    public ResponseEntity<List<ItemResponse>> listAll(
//            @RequestParam(name = "activeOnly", required = false) Boolean activeOnly) {
//        return ResponseEntity.ok(itemService.listAll(activeOnly));
//    }

    // --- READ (SINGLE ITEM) ---
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> get(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(itemService.getItem(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/barcode/{barcode}")
    public ResponseEntity<ItemResponse> getByBarcode(
            @PathVariable(name = "barcode") String barcode,
            @RequestParam(name = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.getByBarcode(barcode, branchId));
    }

    // --- SEARCH METHODS ---
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/search")
    public ResponseEntity<List<ItemResponse>> search(
            @RequestParam(name = "name") String name,
            @RequestParam(name = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.searchByName(name, branchId));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<ItemResponse>> getRecentItems(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(itemService.getRecentlyAddedItems(limit));
    }

    @GetMapping("/search-print")
    public ResponseEntity<List<ItemResponse>> searchItemsForPrint(@RequestParam(name= "query") String query) {
        return ResponseEntity.ok(itemService.searchForBarcodePrint(query));
    }

    // --- UPDATE ---
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody ItemUpdateRequest request) {
        return ResponseEntity.ok(itemService.updateItem(id, request));
    }

    // --- DELETE / DEACTIVATE ---
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(@PathVariable(name = "id") Long id) {
        itemService.deactivateItem(id);
        return ResponseEntity.ok("Item deactivated");
    }
}