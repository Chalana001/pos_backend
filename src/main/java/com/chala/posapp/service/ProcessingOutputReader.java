package com.chala.posapp.service;

import com.chala.posapp.dto.item.ProcessingOutputItemData;
import com.chala.posapp.dto.item.ProcessingOutputLinkData;
import com.chala.posapp.dto.item.ProcessingOutputValue;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockProcessingOutputLinkRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tenant-local bulk reader for stock-processing output relationships and
 * referenced output items.
 *
 * <p>One invocation performs at most two queries: one ordered relationship
 * projection and one distinct output-item projection. It returns a fresh,
 * immutable snapshot and never stores JPA entities, tenant state, or cache
 * entries. Source eligibility remains with the future assembler: callers pass
 * only source ids whose current Item row has {@code stockProcessingEnabled}
 * true, matching {@code ItemService.mapToResponse}'s existing gate. This
 * keeps the reader at two queries and prevents it from inventing a new item
 * type or source lookup policy.
 *
 * <p>Mapping is intentionally field-for-field with
 * {@code ItemService.mapProcessingOutputResponses}: quantity uses the
 * existing {@code QuantityConversionUtil.toDisplayQuantity(ItemType,
 * MeasurementUnit, Integer)} semantics; the display unit uses
 * {@code QuantityConversionUtil.primaryDisplayUnit(Item)}; a non-null link
 * price wins over current output-item selling price; and waste uses
 * {@code Boolean.TRUE.equals(link.waste())}, making null false exactly like
 * the write workflow's nullable-safe handling. Missing or soft-deleted
 * output items throw the legacy {@code ResourceNotFoundException} rather
 * than being omitted.
 */
@Service
@RequiredArgsConstructor
public class ProcessingOutputReader {

    private final StockProcessingOutputLinkRepository linkRepository;
    private final ItemRepository itemRepository;

    /**
     * Loads all processing outputs for the supplied eligible source ids.
     * Null/empty input returns an immutable empty result without repository
     * calls. A nonempty request with no relationship rows performs only the
     * relationship query; no empty item IN-list query is issued.
     */
    public ProcessingOutputResult loadForSources(Collection<Long> sourceItemIds) {
        if (sourceItemIds == null || sourceItemIds.isEmpty()) {
            return ProcessingOutputResult.empty();
        }

        List<ProcessingOutputLinkData> links =
                linkRepository.findProcessingOutputLinksForSources(sourceItemIds);
        if (links.isEmpty()) {
            return ProcessingOutputResult.empty();
        }

        List<Long> outputItemIds = links.stream()
                .map(ProcessingOutputLinkData::outputItemId)
                .distinct()
                .toList();
        Map<Long, ProcessingOutputItemData> itemsById =
                itemRepository.findProcessingOutputItemDataByIdIn(outputItemIds).stream()
                        .collect(Collectors.toMap(ProcessingOutputItemData::itemId, Function.identity()));

        Map<Long, List<ProcessingOutputValue>> grouped = new LinkedHashMap<>();
        for (ProcessingOutputLinkData link : links) {
            ProcessingOutputItemData output = itemsById.get(link.outputItemId());
            if (output == null) {
                throw new ResourceNotFoundException(
                        "Processing output item not found: " + link.outputItemId());
            }

            grouped.computeIfAbsent(link.sourceItemId(), key -> new ArrayList<>())
                    .add(valueFor(link, output));
        }
        return ProcessingOutputResult.from(grouped);
    }

    /** Mirrors ItemService.mapProcessingOutputResponses without loading Item entities. */
    private ProcessingOutputValue valueFor(
            ProcessingOutputLinkData link,
            ProcessingOutputItemData output) {
        return new ProcessingOutputValue(
                output.itemId(),
                output.barcode(),
                output.name(),
                output.itemType(),
                output.defaultUnit(),
                QuantityConversionUtil.toDisplayQuantity(
                        output.itemType(), output.defaultUnit(), link.defaultQuantity()),
                primaryDisplayUnit(output.itemType(), output.defaultUnit()),
                link.defaultSellingPrice() != null
                        ? link.defaultSellingPrice()
                        : output.sellingPrice(),
                Boolean.TRUE.equals(link.waste()));
    }

    /** Scalar equivalent of QuantityConversionUtil.primaryDisplayUnit(Item). */
    private com.chala.posapp.entity.MeasurementUnit primaryDisplayUnit(
            com.chala.posapp.entity.ItemType itemType,
            com.chala.posapp.entity.MeasurementUnit defaultUnit) {
        if (itemType == com.chala.posapp.entity.ItemType.WEIGHT) {
            return com.chala.posapp.entity.MeasurementUnit.KG;
        }
        if (itemType == com.chala.posapp.entity.ItemType.VOLUME) {
            return com.chala.posapp.entity.MeasurementUnit.L;
        }
        return defaultUnit;
    }
}
