package com.chala.posapp.service;

import com.chala.posapp.dto.CategoryDto;
import com.chala.posapp.dto.CategoryRequest;
import com.chala.posapp.dto.CategoryUsageDto;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Category management: renaming and deleting the two-level tree.
 *
 * <p>The delete guard is the part worth pinning down. Items only ever link to a sub-category,
 * so a main category is "in use" when the sum across its children is non-zero, and the cascade
 * that removes its sub-categories is only safe once that sum is zero.
 */
class CategoryServiceTest {

    private CategoryRepository categoryRepository;
    private SubCategoryRepository subCategoryRepository;
    private ItemRepository itemRepository;
    private AppConfigurationService appConfigurationService;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        subCategoryRepository = mock(SubCategoryRepository.class);
        itemRepository = mock(ItemRepository.class);
        appConfigurationService = mock(AppConfigurationService.class);
        service = new CategoryService(categoryRepository, subCategoryRepository, itemRepository,
                appConfigurationService);
        when(categoryRepository.save(any(Category.class))).thenAnswer(call -> call.getArgument(0));
        when(subCategoryRepository.save(any(SubCategory.class))).thenAnswer(call -> call.getArgument(0));
        // Most cases are ordinary two-level shops; the single-category cases opt in below.
        when(appConfigurationService.getCategoryMode()).thenReturn(CategoryMode.MAIN_AND_SUB);
    }

    private Category category(Long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private SubCategory subCategory(Long id, String name, Category parent) {
        SubCategory sub = new SubCategory();
        sub.setId(id);
        sub.setName(name);
        sub.setCategory(parent);
        return sub;
    }

    private CategoryRequest request(String name) {
        CategoryRequest request = new CategoryRequest();
        request.setName(name);
        return request;
    }

    @Test
    @DisplayName("renaming trims the name and stores it")
    void renameTrims() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(category(3L, "Beverages")));

        CategoryDto updated = service.updateCategory(3L, request("  Hot Drinks  "));

        assertThat(updated.getName()).isEqualTo("Hot Drinks");
    }

    @Test
    @DisplayName("a rename that lands on another category's name is refused")
    void renameRejectsDuplicate() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(category(3L, "Beverages")));
        when(categoryRepository.existsByName("Snacks")).thenReturn(true);

        assertThatThrownBy(() -> service.updateCategory(3L, request("Snacks")))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @DisplayName("re-saving a category under its own name is not a clash with itself")
    void renameToSameNameIsAllowed() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(category(3L, "Beverages")));

        assertThat(service.updateCategory(3L, request("Beverages")).getName()).isEqualTo("Beverages");
        verify(categoryRepository, never()).existsByName(any());
    }

    @Test
    @DisplayName("changing only the letter case of the same name is allowed")
    void renameChangesCaseOnly() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(category(3L, "beverages")));

        assertThat(service.updateCategory(3L, request("Beverages")).getName()).isEqualTo("Beverages");
    }

    @Test
    @DisplayName("a blank name is refused before it reaches the database")
    void renameRejectsBlank() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(category(3L, "Beverages")));

        assertThatThrownBy(() -> service.updateCategory(3L, request("   ")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("renaming a category that no longer exists is a not-found, not a silent no-op")
    void renameMissingCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCategory(99L, request("Anything")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the single-category parent cannot be renamed: it is looked up by name")
    void singleCategoryParentCannotBeRenamed() {
        // Renaming "General" would make the next lookup create a second one and orphan
        // every flat category the shop already has.
        when(appConfigurationService.getCategoryMode()).thenReturn(CategoryMode.SINGLE_CATEGORY);
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(category(7L, "General")));

        assertThatThrownBy(() -> service.updateCategory(7L, request("Groceries")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("single-category mode");

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    @DisplayName("the single-category parent cannot be deleted")
    void singleCategoryParentCannotBeDeleted() {
        when(appConfigurationService.getCategoryMode()).thenReturn(CategoryMode.SINGLE_CATEGORY);
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(category(7L, "General")));

        assertThatThrownBy(() -> service.deleteCategory(7L))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("single-category mode");

        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    @DisplayName("a two-level shop may use the name General for an ordinary category")
    void generalNameIsFreeOutsideSingleMode() {
        // The guard keys on the name, so it has to check the mode first or a shop that
        // simply likes the word would be unable to rename or delete its own category.
        Category general = category(8L, "General");
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(general));
        when(subCategoryRepository.findByCategoryId(8L)).thenReturn(List.of());

        assertThat(service.updateCategory(8L, request("General Goods")).getName()).isEqualTo("General Goods");

        service.deleteCategory(8L);
        verify(categoryRepository).delete(general);
    }

    @Test
    @DisplayName("an unused category is deleted")
    void deleteUnusedCategory() {
        Category drinks = category(3L, "Beverages");
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(drinks));
        when(subCategoryRepository.findByCategoryId(3L))
                .thenReturn(List.of(subCategory(11L, "Tea", drinks), subCategory(12L, "Coffee", drinks)));
        when(itemRepository.countBySubCategoryId(any())).thenReturn(0L);

        service.deleteCategory(3L);

        verify(categoryRepository).delete(drinks);
    }

    @Test
    @DisplayName("a main category is in use when any of its sub-categories holds an item")
    void deleteBlockedByGrandchildItems() {
        // Items never point at a main category directly, so the count has to walk the children.
        Category drinks = category(3L, "Beverages");
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(drinks));
        when(subCategoryRepository.findByCategoryId(3L))
                .thenReturn(List.of(subCategory(11L, "Tea", drinks), subCategory(12L, "Coffee", drinks)));
        when(itemRepository.countBySubCategoryId(11L)).thenReturn(0L);
        when(itemRepository.countBySubCategoryId(12L)).thenReturn(14L);

        assertThatThrownBy(() -> service.deleteCategory(3L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("14 items");

        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    @DisplayName("the block message counts items across every child, not just the first")
    void deleteBlockedCountsEveryChild() {
        Category drinks = category(3L, "Beverages");
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(drinks));
        when(subCategoryRepository.findByCategoryId(3L))
                .thenReturn(List.of(subCategory(11L, "Tea", drinks), subCategory(12L, "Coffee", drinks)));
        when(itemRepository.countBySubCategoryId(11L)).thenReturn(2L);
        when(itemRepository.countBySubCategoryId(12L)).thenReturn(3L);

        assertThatThrownBy(() -> service.deleteCategory(3L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("5 items");
    }

    @Test
    @DisplayName("a single linked item is described in the singular")
    void deleteMessageIsSingularForOne() {
        Category drinks = category(3L, "Beverages");
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(drinks));
        when(subCategoryRepository.findByCategoryId(3L)).thenReturn(List.of(subCategory(11L, "Tea", drinks)));
        when(itemRepository.countBySubCategoryId(11L)).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteCategory(3L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("1 item.");
    }

    @Test
    @DisplayName("an unused sub-category is deleted")
    void deleteUnusedSubCategory() {
        Category drinks = category(3L, "Beverages");
        SubCategory tea = subCategory(11L, "Tea", drinks);
        when(subCategoryRepository.findById(11L)).thenReturn(Optional.of(tea));

        service.deleteSubCategory(11L);

        verify(subCategoryRepository).delete(tea);
    }

    @Test
    @DisplayName("a sub-category holding items is not deleted")
    void deleteInUseSubCategory() {
        Category drinks = category(3L, "Beverages");
        when(subCategoryRepository.findById(11L)).thenReturn(Optional.of(subCategory(11L, "Tea", drinks)));
        when(itemRepository.countBySubCategoryId(11L)).thenReturn(4L);

        assertThatThrownBy(() -> service.deleteSubCategory(11L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("4 items");

        verify(subCategoryRepository, never()).delete(any(SubCategory.class));
    }

    @Test
    @DisplayName("renaming a sub-category keeps it under the same parent")
    void renameSubCategory() {
        Category drinks = category(3L, "Beverages");
        SubCategory tea = subCategory(11L, "Tea", drinks);
        when(subCategoryRepository.findById(11L)).thenReturn(Optional.of(tea));

        assertThat(service.updateSubCategory(11L, request("  Green Tea ")).getName()).isEqualTo("Green Tea");
        assertThat(tea.getCategory()).isSameAs(drinks);
    }

    @Test
    @DisplayName("a sub-category cannot take another sub-category's name, in any parent")
    void renameSubCategoryRejectsDuplicate() {
        Category drinks = category(3L, "Beverages");
        when(subCategoryRepository.findById(11L)).thenReturn(Optional.of(subCategory(11L, "Tea", drinks)));
        when(subCategoryRepository.findByNameIgnoreCase("Coffee")).thenReturn(Optional.of(new SubCategory()));

        assertThatThrownBy(() -> service.updateSubCategory(11L, request("Coffee")))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @DisplayName("the usage tree sums child counts onto the parent")
    void usageTotalsPerCategory() {
        Category drinks = category(3L, "Beverages");
        Category snacks = category(4L, "Snacks");
        when(categoryRepository.findAll()).thenReturn(List.of(drinks, snacks));
        when(subCategoryRepository.findByCategoryId(3L))
                .thenReturn(List.of(subCategory(11L, "Tea", drinks), subCategory(12L, "Coffee", drinks)));
        when(subCategoryRepository.findByCategoryId(4L))
                .thenReturn(List.of(subCategory(21L, "Chips", snacks)));
        when(itemRepository.countItemsPerSubCategory()).thenReturn(List.of(
                new Object[] { 11L, 5L },
                new Object[] { 12L, 7L },
                new Object[] { 21L, 2L },
                // Items with no sub-category arrive as a null group; they belong to no row.
                new Object[] { null, 9L }
        ));

        List<CategoryUsageDto> usage = service.getCategoryUsage();

        assertThat(usage).hasSize(2);
        assertThat(usage.get(0).getName()).isEqualTo("Beverages");
        assertThat(usage.get(0).getItemCount()).isEqualTo(12L);
        assertThat(usage.get(0).getSubCategories())
                .extracting(CategoryUsageDto.SubCategoryUsageDto::getItemCount)
                .containsExactly(5L, 7L);
        assertThat(usage.get(1).getItemCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a category with no sub-categories reports zero rather than null")
    void usageEmptyCategory() {
        when(categoryRepository.findAll()).thenReturn(List.of(category(4L, "Snacks")));
        when(subCategoryRepository.findByCategoryId(4L)).thenReturn(List.of());
        when(itemRepository.countItemsPerSubCategory()).thenReturn(List.of());

        CategoryUsageDto usage = service.getCategoryUsage().get(0);

        assertThat(usage.getItemCount()).isZero();
        assertThat(usage.getSubCategories()).isEmpty();
    }
}