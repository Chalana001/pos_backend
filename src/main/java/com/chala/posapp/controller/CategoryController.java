package com.chala.posapp.controller;

import com.chala.posapp.dto.CategoryDto;
import com.chala.posapp.dto.CategoryRequest;
import com.chala.posapp.dto.SubCategoryDto;
import com.chala.posapp.dto.SubCategoryRequest;
import com.chala.posapp.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping()
    public ResponseEntity<List<CategoryDto>> getAllCategories() {
        List<CategoryDto> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(categories);
    }

    @PostMapping()
    public ResponseEntity<CategoryDto> createCategory(@RequestBody CategoryRequest request) {
        CategoryDto createdCategory = categoryService.createCategory(request);
        return ResponseEntity.ok(createdCategory);
    }

    @GetMapping("/{categoryId}/sub-categories")
    public ResponseEntity<List<SubCategoryDto>> getSubCategories(@PathVariable("categoryId") Long categoryId) {
        List<SubCategoryDto> subCategories = categoryService.getSubCategoriesByCategoryId(categoryId);
        return ResponseEntity.ok(subCategories);
    }

    @PostMapping("/sub-categories")
    public ResponseEntity<SubCategoryDto> createSubCategory(@RequestBody SubCategoryRequest request) {
        SubCategoryDto createdSubCategory = categoryService.createSubCategory(request);
        return ResponseEntity.ok(createdSubCategory);
    }
}