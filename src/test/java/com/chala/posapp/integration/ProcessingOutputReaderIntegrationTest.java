package com.chala.posapp.integration;

import com.chala.posapp.dto.item.ProcessingOutputValue;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.StockProcessingOutputLink;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockProcessingOutputLinkRepository;
import com.chala.posapp.service.ProcessingOutputReader;
import com.chala.posapp.service.ProcessingOutputResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 verification for the bulk processing-output reader. Repository rows are
 * seeded directly for isolated semantics and the equivalence test also checks
 * the live ItemResponse mapping. MySQL physical tenant isolation is covered
 * separately by ProcessingOutputMysqlIntegrationTest.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProcessingOutputReaderIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private StockProcessingOutputLinkRepository linkRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ProcessingOutputReader reader;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("multiple sources group multiple outputs and shared output metadata correctly")
    void multipleSourcesAndSharedOutput() {
        Item shared = newItem("Shared output", "OUT-SHARED", ItemType.NORMAL, MeasurementUnit.PCS, "11.00");
        Item weight = newItem("Weight output", "OUT-WEIGHT", ItemType.WEIGHT, MeasurementUnit.KG, "22.00");
        Item sourceA = newItem("Source A", "SRC-A", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item sourceB = newItem("Source B", "SRC-B", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(sourceA, shared, 2000, null, false);
        saveLink(sourceA, weight, 1500, new BigDecimal("7.25"), true);
        saveLink(sourceB, shared, 3000, null, false);

        ProcessingOutputResult result = reader.loadForSources(List.of(sourceA.getId(), sourceB.getId()));

        assertThat(result.outputsFor(sourceA.getId())).hasSize(2);
        assertThat(result.outputsFor(sourceB.getId())).hasSize(1);
        assertThat(ids(result.outputsFor(sourceA.getId()))).containsExactly(shared.getId(), weight.getId());
        assertThat(ids(result.outputsFor(sourceB.getId()))).containsExactly(shared.getId());
        assertThat(find(result.outputsFor(sourceA.getId()), shared.getId()).defaultQty())
                .isEqualByComparingTo("2");
        assertThat(find(result.outputsFor(sourceB.getId()), shared.getId()).defaultQty())
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("output metadata, conversion, price override/fallback and waste semantics match legacy fields")
    void metadataConversionPriceAndWaste() {
        Item normal = newItem("Normal output", "OUT-NORMAL", ItemType.NORMAL, MeasurementUnit.PCS, "19.50");
        Item weight = newItem("Weight output", "OUT-WEIGHT", ItemType.WEIGHT, MeasurementUnit.KG, "21.00");
        Item volume = newItem("Volume output", "OUT-VOLUME", ItemType.VOLUME, MeasurementUnit.L, "31.00");
        Item source = newItem("Processor", "SRC-META", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(source, normal, 3000, new BigDecimal("8.75"), true);
        saveLink(source, weight, 1500, null, false);
        saveLink(source, volume, 2500, null, false);

        List<ProcessingOutputValue> values = reader.loadForSources(List.of(source.getId()))
                .outputsFor(source.getId());
        ProcessingOutputValue normalValue = find(values, normal.getId());
        ProcessingOutputValue weightValue = find(values, weight.getId());
        ProcessingOutputValue volumeValue = find(values, volume.getId());

        assertThat(normalValue.outputBarcode()).isEqualTo("OUT-NORMAL");
        assertThat(normalValue.outputName()).isEqualTo("Normal output");
        assertThat(normalValue.itemType()).isEqualTo(ItemType.NORMAL);
        assertThat(normalValue.defaultUnit()).isEqualTo(MeasurementUnit.PCS);
        assertThat(normalValue.defaultQty()).isEqualByComparingTo("3");
        assertThat(normalValue.defaultQtyUnit()).isEqualTo(MeasurementUnit.PCS);
        assertThat(normalValue.defaultSellingPrice()).isEqualByComparingTo("8.75");
        assertThat(normalValue.waste()).isTrue();
        assertThat(weightValue.defaultQty()).isEqualByComparingTo("1.5");
        assertThat(weightValue.defaultQtyUnit()).isEqualTo(MeasurementUnit.KG);
        assertThat(weightValue.defaultSellingPrice()).isEqualByComparingTo("21.00");
        assertThat(weightValue.waste()).isFalse();
        assertThat(volumeValue.defaultQty()).isEqualByComparingTo("2.5");
        assertThat(volumeValue.defaultQtyUnit()).isEqualTo(MeasurementUnit.L);
    }

    @Test
    @DisplayName("relationship id ascending order is preserved independently for interleaved source rows")
    void perSourceOrderMatchesLegacy() {
        Item outputA = newItem("Output A", "OUT-A", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item outputB = newItem("Output B", "OUT-B", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item outputC = newItem("Output C", "OUT-C", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item sourceA = newItem("Source A", "SRC-ORDER-A", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item sourceB = newItem("Source B", "SRC-ORDER-B", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(sourceA, outputA, 1, null, false);
        saveLink(sourceB, outputC, 1, null, false);
        saveLink(sourceA, outputB, 1, null, false);

        ProcessingOutputResult result = reader.loadForSources(List.of(sourceA.getId(), sourceB.getId()));

        assertThat(ids(result.outputsFor(sourceA.getId()))).containsExactly(outputA.getId(), outputB.getId());
        assertThat(ids(result.outputsFor(sourceB.getId()))).containsExactly(outputC.getId());
        assertThat(ids(result.outputsFor(sourceA.getId())))
                .containsExactlyElementsOf(linkRepository.findBySourceItemIdOrderByIdAsc(sourceA.getId())
                        .stream().map(StockProcessingOutputLink::getOutputItemId).toList());
    }

    @Test
    @DisplayName("disabled source has no outputs in final ItemResponse semantics; reader boundary expects eligible ids")
    void disabledProcessingSourceIsEmptyInFinalResponse() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("proc-disabled"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        long subCategoryId = seedSubCategory();
        JsonNode sourceJson = postJson("/items", fixture.tenantId(), token, """
                {"barcode":"%s","name":"Disabled processor","subCategoryId":%d,
                 "costPrice":1,"sellingPrice":2,"reorderLevel":0,"stockProcessingEnabled":false}
                """.formatted(uniqueKey("PDS"), subCategoryId));
        JsonNode outputJson = postJson("/items", fixture.tenantId(), token, """
                {"barcode":"%s","name":"Output","subCategoryId":%d,
                 "costPrice":1,"sellingPrice":2,"reorderLevel":0}
                """.formatted(uniqueKey("PDO"), subCategoryId));
        long sourceId = sourceJson.path("id").asLong();
        long outputId = outputJson.path("id").asLong();
        linkRepository.save(StockProcessingOutputLink.builder()
                .sourceItemId(sourceId).outputItemId(outputId).defaultQuantity(1000).build());

        JsonNode response = getJson("/items/" + sourceId, fixture.tenantId(), token);
        assertThat(response.path("processingOutputs")).isEmpty();
        // Reader does not perform an extra source-item query; caller must pass
        // only enabled source ids. If called directly, it maps supplied rows.
        assertThat(reader.loadForSources(List.of(sourceId)).outputsFor(sourceId)).hasSize(1);
    }

    @Test
    @DisplayName("empty sources, no links and rowless source use immutable empty lists and bounded queries")
    void emptyAndNoLinks() {
        Item source = newItem("No outputs", "SRC-EMPTY", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Statistics statistics = statistics();

        statistics.clear();
        assertThat(reader.loadForSources(List.of()).isEmpty()).isTrue();
        assertThat(statistics.getQueryExecutionCount()).isZero();

        statistics.clear();
        ProcessingOutputResult result = reader.loadForSources(List.of(source.getId()));
        assertThat(result.outputsFor(source.getId())).isEmpty();
        assertThat(result.outputsFor(source.getId())).isNotNull();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("missing and soft-deleted output items preserve legacy 404 behavior")
    void missingAndSoftDeletedOutputsFail() {
        Item source = newItem("Processor", "SRC-MISSING", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        long missingId = 987_654_321L;
        linkRepository.save(StockProcessingOutputLink.builder()
                .sourceItemId(source.getId()).outputItemId(missingId).defaultQuantity(1000).build());
        assertThatThrownBy(() -> reader.loadForSources(List.of(source.getId())))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Processing output item not found: " + missingId);

        Item deleted = newItem("Deleted output", "OUT-DELETED", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item source2 = newItem("Processor 2", "SRC-DELETED", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(source2, deleted, 1000, null, false);
        deleted.setActive(false);
        deleted.setDeletedAt(LocalDateTime.now());
        itemRepository.saveAndFlush(deleted);
        assertThatThrownBy(() -> reader.loadForSources(List.of(source2.getId())))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Processing output item not found: " + deleted.getId());
    }

    @Test
    @DisplayName("duplicate relationship pair is rejected by the database unique constraint")
    void duplicateRelationshipRejected() {
        Item source = newItem("Processor", "SRC-DUP", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item output = newItem("Output", "OUT-DUP", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(source, output, 1000, null, false);

        assertThatThrownBy(() -> linkRepository.saveAndFlush(StockProcessingOutputLink.builder()
                .sourceItemId(source.getId()).outputItemId(output.getId()).defaultQuantity(2000).build()))
                .isInstanceOf(DataAccessException.class);
        assertThat(reader.loadForSources(List.of(source.getId())).outputsFor(source.getId())).hasSize(1);
    }

    @Test
    @DisplayName("large source collection binds and groups correctly")
    void largeSourceCollection() {
        Item output = newItem("Output", "OUT-LARGE", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item source = newItem("Real processor", "SRC-LARGE", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(source, output, 1000, null, false);
        List<Long> ids = new ArrayList<>();
        for (long id = 20_000_000L; id < 20_006_000L; id++) {
            ids.add(id);
        }
        ids.add(source.getId());

        ProcessingOutputResult result = reader.loadForSources(ids);
        assertThat(result.valuesBySource()).containsOnlyKeys(source.getId());
        assertThat(ids(result.outputsFor(source.getId()))).containsExactly(output.getId());
    }

    @Test
    @DisplayName("reader equals live ItemResponse processing-output fields for API-created source/output items")
    void legacyEquivalenceAgainstLiveItemResponse() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("proc-equivalence"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        long subCategoryId = seedSubCategory();
        JsonNode output = postJson("/items", fixture.tenantId(), token, """
                {"barcode":"%s","name":"Live output","subCategoryId":%d,
                 "costPrice":10,"sellingPrice":31,"reorderLevel":0,"itemType":"WEIGHT","defaultUnit":"KG"}
                """.formatted(uniqueKey("PEO"), subCategoryId));
        JsonNode source = postJson("/items", fixture.tenantId(), token, """
                {"barcode":"%s","name":"Live processor","subCategoryId":%d,
                 "costPrice":10,"sellingPrice":31,"reorderLevel":0,"stockProcessingEnabled":true}
                """.formatted(uniqueKey("PES"), subCategoryId));
        long outputId = output.path("id").asLong();
        long sourceId = source.path("id").asLong();
        StockProcessingOutputLink link = linkRepository.save(StockProcessingOutputLink.builder()
                .sourceItemId(sourceId).outputItemId(outputId).defaultQuantity(1500)
                .defaultSellingPrice(new BigDecimal("7.25")).waste(true).build());

        JsonNode legacy = getJson("/items/" + sourceId, fixture.tenantId(), token)
                .path("processingOutputs").get(0);
        ProcessingOutputValue bulk = reader.loadForSources(List.of(sourceId)).outputsFor(sourceId).get(0);

        assertThat(link.getId()).isNotNull();
        assertThat(bulk.outputItemId()).isEqualTo(legacy.path("outputItemId").asLong());
        assertThat(bulk.outputBarcode()).isEqualTo(legacy.path("outputBarcode").asText());
        assertThat(bulk.outputName()).isEqualTo(legacy.path("outputName").asText());
        assertThat(bulk.itemType().name()).isEqualTo(legacy.path("itemType").asText());
        assertThat(bulk.defaultUnit().name()).isEqualTo(legacy.path("defaultUnit").asText());
        assertThat(bulk.defaultQty()).isEqualByComparingTo(legacy.path("defaultQty").decimalValue());
        assertThat(bulk.defaultQtyUnit().name()).isEqualTo(legacy.path("defaultQtyUnit").asText());
        assertThat(bulk.defaultSellingPrice()).isEqualByComparingTo(legacy.path("defaultSellingPrice").decimalValue());
        assertThat(bulk.waste()).isEqualTo(legacy.path("waste").asBoolean());
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private List<Long> ids(List<ProcessingOutputValue> values) {
        return values.stream().map(ProcessingOutputValue::outputItemId).toList();
    }

    private ProcessingOutputValue find(List<ProcessingOutputValue> values, long id) {
        return values.stream().filter(value -> value.outputItemId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("missing output " + id));
    }

    private Item newItem(String name, String barcode, ItemType type, MeasurementUnit unit, String price) {
        return itemRepository.save(Item.builder().name(name).barcode(barcode)
                .costPrice(new BigDecimal("1.00")).sellingPrice(new BigDecimal(price)).reorderLevel(0)
                .itemType(type).defaultUnit(unit).build());
    }

    private StockProcessingOutputLink saveLink(Item source, Item output, int quantity,
                                                BigDecimal price, boolean waste) {
        return linkRepository.save(StockProcessingOutputLink.builder()
                .sourceItemId(source.getId()).outputItemId(output.getId())
                .defaultQuantity(quantity).defaultSellingPrice(price).waste(waste).build());
    }

    private long seedSubCategory() {
        Category category = new Category();
        category.setName(uniqueKey("proc-category"));
        category = categoryRepository.save(category);
        com.chala.posapp.entity.SubCategory subCategory = new com.chala.posapp.entity.SubCategory();
        subCategory.setName(uniqueKey("proc-subcategory"));
        subCategory.setCategory(category);
        return subCategoryRepository.save(subCategory).getId();
    }
}
