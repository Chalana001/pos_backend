package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import com.chala.posapp.service.PosCatalogueCandidateReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 coverage for the tenant-global POS candidate reader. The legacy comparison
 * intentionally calls the old empty-name derived repository method directly, so
 * this suite tests candidate parity without applying ItemService eligibility.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class PosCatalogueCandidateReaderIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private SubCategoryRepository subCategoryRepository;

    @Autowired
    private PosCatalogueCandidateReader reader;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics;

    @BeforeEach
    void clearPersistenceContextAndStatistics() {
        statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();
    }

    @Test
    @DisplayName("candidate ids exactly match the legacy empty-name repository population")
    void candidateParity() {
        Category category = category("Parity category");
        SubCategory subCategory = subCategory("Parity subcategory", category);
        Item normal = item("Normal candidate", "PAR-N", ItemType.NORMAL, true, true, subCategory);
        Item inactive = item("Inactive candidate", "PAR-I", ItemType.NORMAL, false, true, subCategory);
        Item hidden = item("Hidden candidate", "PAR-H", ItemType.NORMAL, true, false, subCategory);
        Item weight = item("Weight candidate", "PAR-W", ItemType.WEIGHT, true, true, subCategory);
        Item volume = item("Volume candidate", "PAR-V", ItemType.VOLUME, true, true, subCategory);
        Item service = item("Service candidate", "PAR-S", ItemType.SERVICE, true, true, subCategory);
        Item recipe = item("Recipe candidate", "PAR-R", ItemType.RECIPE, true, true, subCategory);
        Item noCategory = item("No category candidate", "PAR-X", ItemType.NORMAL, true, true, null);
        Item deleted = item("Deleted candidate", "PAR-D", ItemType.NORMAL, false, true, subCategory);
        deleted.setDeletedAt(LocalDateTime.now());
        itemRepository.saveAndFlush(deleted);
        entityManager.clear();

        Set<Long> legacyIds = itemRepository
                .findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase("", "")
                .stream()
                .map(Item::getId)
                .collect(Collectors.toSet());
        List<Item> candidates = reader.loadCandidates();

        assertThat(candidates).extracting(Item::getId).containsExactlyInAnyOrderElementsOf(legacyIds);
        assertThat(candidates).extracting(Item::getId)
                .containsExactlyInAnyOrder(normal.getId(), inactive.getId(), hidden.getId(), weight.getId(),
                        volume.getId(), service.getId(), recipe.getId(), noCategory.getId());
        assertThat(candidates).noneMatch(item -> item.getId().equals(deleted.getId()));
        assertThat(candidates).extracting(Item::getItemType)
                .containsAll(EnumSet.allOf(ItemType.class));
    }

    @Test
    @DisplayName("category and subcategory chain is available without lazy queries")
    void categoryMappingIsFetched() {
        Category category = category("Category mapping");
        SubCategory subCategory = subCategory("Subcategory mapping", category);
        Item item = item("Mapped item", "CAT-MAP", ItemType.NORMAL, true, true, subCategory);
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        List<Item> candidates = reader.loadCandidates();

        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        Item mapped = candidates.stream().filter(candidate -> candidate.getId().equals(item.getId())).findFirst().orElseThrow();
        assertThat(mapped.getSubCategory().getId()).isEqualTo(subCategory.getId());
        assertThat(mapped.getSubCategory().getName()).isEqualTo("Subcategory mapping");
        assertThat(mapped.getSubCategory().getCategory().getId()).isEqualTo(category.getId());
        assertThat(mapped.getSubCategory().getCategory().getName()).isEqualTo("Category mapping");
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("optional category relationship remains null without an extra query")
    void optionalCategoryRelationship() {
        Item item = item("Uncategorised item", "CAT-NULL", ItemType.NORMAL, true, true, null);
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        Item candidate = reader.loadCandidates().stream()
                .filter(found -> found.getId().equals(item.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(candidate.getSubCategory()).isNull();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("soft-deleted candidates remain excluded by the existing item SQL restriction")
    void softDeleteParity() {
        Item visible = item("Visible soft-delete peer", "DEL-V", ItemType.NORMAL, true, true, null);
        Item deleted = item("Deleted soft-delete peer", "DEL-D", ItemType.NORMAL, false, true, null);
        deleted.setDeletedAt(LocalDateTime.now());
        itemRepository.saveAndFlush(deleted);
        entityManager.clear();

        assertThat(reader.loadCandidates()).extracting(Item::getId).contains(visible.getId()).doesNotContain(deleted.getId());
        assertThat(itemRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase("", ""))
                .extracting(Item::getId).contains(visible.getId()).doesNotContain(deleted.getId());
    }

    @Test
    @DisplayName("empty catalogue returns an empty list")
    void emptyCatalogue() {
        assertThat(reader.loadCandidates()).isEmpty();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("large catalogue is complete and unpaged")
    void largeCatalogue() {
        for (int i = 0; i < 6001; i++) {
            item("Large item " + i, "LARGE-" + i, ItemType.NORMAL, true, true, null);
            if (i % 500 == 499) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        List<Item> candidates = reader.loadCandidates();

        assertThat(candidates).hasSize(6001);
        assertThat(candidates).extracting(Item::getBarcode)
                .contains("LARGE-0", "LARGE-6000");
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    private Category category(String name) {
        Category category = new Category();
        category.setName(name);
        return categoryRepository.saveAndFlush(category);
    }

    private SubCategory subCategory(String name, Category category) {
        SubCategory subCategory = new SubCategory();
        subCategory.setName(name);
        subCategory.setCategory(category);
        return subCategoryRepository.saveAndFlush(subCategory);
    }

    private Item item(String name, String barcode, ItemType type, boolean active, boolean posVisible,
                      SubCategory subCategory) {
        return itemRepository.save(Item.builder()
                .name(name)
                .barcode(barcode)
                .subCategory(subCategory)
                .costPrice(new BigDecimal("1.00"))
                .sellingPrice(new BigDecimal("2.00"))
                .reorderLevel(0)
                .itemType(type)
                .defaultUnit(type == ItemType.WEIGHT ? MeasurementUnit.KG : MeasurementUnit.PCS)
                .active(active)
                .posVisible(posVisible)
                .build());
    }
}
