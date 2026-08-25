package com.chala.posapp.service;

import com.chala.posapp.dto.item.StockProcessingOutputLinkResponse;
import com.chala.posapp.dto.stock.CancelStockProcessingRequest;
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
import com.chala.posapp.entity.stock.StockProcessingStatus;
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
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final SecurityUtils securityUtils;
    private final UserRepository userRepository;
    private final ReportCacheInvalidator reportCacheInvalidator;

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead

    // DUP-05 FIX: securityUtils.enforceBranchAccess() centralised in SecurityUtils

    public List<StockProcessingSourceResponse> listSources(Long branchId) {
        Long allowedBranchId = securityUtils.enforceBranchAccess(branchId);
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
                    BigDecimal effectiveSellingPrice = link.getDefaultSellingPrice() != null
                            ? link.getDefaultSellingPrice()
                            : output.getSellingPrice();
                    return StockProcessingOutputLinkResponse.builder()
                            .outputItemId(output.getId())
                            .outputBarcode(output.getBarcode())
                            .outputName(output.getName())
                            .itemType(output.getItemType())
                            .defaultUnit(output.getDefaultUnit())
                            .defaultQty(QuantityConversionUtil.toDisplayQuantity(output, link.getDefaultQuantity()))
                            .defaultQtyUnit(QuantityConversionUtil.primaryDisplayUnit(output))
                            .defaultSellingPrice(effectiveSellingPrice)
                            .waste(link.isWaste())
                            .build();
                })
                .toList();
    }

    @Transactional
    public StockProcessingResponse create(CreateStockProcessingRequest request) {
        User user = securityUtils.getCurrentUser();
        if (user.getRole() == Role.CASHIER) {
            throw new BadRequestException("Cashier cannot process stock");
        }

        Long branchId = securityUtils.enforceBranchAccess(request.getBranchId());
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
            // Selling price priority: request override → link default → item selling price
            BigDecimal sellingPrice;
            if (outputRequest.getSellingPrice() != null) {
                sellingPrice = outputRequest.getSellingPrice().stripTrailingZeros();
            } else if (link.getDefaultSellingPrice() != null) {
                sellingPrice = link.getDefaultSellingPrice();
            } else {
                sellingPrice = outputItem.getSellingPrice();
            }
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

        // Sales Value Method: allocate source cost proportional to expected revenue (qty × sellingPrice)
        BigDecimal totalExpectedRevenue = BigDecimal.ZERO;
        for (PreparedOutput po : preparedOutputs) {
            if (po.waste()) continue;
            totalExpectedRevenue = totalExpectedRevenue.add(po.displayQty().multiply(po.sellingPrice()));
        }
        final boolean useSalesValue = totalExpectedRevenue.signum() > 0;

        // Find index of last non-waste output (absorbs rounding drift)
        int lastNonWasteIdx = -1;
        for (int i = preparedOutputs.size() - 1; i >= 0; i--) {
            if (!preparedOutputs.get(i).waste()) {
                lastNonWasteIdx = i;
                break;
            }
        }

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
        BigDecimal runningAllocated = BigDecimal.ZERO;
        int index = 1;
        for (int i = 0; i < preparedOutputs.size(); i++) {
            PreparedOutput preparedOutput = preparedOutputs.get(i);
            BigDecimal allocatedCost;
            if (preparedOutput.waste()) {
                allocatedCost = BigDecimal.ZERO;
            } else if (i == lastNonWasteIdx) {
                // Last non-waste row absorbs any rounding drift so Σ == sourceCost
                allocatedCost = sourceCost.subtract(runningAllocated).max(BigDecimal.ZERO);
            } else if (useSalesValue) {
                BigDecimal lineRevenue = preparedOutput.displayQty().multiply(preparedOutput.sellingPrice());
                allocatedCost = sourceCost.multiply(lineRevenue)
                        .divide(totalExpectedRevenue, 2, RoundingMode.HALF_UP);
            } else {
                // Fallback: physical qty ratio (all selling prices are zero)
                allocatedCost = sourceCost.multiply(BigDecimal.valueOf(preparedOutput.baseQty()))
                        .divide(BigDecimal.valueOf(usableBaseQty), 2, RoundingMode.HALF_UP);
            }
            runningAllocated = runningAllocated.add(allocatedCost);

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

        reportCacheInvalidator.stockChanged();

        return mapResponse(processing, savedOutputs, user, branch);
    }

    public Page<StockProcessingResponse> history(Long branchId, Long sourceItemId, LocalDate from, LocalDate to, int page, int size) {
        Long allowedBranchId = securityUtils.enforceBranchAccess(branchId);
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
        securityUtils.enforceBranchAccess(processing.getBranch().getId());
        return mapResponse(
                processing,
                stockProcessingOutputRepository.findByProcessingIdOrderByIdAsc(processing.getId()),
                userRepository.findById(processing.getProcessedByUserId()).orElse(null),
                processing.getBranch()
        );
    }

    @Transactional
    public StockProcessingResponse cancel(Long id, CancelStockProcessingRequest request) {
        User user = securityUtils.getCurrentUser();
        if (user.getRole() == Role.CASHIER) {
            throw new BadRequestException("Cashier cannot cancel stock processing");
        }

        StockProcessing processing = stockProcessingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stock processing record not found"));
        securityUtils.enforceBranchAccess(processing.getBranch().getId());

        if (processing.getStatus() == StockProcessingStatus.CANCELED) {
            throw new BadRequestException("Stock processing already canceled");
        }

        reportCacheInvalidator.stockChanged();

        List<StockProcessingOutput> outputs = stockProcessingOutputRepository.findByProcessingIdOrderByIdAsc(processing.getId());
        List<StockBatch> batchesToDelete = new ArrayList<>();
        for (StockProcessingOutput output : outputs) {
            if (output.isWaste() || output.getCreatedBatchId() == null) {
                continue;
            }

            StockBatch createdBatch = stockBatchRepository.findById(output.getCreatedBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Created output batch not found"));
            Integer currentQty = createdBatch.getQuantity() == null ? 0 : createdBatch.getQuantity();
            Integer originalQty = createdBatch.getOriginalQuantity() == null ? 0 : createdBatch.getOriginalQuantity();
            if (!currentQty.equals(originalQty)) {
                throw new BadRequestException("Cannot cancel stock processing because output stock has already been sold or adjusted");
            }
            batchesToDelete.add(createdBatch);
        }

        StockBatch sourceBatch = stockBatchRepository.findById(processing.getSourceBatchId())
                .orElseThrow(() -> new ResourceNotFoundException("Source batch not found"));
        int existingSourceQty = sourceBatch.getQuantity() == null ? 0 : sourceBatch.getQuantity();
        sourceBatch.setQuantity(existingSourceQty + processing.getSourceQty());
        stockBatchRepository.save(sourceBatch);

        if (!batchesToDelete.isEmpty()) {
            stockBatchRepository.deleteAll(batchesToDelete);
        }

        processing.setStatus(StockProcessingStatus.CANCELED);
        processing.setCancelReason(request.getReason().trim());
        processing.setCanceledByUserId(user.getId());
        processing.setCanceledAt(LocalDateTime.now());
        StockProcessing saved = stockProcessingRepository.save(processing);

        return mapResponse(
                saved,
                outputs,
                userRepository.findById(saved.getProcessedByUserId()).orElse(null),
                saved.getBranch()
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
        User canceledBy = processing.getCanceledByUserId() == null
                ? null
                : userRepository.findById(processing.getCanceledByUserId()).orElse(null);
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
                .status(processing.getStatus())
                .cancelReason(processing.getCancelReason())
                .canceledByUserId(processing.getCanceledByUserId())
                .canceledByUsername(canceledBy != null ? canceledBy.getUsername() : null)
                .canceledAt(processing.getCanceledAt())
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
