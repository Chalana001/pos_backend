package com.chala.posapp.service;

import com.chala.posapp.dto.CategoryDto;
import com.chala.posapp.dto.CategoryRequest;
import com.chala.posapp.dto.SubCategoryDto;
import com.chala.posapp.dto.SubCategoryRequest;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;

    public CategoryService(CategoryRepository categoryRepository, SubCategoryRepository subCategoryRepository) {
        this.categoryRepository = categoryRepository;
        this.subCategoryRepository = subCategoryRepository;
    }

    // 1. Get All Categoriess
    @Transactional(readOnly = true)
    public List<CategoryDto> getAllCategories() {
        return categoryRepository.findAll().stream()
                .map(this::mapToCategoryDto)
                .collect(Collectors.toList());
    }

    // 2. Create Category
    @Transactional
    public CategoryDto createCategory(CategoryRequest request) {
        // Check if category already exists (optional but recommended)
        if (categoryRepository.existsByName(request.getName())) {
            throw new AlreadyExistsException("Category name already exists");
        }

        Category category = new Category();
        category.setName(request.getName());

        Category savedCategory = categoryRepository.save(category);
        return mapToCategoryDto(savedCategory);
    }

    // 3. Get Sub-Categories by Category ID
    @Transactional(readOnly = true)
    public List<SubCategoryDto> getSubCategoriesByCategoryId(Long categoryId) {
        return subCategoryRepository.findByCategoryId(categoryId).stream()
                .map(this::mapToSubCategoryDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public SubCategoryDto createSubCategory(SubCategoryRequest request) {
        // Fetch the parent category
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + request.getCategoryId()));

        SubCategory subCategory = new SubCategory();
        subCategory.setName(request.getName());
        subCategory.setCategory(category);

        SubCategory savedSubCategory = subCategoryRepository.save(subCategory);
        return mapToSubCategoryDto(savedSubCategory);
    }

    private CategoryDto mapToCategoryDto(Category category) {
        CategoryDto dto = new CategoryDto();
        dto.setId(category.getId());
        dto.setName(category.getName());
        return dto;
    }

    private SubCategoryDto mapToSubCategoryDto(SubCategory subCategory) {
        SubCategoryDto dto = new SubCategoryDto();
        dto.setId(subCategory.getId());
        dto.setName(subCategory.getName());
        return dto;
    }
}
