package com.chala.posapp.service;

import com.chala.posapp.dto.item.ProcessingOutputItemData;
import com.chala.posapp.dto.item.ProcessingOutputLinkData;
import com.chala.posapp.dto.item.ProcessingOutputValue;
import com.chala.posapp.dto.item.StockProcessingOutputLinkResponse;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockProcessingOutputLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the processing-output bulk reader. Repository methods are
 * mocked so this class isolates grouping, legacy response semantics and
 * immutable-result behavior; real JPQL and tenant routing are covered by H2
 * and MySQL integration tests.
 */
class ProcessingOutputReaderTest {

    private StockProcessingOutputLinkRepository linkRepository;
    private ItemRepository itemRepository;
    private ProcessingOutputReader reader;

    @BeforeEach
    void setUp() {
        linkRepository = mock(StockProcessingOutputLinkRepository.class);
        itemRepository = mock(ItemRepository.class);
        reader = new ProcessingOutputReader(linkRepository, itemRepository);
    }

    @Test
    @DisplayName("empty input performs no repository calls")
    void emptyInputSkipsRepositories() {
        assertThat(reader.loadForSources(List.of()).isEmpty()).isTrue();
        assertThat(reader.loadForSources(null).isEmpty()).isTrue();
        verifyNoInteractions(linkRepository, itemRepository);
    }

