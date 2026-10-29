package com.chala.posapp.service;

import com.chala.posapp.dto.CategoryDto;
import com.chala.posapp.dto.CategoryRequest;
import com.chala.posapp.dto.CategoryUsageDto;
import com.chala.posapp.dto.SubCategoryDto;
import com.chala.posapp.dto.SubCategoryRequest;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private static final String SINGLE_CATEGORY_PARENT_NAME = "General";

    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final ItemRepository itemRepository;
    private final AppConfigurationService appConfigurationService;

    public CategoryService(CategoryRepository categoryRepository, SubCategoryRepository subCategoryRepository,
                           ItemRepository itemRepository, AppConfigurationService appConfigurationService) {
        this.categoryRepository = categoryRepository;
        this.subCategoryRepository = subCategoryRepository;
        this.itemRepository = itemRepository;
        this.appConfigurationService = appConfigurationService;
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

    @Transactional
    public List<SubCategoryDto> getSingleCategories() {
        return subCategoryRepository.findByCategoryId(getSingleCategoryParent().getId()).stream()
                .map(this::mapToSubCategoryDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public SubCategoryDto createSingleCategory(CategoryRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Category name is required");
        }

        String name = request.getName().trim();
        if (subCategoryRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new AlreadyExistsException("Category name already exists");
        }

        SubCategory subCategory = new SubCategory();
        subCategory.setName(name);
        subCategory.setCategory(getSingleCategoryParent());
        return mapToSubCategoryDto(subCategoryRepository.save(subCategory));
    }

    @Transactional
    public CategoryDto getSingleCategoryParentDto() {
        return mapToCategoryDto(getSingleCategoryParent());
    }

    /**
     * Every category with its sub-categories and the number of items linked to each.
     *
     * <p>One grouped count query feeds the whole tree, so the management screen costs two queries
     * regardless of how many categories the shop has.
     */
    @Transactional(readOnly = true)
    public List<CategoryUsageDto> getCategoryUsage() {
        Map<Long, Long> countsBySubCategory = new HashMap<>();
        for (Object[] row : itemRepository.countItemsPerSubCategory()) {
            if (row[0] != null) {
                countsBySubCategory.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }

        return categoryRepository.findAll().stream()
                .map(category -> {
                    List<CategoryUsageDto.SubCategoryUsageDto> subs = subCategoryRepository
                            .findByCategoryId(category.getId()).stream()
                            .map(sub -> CategoryUsageDto.SubCategoryUsageDto.builder()
                                    .id(sub.getId())
                                    .name(sub.getName())
                                    .itemCount(countsBySubCategory.getOrDefault(sub.getId(), 0L))
                                    .build())
                            .collect(Collectors.toList());
                    long total = subs.stream().mapToLong(CategoryUsageDto.SubCategoryUsageDto::getItemCount).sum();
                    return CategoryUsageDto.builder()
                            .id(category.getId())
                            .name(category.getName())
                            .itemCount(total)
                            .subCategories(subs)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public CategoryDto updateCategory(Long categoryId, CategoryRequest request) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + categoryId));
        assertNotSingleCategoryParent(category, "renamed");

        String name = requireName(request.getName());
        if (!name.equalsIgnoreCase(category.getName()) && categoryRepository.existsByName(name)) {
            throw new AlreadyExistsException("Category name already exists");
        }

        category.setName(name);
        return mapToCategoryDto(categoryRepository.save(category));
    }

    /**
     * Deletes a main category only when nothing is linked to it or its sub-categories.
     *
     * <p>The sub-categories go with it through the existing cascade, which is only safe because
     * the item count covers the whole subtree, an item always links to a sub-category, so a
     * non-zero total on any child blocks the delete.
     */
    @Transactional
    public void deleteCategory(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + categoryId));
        assertNotSingleCategoryParent(category, "deleted");

        long linkedItems = subCategoryRepository.findByCategoryId(categoryId).stream()
                .mapToLong(sub -> countItems(sub.getId()))
                .sum();
        if (linkedItems > 0) {
            throw new ConflictException("This category is linked to " + linkedItems
                    + (linkedItems == 1 ? " item" : " items")
                    + ". Reassign or delete those items first.");
        }

        categoryRepository.delete(category);
    }

    @Transactional
    public SubCategoryDto updateSubCategory(Long subCategoryId, CategoryRequest request) {
        SubCategory subCategory = subCategoryRepository.findById(subCategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-category not found with ID: " + subCategoryId));

        String name = requireName(request.getName());
        if (!name.equalsIgnoreCase(subCategory.getName())
                && subCategoryRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new AlreadyExistsException("Category name already exists");
        }

        subCategory.setName(name);
        return mapToSubCategoryDto(subCategoryRepository.save(subCategory));
    }

    @Transactional
    public void deleteSubCategory(Long subCategoryId) {
        SubCategory subCategory = subCategoryRepository.findById(subCategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-category not found with ID: " + subCategoryId));

        long linkedItems = countItems(subCategoryId);
        if (linkedItems > 0) {
            throw new ConflictException("This category is linked to " + linkedItems
                    + (linkedItems == 1 ? " item" : " items")
                    + ". Reassign or delete those items first.");
        }

        subCategoryRepository.delete(subCategory);
    }

    private long countItems(Long subCategoryId) {
        return subCategoryId == null ? 0L : itemRepository.countBySubCategoryId(subCategoryId);
    }

    private String requireName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new BadRequestException("Category name is required");
        }
        return rawName.trim();
    }

    /**
     * The single-category parent is found by name, so renaming or deleting it would silently
     * spawn a second "General" and orphan every flat category the shop already has. The guard
     * only bites while the shop actually runs in single-category mode; a MAIN_AND_SUB shop is
     * free to use the word "General" for an ordinary category.
     */
    private void assertNotSingleCategoryParent(Category category, String verb) {
        if (!SINGLE_CATEGORY_PARENT_NAME.equalsIgnoreCase(category.getName())) {
            return;
        }
        if (appConfigurationService.getCategoryMode() != CategoryMode.SINGLE_CATEGORY) {
            return;
        }
        throw new BadRequestException("The \"" + SINGLE_CATEGORY_PARENT_NAME
                + "\" category belongs to single-category mode and cannot be " + verb + ".");
    }

    private Category getSingleCategoryParent() {
        return categoryRepository.findByNameIgnoreCase(SINGLE_CATEGORY_PARENT_NAME)
                .orElseGet(() -> {
                    Category category = new Category();
                    category.setName(SINGLE_CATEGORY_PARENT_NAME);
                    return categoryRepository.save(category);
                });
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
