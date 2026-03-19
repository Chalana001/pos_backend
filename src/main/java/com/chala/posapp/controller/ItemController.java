package com.chala.posapp.controller;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ItemUpdateRequest;
import com.chala.posapp.dto.item.ItemWithStockResponse;
import com.chala.posapp.service.ItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
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

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemCreateRequest request) {
        return ResponseEntity.ok(itemService.createItem(request));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<ItemResponse>> bulkCreate(
            @RequestBody @NotEmpty(message = "List cannot be empty")
            List<@Valid ItemCreateRequest> requestList) {
        List<ItemResponse> responses = itemService.bulkCreate(requestList);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/with-stock")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    public ResponseEntity<List<ItemWithStockResponse>> listWithStock(
            @RequestParam(name = "branchId", required = false) Long branchId  
    ) {
        return ResponseEntity.ok(itemService.itemsWithStock(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> get(@PathVariable(name = "id") Long id) {  
        System.out.println("called");
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

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/search")
    public ResponseEntity<List<ItemResponse>> search(
            @RequestParam(name = "name") String name,  
            @RequestParam(name = "branchId", required = false) Long branchId  
    ) {
        return ResponseEntity.ok(itemService.searchByName(name, branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<ItemResponse>> list(@RequestParam(name = "activeOnly", required = false) Boolean activeOnly) {  
        System.out.println("called3333333");
        return ResponseEntity.ok(itemService.listAll(activeOnly));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(@PathVariable(name = "id") Long id,  
                                               @Valid @RequestBody ItemUpdateRequest request) {
        return ResponseEntity.ok(itemService.updateItem(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(@PathVariable(name = "id") Long id) {  
        itemService.deactivateItem(id);
        return ResponseEntity.ok("Item deactivated");
    }

    @GetMapping("/recent")
    public ResponseEntity<List<ItemResponse>> getRecentItems(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<ItemResponse> recentItems = itemService.getRecentlyAddedItems(limit);
        return ResponseEntity.ok(recentItems);
    }

    @GetMapping("/search-print")
    public ResponseEntity<List<ItemResponse>> searchItemsForPrint(@RequestParam(name= "query") String query) {

        List<ItemResponse> items = itemService.searchForBarcodePrint(query);
        return ResponseEntity.ok(items);
    }
}