    @Test
    @DisplayName("no relationship rows skip the second item query")
    void noRowsSkipItemLookup() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L)))
                .thenReturn(List.of());

        assertThat(reader.loadForSources(List.of(1L)).isEmpty()).isTrue();
        verifyNoInteractions(itemRepository);
    }

    @Test
    @DisplayName("multiple source items group outputs without cross-source leakage")
    void groupsBySourceId() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L, 2L))).thenReturn(List.of(
                link(1L, 101L, 2, null, false),
                link(2L, 101L, 3, null, true),
                link(1L, 102L, 4, new BigDecimal("8.50"), false)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(101L, 102L))).thenReturn(List.of(
                item(101L, "OUT-1", "Output 1", ItemType.NORMAL, MeasurementUnit.PCS,
                        new BigDecimal("10.00")),
                item(102L, "OUT-2", "Output 2", ItemType.WEIGHT, MeasurementUnit.KG,
                        new BigDecimal("20.00"))));

        ProcessingOutputResult result = reader.loadForSources(List.of(1L, 2L));

        assertThat(result.valuesBySource()).containsOnlyKeys(1L, 2L);
        assertThat(result.outputsFor(1L)).extracting(ProcessingOutputValue::outputItemId)
                .containsExactly(101L, 102L);
        assertThat(result.outputsFor(2L)).extracting(ProcessingOutputValue::outputItemId)
                .containsExactly(101L);
    }

    @Test
    @DisplayName("shared output item is fetched once and remains a separate relationship per source")
    void sharedOutputIsDistinctForLookupButNotRelationship() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L, 2L))).thenReturn(List.of(
                link(1L, 101L, 2000, null, false),
                link(2L, 101L, 3000, new BigDecimal("7.00"), true)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(101L))).thenReturn(List.of(
                item(101L, "OUT-1", "Output 1", ItemType.NORMAL, MeasurementUnit.PCS,
                        new BigDecimal("10.00"))));

        ProcessingOutputResult result = reader.loadForSources(List.of(1L, 2L));

        verify(itemRepository).findProcessingOutputItemDataByIdIn(List.of(101L));
        assertThat(result.outputsFor(1L)).hasSize(1);
        assertThat(result.outputsFor(2L)).hasSize(1);
        assertThat(result.outputsFor(1L).get(0).defaultQty()).isEqualByComparingTo("2");
        assertThat(result.outputsFor(2L).get(0).defaultQty()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("NORMAL quantity uses existing conversion and primary PCS unit")
    void normalConversion() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L))).thenReturn(List.of(
                link(1L, 101L, 3000, null, false)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(101L))).thenReturn(List.of(
                item(101L, "OUT", "Units", ItemType.NORMAL, MeasurementUnit.PCS,
                        new BigDecimal("10.00"))));

        ProcessingOutputValue value = reader.loadForSources(List.of(1L)).outputsFor(1L).get(0);

        assertThat(value.defaultQty()).isEqualByComparingTo("3");
        assertThat(value.defaultQtyUnit()).isEqualTo(MeasurementUnit.PCS);
    }

    @Test
    @DisplayName("WEIGHT and VOLUME quantities use current output item unit semantics")
    void measuredConversions() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L))).thenReturn(List.of(
                link(1L, 101L, 1500, null, false),
                link(1L, 102L, 2500, null, false)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(101L, 102L))).thenReturn(List.of(
                item(101L, "W", "Weight", ItemType.WEIGHT, MeasurementUnit.KG,
                        new BigDecimal("10.00")),
                item(102L, "V", "Volume", ItemType.VOLUME, MeasurementUnit.L,
                        new BigDecimal("10.00"))));

        List<ProcessingOutputValue> values = reader.loadForSources(List.of(1L)).outputsFor(1L);

        assertThat(values.get(0).defaultQty()).isEqualByComparingTo("1.5");
        assertThat(values.get(0).defaultQtyUnit()).isEqualTo(MeasurementUnit.KG);
        assertThat(values.get(1).defaultQty()).isEqualByComparingTo("2.5");
        assertThat(values.get(1).defaultQtyUnit()).isEqualTo(MeasurementUnit.L);
    }

    @Test
    @DisplayName("non-null link price wins and null link price falls back to current output price")
    void priceOverrideAndFallback() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L))).thenReturn(List.of(
                link(1L, 101L, 1000, new BigDecimal("7.25"), false),
                link(1L, 102L, 1000, null, false)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(101L, 102L))).thenReturn(List.of(
                item(101L, "A", "Override", ItemType.NORMAL, MeasurementUnit.PCS,
                        new BigDecimal("99.00")),
                item(102L, "B", "Fallback", ItemType.NORMAL, MeasurementUnit.PCS,
                        new BigDecimal("12.50"))));

        List<ProcessingOutputValue> values = reader.loadForSources(List.of(1L)).outputsFor(1L);

        assertThat(values.get(0).defaultSellingPrice()).isEqualByComparingTo("7.25");
        assertThat(values.get(1).defaultSellingPrice()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("true waste is true and false/null waste is false")
    void wasteSemantics() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L))).thenReturn(List.of(
                link(1L, 101L, 1, null, true),
                link(1L, 102L, 1, null, false),
                new ProcessingOutputLinkData(3L, 1L, 103L, 1, null, null)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(101L, 102L, 103L))).thenReturn(List.of(
                item(101L, "A", "A", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE),
                item(102L, "B", "B", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE),
                item(103L, "C", "C", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE)));

        List<ProcessingOutputValue> values = reader.loadForSources(List.of(1L)).outputsFor(1L);

        assertThat(values).extracting(ProcessingOutputValue::waste).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("repository order is preserved per source even when source rows are interleaved")
    void preservesRelationshipOrder() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L, 2L))).thenReturn(List.of(
                link(20L, 202L, 1, null, false),
                link(10L, 101L, 1, null, false),
                link(21L, 203L, 1, null, false),
                link(11L, 102L, 1, null, false)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(202L, 101L, 203L, 102L))).thenReturn(List.of(
                item(202L, "202", "202", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE),
                item(101L, "101", "101", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE),
                item(203L, "203", "203", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE),
                item(102L, "102", "102", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE)));

        ProcessingOutputResult result = reader.loadForSources(List.of(1L, 2L));

        assertThat(result.outputsFor(2L)).extracting(ProcessingOutputValue::outputItemId)
                .containsExactly(202L, 203L);
        assertThat(result.outputsFor(1L)).extracting(ProcessingOutputValue::outputItemId)
                .containsExactly(101L, 102L);
    }

    @Test
    @DisplayName("missing output item throws the legacy exception")
    void missingOutputThrows() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L))).thenReturn(List.of(
                link(1L, 404L, 1, null, false)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(404L))).thenReturn(List.of());

        assertThatThrownBy(() -> reader.loadForSources(List.of(1L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Processing output item not found: 404");
    }

    @Test
    @DisplayName("result map and lists are immutable")
    void resultIsImmutable() {
        when(linkRepository.findProcessingOutputLinksForSources(List.of(1L))).thenReturn(List.of(
                link(1L, 101L, 1, null, false)));
        when(itemRepository.findProcessingOutputItemDataByIdIn(List.of(101L))).thenReturn(List.of(
                item(101L, "A", "A", ItemType.NORMAL, MeasurementUnit.PCS, BigDecimal.ONE)));

        ProcessingOutputResult result = reader.loadForSources(List.of(1L));

        assertThatThrownBy(() -> result.valuesBySource().put(2L, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.outputsFor(1L).add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.outputsFor(1L)).hasSize(1);
    }

    @Test
    @DisplayName("toResponse preserves every public DTO field")
    void responseContract() {
        ProcessingOutputValue value = new ProcessingOutputValue(
                101L, "OUT", "Output", ItemType.WEIGHT, MeasurementUnit.KG,
                new BigDecimal("1.5"), MeasurementUnit.KG, new BigDecimal("7.25"), true);

        StockProcessingOutputLinkResponse response = value.toResponse();

        assertThat(response.getOutputItemId()).isEqualTo(101L);
        assertThat(response.getOutputBarcode()).isEqualTo("OUT");
        assertThat(response.getOutputName()).isEqualTo("Output");
        assertThat(response.getItemType()).isEqualTo(ItemType.WEIGHT);
        assertThat(response.getDefaultUnit()).isEqualTo(MeasurementUnit.KG);
        assertThat(response.getDefaultQty()).isEqualByComparingTo("1.5");
        assertThat(response.getDefaultQtyUnit()).isEqualTo(MeasurementUnit.KG);
        assertThat(response.getDefaultSellingPrice()).isEqualByComparingTo("7.25");
        assertThat(response.isWaste()).isTrue();
    }

    private ProcessingOutputLinkData link(long linkId, long outputId, int quantity,
                                          BigDecimal price, boolean waste) {
        return new ProcessingOutputLinkData(linkId, sourceFor(linkId), outputId,
                quantity, price, waste);
    }

    private long sourceFor(long linkId) {
        return linkId == 2L || linkId >= 20L ? 2L : 1L;
    }

    private ProcessingOutputItemData item(long id, String barcode, String name,
                                          ItemType type, MeasurementUnit unit,
                                          BigDecimal sellingPrice) {
        return new ProcessingOutputItemData(id, barcode, name, type, unit, sellingPrice);
    }
}
