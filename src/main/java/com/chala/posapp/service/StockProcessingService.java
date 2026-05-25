package com.chala.posapp.service;

import com.chala.posapp.dto.item.StockProcessingOutputLinkResponse;
import com.chala.posapp.dto.stock.CreateStockProcessingOutputRequest;
import com.chala.posapp.dto.stock.CreateStockProcessingRequest;
import com.chala.posapp.dto.stock.StockBatchResponse;
import com.chala.posapp.dto.stock.StockProcessingOutputResponse;
import com.chala.posapp.dto.stock.StockProcessingResponse;
import com.chala.posapp.dto.stock.StockProcessingSourceResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.StockProcessingOutputLink;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.entity.stock.StockProcessing;
import com.chala.posapp.entity.stock.StockProcessingOutput;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.StockProcessingOutputLinkRepository;
import com.chala.posapp.repository.StockProcessingOutputRepository;
import com.chala.posapp.repository.StockProcessingRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockProcessingService {

    private final StockProcessingRepository stockProcessingRepository;
    private final StockProcessingOutputRepository stockProcessingOutputRepository;
    private final StockProcessingOutputLinkRepository stockProcessingOutputLinkRepository;
    private final StockBatchRepository stockBatchRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private boolean isAdminLike(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
    }

    private Long enforceBranchAccess(Long requestedBranchId) {
        User user = getLoggedUser();
        if (isAdminLike(user)) {
            return requestedBranchId;
        }
        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }
        if (requestedBranchId == null || requestedBranchId == 0L) {
            return user.getBranchId();
        }
        if (!user.getBranchId().equals(requestedBranchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
        return requestedBranchId;
    }

    public List<StockProcessingSourceResponse> listSources(Long branchId) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        List<Item> sources = itemRepository.findByStockProcessingEnabledTrueAndActiveTrueOrderByNameAsc().stream()
                .filter(this::isStockTrackedItem)
                .toList();

        return sources.stream()
                .map(item -> {
                    List<StockBatch> batches = allowedBranchId == null || allowedBranchId == 0L
                            ? stockBatchRepository.findBatchesForScope(null, item.getId())
                            : stockBatchRepository.findBatchesForScope(allowedBranchId, item.getId());
                    int availableBaseQty = batches.stream()
                            .filter(batch -> batch.getQuantity() != null && batch.getQuantity() > 0)
                            .mapToInt(StockBatch::getQuantity)
                            .sum();
                    return StockProcessingSourceResponse.builder()
                            .id(item.getId())
                            .barcode(item.getBarcode())
                            .name(item.getName())
                            .itemType(item.getItemType())
                            .defaultUnit(item.getDefaultUnit())
                            .availableBaseQty(availableBaseQty)
                            .availableQty(QuantityConversionUtil.toDisplayQuantity(item, availableBaseQty))
                            .batches(mapBatches(item, batches))
                            .build();
                })
                .toList();
    }

    public List<StockProcessingOutputLinkResponse> getSourceOutputs(Long sourceItemId) {
        Item sourceItem = itemRepository.findById(sourceItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Source item not found"));
        validateSourceItem(sourceItem);

        List<StockProcessingOutputLink> links = stockProcessingOutputLinkRepository.findBySourceItemIdOrderByIdAsc(sourceItemId);
        Map<Long, Item> outputMap = itemRepository.findAllById(
                        links.stream().map(StockProcessingOutputLink::getOutputItemId).distinct().toList()
                ).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));

        return links.stream()
                .map(link -> {
                    Item output = outputMap.get(link.getOutputItemId());
                    if (output == null) {
                        throw new ResourceNotFoundException("Processing output item not found: " + link.getOutputItemId());
                    }
                    return StockProcessingOutputLinkResponse.builder()
                            .outputItemId(output.getId())
                            .outputBarcode(output.getBarcode())
                            .outputName(output.getName())
                            .itemType(output.getItemType())
                            .defaultUnit(output.getDefaultUnit())
                            .defaultQty(QuantityConversionUtil.toDisplayQuantity(output, link.getDefaultQuantity()))
                            .defaultQtyUnit(QuantityConversionUtil.primaryDisplayUnit(output))
                            .defaultSellingPrice(output.getSellingPrice())
                            .waste(link.isWaste())
                            .build();
                })
                .toList();
    }

    @Transactional
    public StockProcessingResponse create(CreateStockProcessingRequest request) {
        User user = getLoggedUser();
        if (user.getRole() == Role.CASHIER) {
            throw new BadRequestException("Cashier cannot process stock");
        }

        Long branchId = enforceBranchAccess(request.getBranchId());
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        if (!branch.isActive()) {
            throw new BadRequestException("Branch inactive");
        }

        Item sourceItem = itemRepository.findById(request.getSourceItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Source item not found"));
        validateSourceItem(sourceItem);

        StockBatch sourceBatch = stockBatchRepository.findById(request.getSourceBatchId())
                .orElseThrow(() -> new ResourceNotFoundException("Source batch not found"));
        if (!sourceBatch.getBranch().getId().equals(branch.getId())) {
            throw new BadRequestException("Selected source batch does not belong to this branch");
        }
        if (!sourceBatch.getItem().getId().equals(sourceItem.getId())) {
            throw new BadRequestException("Selected source batch does not belong to the source item");
        }

        int sourceBaseQty = QuantityConversionUtil.normalizeQuantity(
                sourceItem,
                request.getSourceQty(),
                request.getSourceQtyUnit() == null ? QuantityConversionUtil.primaryDisplayUnit(sourceItem) : request.getSourceQtyUnit()
        );
        if (sourceBatch.getQuantity() < sourceBaseQty) {
            throw new BadRequestException("Not enough source stock. Available: " + sourceBatch.getQuantity());
        }

        List<StockProcessingOutputLink> links = stockProcessingOutputLinkRepository.findBySourceItemIdOrderByIdAsc(sourceItem.getId());
        if (links.isEmpty()) {
            throw new BadRequestException("No processing output items are linked to this source item");
        }
        Map<Long, StockProcessingOutputLink> linkMap = links.stream()
                .collect(Collectors.toMap(StockProcessingOutputLink::getOutputItemId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Set<Long> requestedOutputIds = request.getOutputs().stream()
                .map(CreateStockProcessingOutputRequest::getOutputItemId)
                .collect(Collectors.toSet());
        if (requestedOutputIds.size() != request.getOutputs().size()) {
            throw new BadRequestException("Duplicate processing output item found");
        }

        List<PreparedOutput> preparedOutputs = new ArrayList<>();
        for (CreateStockProcessingOutputRequest outputRequest : request.getOutputs()) {
            StockProcessingOutputLink link = linkMap.get(outputRequest.getOutputItemId());
            if (link == null) {
                throw new BadRequestException("Output item is not linked to this source item: " + outputRequest.getOutputItemId());
            }
            Item outputItem = itemRepository.findById(outputRequest.getOutputItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Output item not found: " + outputRequest.getOutputItemId()));
            validateOutputItem(sourceItem, outputItem);

            MeasurementUnit outputUnit = outputRequest.getQtyUnit() == null
                    ? QuantityConversionUtil.primaryDisplayUnit(outputItem)
                    : outputRequest.getQtyUnit();
            int outputBaseQty = QuantityConversionUtil.normalizeQuantity(outputItem, outputRequest.getQty(), outputUnit);
            BigDecimal sellingPrice = outputRequest.getSellingPrice() == null
                    ? outputItem.getSellingPrice()
                    : outputRequest.getSellingPrice().stripTrailingZeros();
            preparedOutputs.add(new PreparedOutput(outputItem, outputBaseQty, outputRequest.getQty().stripTrailingZeros(), outputUnit, sellingPrice, link.isWaste()));
        }

        int usableBaseQty = preparedOutputs.stream()
                .filter(output -> !output.waste())
                .mapToInt(PreparedOutput::baseQty)
                .sum();
        if (usableBaseQty <= 0) {
            throw new BadRequestException("At least one non-waste output quantity is required");
        }

        BigDecimal sourceCost = QuantityConversionUtil.calculateActualAmount(sourceItem, sourceBatch.getCostPrice(), sourceBaseQty);

        sourceBatch.setQuantity(sourceBatch.getQuantity() - sourceBaseQty);
        stockBatchRepository.save(sourceBatch);

        StockProcessing processing = stockProcessingRepository.save(StockProcessing.builder()
                .branch(branch)
                .sourceItem(sourceItem)
                .sourceBatchId(sourceBatch.getId())
                .sourceBatchCode(sourceBatch.getBatchCode())
                .sourceQty(sourceBaseQty)
                .sourceDisplayQty(request.getSourceQty().stripTrailingZeros())
                .sourceQtyUnit(request.getSourceQtyUnit() == null ? QuantityConversionUtil.primaryDisplayUnit(sourceItem) : request.getSourceQtyUnit())
                .sourceCost(sourceCost)
                .processedByUserId(user.getId())
                .processedAt(LocalDateTime.now())
                .note(request.getNote() == null || request.getNote().isBlank() ? null : request.getNote().trim())
                .build());

        List<StockProcessingOutput> savedOutputs = new ArrayList<>();
        int index = 1;
        for (PreparedOutput preparedOutput : preparedOutputs) {
            BigDecimal allocatedCost = preparedOutput.waste()
                    ? BigDecimal.ZERO
                    : sourceCost.multiply(BigDecimal.valueOf(preparedOutput.baseQty()))
                    .divide(BigDecimal.valueOf(usableBaseQty), 2, RoundingMode.HALF_UP);

            Long createdBatchId = null;
            if (!preparedOutput.waste()) {
                BigDecimal displayQty = QuantityConversionUtil.toDisplayQuantity(preparedOutput.item(), preparedOutput.baseQty());
                BigDecimal unitCost = displayQty.compareTo(BigDecimal.ZERO) <= 0
                        ? BigDecimal.ZERO
                        : allocatedCost.divide(displayQty, 6, RoundingMode.HALF_UP);

                StockBatch outputBatch = stockBatchRepository.save(StockBatch.builder()
                        .branch(branch)
                        .item(preparedOutput.item())
                        .supplier(sourceBatch.getSupplier())
                        .originBatchId(sourceBatch.getId())
                        .sourceType(StockBatchSourceType.PROCESSING)
                        .batchCode("PROC-" + processing.getId() + "-" + preparedOutput.item().getId() + "-" + index)
                        .quantity(preparedOutput.baseQty())
                        .originalQuantity(preparedOutput.baseQty())
                        .costPrice(unitCost)
                        .sellingPrice(preparedOutput.sellingPrice())
                        .expireDate(sourceBatch.getExpireDate())
                        .build());
                createdBatchId = outputBatch.getId();
            }

            savedOutputs.add(stockProcessingOutputRepository.save(StockProcessingOutput.builder()
                    .processingId(processing.getId())
                    .outputItem(preparedOutput.item())
                    .quantity(preparedOutput.baseQty())
                    .displayQty(preparedOutput.displayQty())
                    .qtyUnit(preparedOutput.qtyUnit())
                    .waste(preparedOutput.waste())
                    .allocatedCost(allocatedCost)
                    .createdBatchId(createdBatchId)
                    .build()));
            index++;
        }

        return mapResponse(processing, savedOutputs, user, branch);
    }

    public Page<StockProcessingResponse> history(Long branchId, Long sourceItemId, LocalDate from, LocalDate to, int page, int size) {
        Long allowedBranchId = enforceBranchAccess(branchId);
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);

        return stockProcessingRepository.findHistory(
                allowedBranchId != null && allowedBranchId > 0 ? allowedBranchId : null,
                sourceItemId,
                fromDateTime,
                toDateTime,
                PageRequest.of(page, size)
        ).map(processing -> mapResponse(
                processing,
                stockProcessingOutputRepository.findByProcessingIdOrderByIdAsc(processing.getId()),
                userRepository.findById(processing.getProcessedByUserId()).orElse(null),
                processing.getBranch()
        ));
    }

    public StockProcessingResponse get(Long id) {
        StockProcessing processing = stockProcessingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stock processing record not found"));
        enforceBranchAccess(processing.getBranch().getId());
        return mapResponse(
                processing,
                stockProcessingOutputRepository.findByProcessingIdOrderByIdAsc(processing.getId()),
                userRepository.findById(processing.getProcessedByUserId()).orElse(null),
                processing.getBranch()
        );
    }

    private void validateSourceItem(Item sourceItem) {
        if (!sourceItem.isActive()) {
            throw new BadRequestException("Source item inactive");
        }
        if (!sourceItem.isStockProcessingEnabled()) {
            throw new BadRequestException("Source item is not enabled for stock processing");
        }
        if (!isStockTrackedItem(sourceItem)) {
            throw new BadRequestException("Only normal, weight, or volume stock items can be processed");
        }
    }

    private void validateOutputItem(Item sourceItem, Item outputItem) {
        if (!outputItem.isActive()) {
            throw new BadRequestException("Output item inactive: " + outputItem.getName());
        }
        if (outputItem.getId().equals(sourceItem.getId())) {
            throw new BadRequestException("Source item cannot be used as output");
        }
        if (!isStockTrackedItem(outputItem)) {
            throw new BadRequestException("Processing outputs must be normal, weight, or volume stock items");
        }
    }

    private boolean isStockTrackedItem(Item item) {
        return item.getItemType() == ItemType.NORMAL
                || item.getItemType() == ItemType.WEIGHT
                || item.getItemType() == ItemType.VOLUME;
    }

    private List<StockBatchResponse> mapBatches(Item item, List<StockBatch> batches) {
        return batches.stream()
                .map(batch -> new StockBatchResponse(
                        batch.getId(),
                        batch.getSellingPrice(),
                        batch.getQuantity() == null ? 0 : batch.getQuantity(),
                        QuantityConversionUtil.toDisplayQuantity(item, batch.getQuantity() == null ? 0 : batch.getQuantity()),
                        item.getDefaultUnit(),
                        batch.getExpireDate()
                ))
                .toList();
    }

    private StockProcessingResponse mapResponse(StockProcessing processing, List<StockProcessingOutput> outputs, User user, Branch branch) {
        return StockProcessingResponse.builder()
                .id(processing.getId())
                .branchId(branch != null ? branch.getId() : null)
                .branchName(branch != null ? branch.getName() : null)
                .sourceItemId(processing.getSourceItem().getId())
                .sourceItemName(processing.getSourceItem().getName())
                .sourceBarcode(processing.getSourceItem().getBarcode())
                .sourceItemType(processing.getSourceItem().getItemType())
                .sourceBatchId(processing.getSourceBatchId())
                .sourceBatchCode(processing.getSourceBatchCode())
                .sourceQty(processing.getSourceQty())
                .sourceDisplayQty(processing.getSourceDisplayQty())
                .sourceQtyUnit(processing.getSourceQtyUnit())
                .sourceCost(processing.getSourceCost())
                .processedByUserId(processing.getProcessedByUserId())
                .processedByUsername(user != null ? user.getUsername() : null)
                .processedAt(processing.getProcessedAt())
                .note(processing.getNote())
                .outputs(outputs.stream().map(this::mapOutput).toList())
                .build();
    }

    private StockProcessingOutputResponse mapOutput(StockProcessingOutput output) {
        Item item = output.getOutputItem();
        BigDecimal sellingPrice = output.getCreatedBatchId() == null
                ? item.getSellingPrice()
                : stockBatchRepository.findById(output.getCreatedBatchId())
                .map(StockBatch::getSellingPrice)
                .orElse(item.getSellingPrice());
        return StockProcessingOutputResponse.builder()
                .outputItemId(item.getId())
                .outputItemName(item.getName())
                .outputBarcode(item.getBarcode())
                .itemType(item.getItemType())
                .quantity(output.getQuantity())
                .displayQty(output.getDisplayQty())
                .qtyUnit(output.getQtyUnit())
                .waste(output.isWaste())
                .allocatedCost(output.getAllocatedCost())
                .sellingPrice(sellingPrice)
                .createdBatchId(output.getCreatedBatchId())
                .build();
    }

    private record PreparedOutput(Item item, int baseQty, BigDecimal displayQty, MeasurementUnit qtyUnit, BigDecimal sellingPrice, boolean waste) {
    }
}
