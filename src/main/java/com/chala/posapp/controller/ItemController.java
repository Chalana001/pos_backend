package com.chala.posapp.controller;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemDeleteCheckResponse;
import com.chala.posapp.dto.item.ItemImportCommitRequest;
import com.chala.posapp.dto.item.ItemImportResponse;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.dto.item.ItemUpdateRequest;
import com.chala.posapp.service.ItemExcelImportService;
import com.chala.posapp.service.ItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
@Validated
public class ItemController {

    private final ItemService itemService;
    private final ItemExcelImportService itemExcelImportService;

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
        return ResponseEntity.ok(itemService.bulkCreate(requestList));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ItemImportResponse> importItems(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(itemExcelImportService.importItems(file));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ItemImportResponse> previewItems(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(itemExcelImportService.previewItems(file));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/import/commit")
    public ResponseEntity<ItemImportResponse> commitImportedItems(@Valid @RequestBody ItemImportCommitRequest request) {
        return ResponseEntity.ok(itemExcelImportService.commitRows(request.getRows()));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping(value = "/import/recipe-ingredients", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ItemImportResponse> importRecipeIngredients(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(itemExcelImportService.importRecipeIngredients(file));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping(value = "/import/recipe-ingredients/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ItemImportResponse> previewRecipeIngredients(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(itemExcelImportService.previewRecipeIngredients(file));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/import/recipe-ingredients/commit")
    public ResponseEntity<ItemImportResponse> commitRecipeIngredients(@Valid @RequestBody ItemImportCommitRequest request) {
        return ResponseEntity.ok(itemExcelImportService.commitRecipeIngredientRows(request.getRows()));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping(value = "/import/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] file = itemExcelImportService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"items-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping(value = "/import/recipe-ingredients/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadRecipeIngredientsTemplate() {
        byte[] file = itemExcelImportService.generateRecipeIngredientsTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recipe-ingredients-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    public ResponseEntity<Page<ItemResponse>> getAllItems(
            @RequestParam(value = "search", required = false, defaultValue = "") String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "subCategoryId", required = false) Long subCategoryId,
            @RequestParam(value = "itemType", required = false, defaultValue = "ALL") String itemType,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "kotEnabled", required = false) Boolean kotEnabled,
            @RequestParam(value = "priceField", required = false, defaultValue = "SELLING") String priceField,
            @RequestParam(value = "priceOperator", required = false, defaultValue = "ALL") String priceOperator,
            @RequestParam(value = "priceAmount", required = false) BigDecimal priceAmount
    ) {
        return ResponseEntity.ok(itemService.getAllItems(
                search,
                page,
                size,
                categoryId,
                subCategoryId,
                itemType,
                active,
                kotEnabled,
                priceField,
                priceOperator,
                priceAmount
        ));
    }

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

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/search")
    public ResponseEntity<List<ItemResponse>> search(
            @RequestParam(name = "name") String name,
            @RequestParam(name = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.searchByName(name, branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/searchForPurchase")
    public ResponseEntity<List<ItemResponse>> searchForPurchase(
            @RequestParam(name = "name") String name,
            @RequestParam(name = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.searchForPurchase(name, branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/searchForPos")
    public ResponseEntity<List<ItemResponse>> searchForPos(
            @RequestParam(name = "name") String name,
            @RequestParam(name = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(itemService.searchForPos(name, branchId));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<ItemResponse>> getRecentItems(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(itemService.getRecentlyAddedItems(limit));
    }

    @GetMapping("/search-print")
    public ResponseEntity<List<ItemResponse>> searchItemsForPrint(@RequestParam(name = "query") String query) {
        return ResponseEntity.ok(itemService.searchForBarcodePrint(query));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody ItemUpdateRequest request) {
        return ResponseEntity.ok(itemService.updateItem(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/{id}/delete-check")
    public ResponseEntity<ItemDeleteCheckResponse> deleteCheck(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(itemService.checkDelete(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable(name = "id") Long id) {
        itemService.deactivateItem(id);
        return ResponseEntity.ok("Item deactivated");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteLegacy(@PathVariable(name = "id") Long id) {
        ItemDeleteCheckResponse deleteCheck = itemService.checkDelete(id);
        if (deleteCheck.isCanDelete()) {
            itemService.deleteItem(id);
            return ResponseEntity.ok("Item deleted");
        }
        itemService.deactivateItem(id);
        return ResponseEntity.ok("Item deactivated");
    }

}
