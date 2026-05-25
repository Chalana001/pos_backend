package com.chala.posapp.service;

import com.chala.posapp.dto.stock.CancelTransferRequest;
import com.chala.posapp.dto.stock.CreateStockTransferRequest;
import com.chala.posapp.dto.stock.StockTransferItemRequest;
import com.chala.posapp.dto.stock.StockTransferItemResponse;
import com.chala.posapp.dto.stock.StockTransferResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.entity.stock.StockTransfer;
import com.chala.posapp.entity.stock.StockTransferItem;
import com.chala.posapp.entity.stock.StockTransferStatus;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.StockTransferItemRepository;
import com.chala.posapp.repository.StockTransferRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockTransferService {

    private final StockTransferRepository transferRepository;
    private final StockTransferItemRepository transferItemRepository;
    private final TransferNumberService transferNumberService;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final StockBatchRepository stockBatchRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private boolean isAdminLike(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
    }

    private void ensureManagerBranchAccess(User user, Long branchId) {
        if (user.getRole() != Role.MANAGER) {
            return;
        }

        if (user.getBranchId() == null) {
            throw new NotAssignedException("Manager branch not assigned");
        }

        if (!user.getBranchId().equals(branchId)) {
            throw new BadRequestException("Manager can only access their branch");
        }
    }

    private void ensureTransferAccess(User user, StockTransfer transfer) {
        if (isAdminLike(user)) {
            return;
        }

        if (user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        if (user.getBranchId() == null) {
            throw new NotAssignedException("Manager branch not assigned");
        }

        boolean allowed = user.getBranchId().equals(transfer.getFromBranchId())
                || user.getBranchId().equals(transfer.getToBranchId());
        if (!allowed) {
            throw new BadRequestException("Manager cannot access this transfer");
        }
    }

    private void validateBranches(Long fromBranchId, Long toBranchId) {
        Branch from = branchRepository.findById(fromBranchId)
                .orElseThrow(() -> new ResourceNotFoundException("From branch not found"));
        Branch to = branchRepository.findById(toBranchId)
                .orElseThrow(() -> new ResourceNotFoundException("To branch not found"));

        if (!from.isActive()) throw new BadRequestException("From branch inactive");
        if (!to.isActive()) throw new BadRequestException("To branch inactive");
        if (fromBranchId.equals(toBranchId)) {
            throw new BadRequestException("From and To branch cannot be same");
        }
    }

    @Transactional
    public StockTransferResponse createTransfer(CreateStockTransferRequest request) {
        User user = getLoggedUser();

        if (user.getRole() == Role.CASHIER) {
            throw new BadRequestException("Cashier cannot create transfers");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BadRequestException("Transfer items required");
        }
        if (request.getFromBranchId().equals(request.getToBranchId())) {
            throw new BadRequestException("Cannot transfer stock to the same branch");
        }

        validateBranches(request.getFromBranchId(), request.getToBranchId());
        if (user.getRole() == Role.MANAGER) {
            ensureManagerBranchAccess(user, request.getFromBranchId());
        }

        String transferNo = transferNumberService.generateTransferNo(request.getFromBranchId());
        List<StockTransferItem> transferItems = new ArrayList<>();

        for (StockTransferItemRequest reqItem : request.getItems()) {
            Item item = itemRepository.findById(reqItem.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + reqItem.getItemId()));

            // ✅ SERVICE අයිටම් එකක් නම් ට්‍රාන්ස්ෆර් කරන්න බැරි වෙන්න Block කරනවා
            if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
                throw new BadRequestException("Only stock-tracked grocery items can be transferred. Item: " + item.getName());
            }

            if (reqItem.getBatchId() == null) {
                throw new BadRequestException("Batch ID is required for item: " + item.getName());
            }

            StockBatch batch = stockBatchRepository.findById(reqItem.getBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Stock batch not found"));

            if (!batch.getBranch().getId().equals(request.getFromBranchId())) {
                throw new BadRequestException("Selected batch does not belong to the source branch");
            }
            if (!batch.getItem().getId().equals(reqItem.getItemId())) {
                throw new BadRequestException("Selected batch does not match the requested item");
            }

            int normalizedQty = QuantityConversionUtil.normalizeQuantity(item, reqItem.getQty(), reqItem.getQtyUnit());
            if (batch.getQuantity() < normalizedQty) {
                throw new BadRequestException("Not enough stock in the selected batch for item: " + item.getName()
                        + " (Available: " + batch.getQuantity() + ")");
            }

            batch.setQuantity(batch.getQuantity() - normalizedQty);
            stockBatchRepository.save(batch);

            transferItems.add(StockTransferItem.builder()
                    .transferId(null)
                    .itemId(item.getId())
                    .batchId(batch.getId())
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .qty(normalizedQty)
                    .displayQty(reqItem.getQty().stripTrailingZeros())
                    .qtyUnit(QuantityConversionUtil.isMeasuredItem(item.getItemType())
                            ? (reqItem.getQtyUnit() == null ? item.getDefaultUnit() : reqItem.getQtyUnit())
                            : item.getDefaultUnit())
                    .build());
        }

        StockTransfer transfer = StockTransfer.builder()
                .transferNo(transferNo)
                .fromBranchId(request.getFromBranchId())
                .toBranchId(request.getToBranchId())
                .status(StockTransferStatus.IN_TRANSIT)
                .requestedByUserId(user.getId())
                .note(request.getNote())
                .requestedAt(LocalDateTime.now())
                .build();

        StockTransfer savedTransfer = transferRepository.save(transfer);
        for (StockTransferItem transferItem : transferItems) {
            transferItem.setTransferId(savedTransfer.getId());
        }
        transferItemRepository.saveAll(transferItems);

        return buildResponse(savedTransfer, transferItems);
    }

    @Transactional
    public StockTransferResponse receiveTransferById(Long transferId) {
        User user = getLoggedUser();

        if (user.getRole() == Role.CASHIER) {
            throw new BadRequestException("Cashier cannot receive transfers");
        }

        StockTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));

        if (transfer.getStatus() != StockTransferStatus.IN_TRANSIT) {
            throw new BadRequestException("Transfer is not IN_TRANSIT status");
        }

        if (user.getRole() == Role.MANAGER) {
            ensureManagerBranchAccess(user, transfer.getToBranchId());
        }

        Branch toBranch = branchRepository.findById(transfer.getToBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving branch not found"));

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());
        for (StockTransferItem transferItem : items) {
            StockBatch sourceBatch = stockBatchRepository.findById(transferItem.getBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Source batch not found"));
            receiveIntoDestinationBatch(transfer, toBranch, sourceBatch, transferItem);
        }

        transfer.setStatus(StockTransferStatus.COMPLETED);
        transfer.setReceivedByUserId(user.getId());
        transfer.setReceivedAt(LocalDateTime.now());

        return buildResponse(transferRepository.save(transfer), items);
    }

    @Transactional
    public StockTransferResponse receiveTransfer(String transferNo) {
        StockTransfer transfer = transferRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));
        return receiveTransferById(transfer.getId());
    }

    @Transactional
    public StockTransferResponse cancelTransferById(Long transferId, CancelTransferRequest request) {
        User user = getLoggedUser();

        StockTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));

        if (transfer.getStatus() != StockTransferStatus.IN_TRANSIT) {
            throw new BadRequestException("Only IN_TRANSIT transfers can be canceled");
        }

        if (user.getRole() == Role.MANAGER) {
            ensureTransferAccess(user, transfer);
        }

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());
        for (StockTransferItem transferItem : items) {
            restoreToSourceBatch(transferItem, transfer.getFromBranchId());
        }

        transfer.setStatus(StockTransferStatus.CANCELED);
        transfer.setCancelReason(request.getReason() != null ? request.getReason().trim() : "Cancelled by User");
        transfer.setCanceledAt(LocalDateTime.now());

        return buildResponse(transferRepository.save(transfer), items);
    }

    @Transactional
    public StockTransferResponse cancelTransfer(String transferNo, CancelTransferRequest request) {
        StockTransfer transfer = transferRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));
        return cancelTransferById(transfer.getId(), request);
    }

    public StockTransferResponse getTransfer(String transferNo) {
        User user = getLoggedUser();
        StockTransfer transfer = transferRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));

        ensureTransferAccess(user, transfer);
        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());
        return buildResponse(transfer, items);
    }

    public List<StockTransferResponse> incomingPending(Long branchId) {
        ensureManagerBranchAccess(getLoggedUser(), branchId);
        return transferRepository.findByToBranchIdAndStatusOrderByRequestedAtDesc(branchId, StockTransferStatus.IN_TRANSIT)
                .stream()
                .map(transfer -> buildResponse(transfer, transferItemRepository.findByTransferId(transfer.getId())))
                .toList();
    }

    public List<StockTransferResponse> outgoingPending(Long branchId) {
        ensureManagerBranchAccess(getLoggedUser(), branchId);
        return transferRepository.findByFromBranchIdAndStatusOrderByRequestedAtDesc(branchId, StockTransferStatus.IN_TRANSIT)
                .stream()
                .map(transfer -> buildResponse(transfer, transferItemRepository.findByTransferId(transfer.getId())))
                .toList();
    }

    public List<StockTransferResponse> listOutgoing(Long fromBranchId) {
        ensureManagerBranchAccess(getLoggedUser(), fromBranchId);
        return transferRepository.findByFromBranchIdOrderByRequestedAtDesc(fromBranchId).stream()
                .map(transfer -> buildResponse(transfer, transferItemRepository.findByTransferId(transfer.getId())))
                .toList();
    }

    public List<StockTransferResponse> listIncoming(Long toBranchId) {
        ensureManagerBranchAccess(getLoggedUser(), toBranchId);
        return transferRepository.findByToBranchIdOrderByRequestedAtDesc(toBranchId).stream()
                .map(transfer -> buildResponse(transfer, transferItemRepository.findByTransferId(transfer.getId())))
                .toList();
    }

    public Page<StockTransferResponse> listOutgoingPage(
            Long fromBranchId,
            String search,
            StockTransferStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        User user = getLoggedUser();
        Long effectiveBranchId = resolveScopedBranchId(user, fromBranchId);
        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();

        Page<StockTransfer> transfers = transferRepository.findOutgoingHistory(
                effectiveBranchId,
                normalizedSearch,
                status,
                fromDateTime,
                toDateTime,
                PageRequest.of(page, size)
        );

        List<StockTransferResponse> content = transfers.getContent().stream()
                .map(transfer -> buildResponse(transfer, transferItemRepository.findByTransferId(transfer.getId())))
                .toList();
        return new PageImpl<>(content, transfers.getPageable(), transfers.getTotalElements());
    }

    public Page<StockTransferResponse> listIncomingPage(
            Long toBranchId,
            String search,
            StockTransferStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        User user = getLoggedUser();
        Long effectiveBranchId = resolveScopedBranchId(user, toBranchId);
        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();

        Page<StockTransfer> transfers = transferRepository.findIncomingHistory(
                effectiveBranchId,
                normalizedSearch,
                status,
                fromDateTime,
                toDateTime,
                PageRequest.of(page, size)
        );

        List<StockTransferResponse> content = transfers.getContent().stream()
                .map(transfer -> buildResponse(transfer, transferItemRepository.findByTransferId(transfer.getId())))
                .toList();
        return new PageImpl<>(content, transfers.getPageable(), transfers.getTotalElements());
    }

    private Long resolveScopedBranchId(User user, Long branchId) {
        if (isAdminLike(user)) {
            return branchId != null && branchId > 0 ? branchId : null;
        }

        if (user.getRole() != Role.MANAGER) {
            throw new BadRequestException("Not allowed");
        }

        if (user.getBranchId() == null) {
            throw new NotAssignedException("Manager branch not assigned");
        }

        if (branchId == null || branchId == 0L) {
            return user.getBranchId();
        }

        if (!user.getBranchId().equals(branchId)) {
            throw new BadRequestException("Manager can only access their branch");
        }

        return branchId;
    }

    private void receiveIntoDestinationBatch(StockTransfer transfer, Branch toBranch, StockBatch sourceBatch, StockTransferItem transferItem) {
        StockBatch existingBatch = stockBatchRepository.findByBranchIdAndItemIdAndOriginBatchId(
                        toBranch.getId(),
                        transferItem.getItemId(),
                        sourceBatch.getId()
                )
                .orElse(null);

        if (existingBatch != null) {
            existingBatch.setQuantity(existingBatch.getQuantity() + transferItem.getQty());
            stockBatchRepository.save(existingBatch);
            return;
        }

        StockBatch newBatch = StockBatch.builder()
                .branch(toBranch)
                .item(sourceBatch.getItem())
                .supplier(sourceBatch.getSupplier())
                .batchCode(buildTransferredBatchCode(transfer, sourceBatch, toBranch.getId()))
                .originBatchId(sourceBatch.getId())
                .sourceType(StockBatchSourceType.TRANSFER)
                .quantity(transferItem.getQty())
                .originalQuantity(transferItem.getQty())
                .costPrice(sourceBatch.getCostPrice())
                .sellingPrice(sourceBatch.getSellingPrice())
                .receivedAt(LocalDateTime.now())
                .expireDate(sourceBatch.getExpireDate())
                .build();

        stockBatchRepository.save(newBatch);
    }

    private void restoreToSourceBatch(StockTransferItem transferItem, Long fromBranchId) {
        StockBatch sourceBatch = stockBatchRepository.findById(transferItem.getBatchId())
                .orElseThrow(() -> new ResourceNotFoundException("Source batch not found"));

        if (!sourceBatch.getBranch().getId().equals(fromBranchId)) {
            throw new BadRequestException("Source batch does not belong to the transfer origin branch");
        }
        if (!sourceBatch.getItem().getId().equals(transferItem.getItemId())) {
            throw new BadRequestException("Source batch does not match the transferred item");
        }

        sourceBatch.setQuantity(sourceBatch.getQuantity() + transferItem.getQty());
        stockBatchRepository.save(sourceBatch);
    }

    private String buildTransferredBatchCode(StockTransfer transfer, StockBatch sourceBatch, Long toBranchId) {
        return "TRN-" + transfer.getTransferNo() + "-SRC-" + sourceBatch.getId() + "-BR-" + toBranchId;
    }

    private StockTransferResponse buildResponse(StockTransfer transfer, List<StockTransferItem> items) {
        String fromBranchName = branchRepository.findById(transfer.getFromBranchId())
                .map(Branch::getName)
                .orElse("Unknown Branch");
        String toBranchName = branchRepository.findById(transfer.getToBranchId())
                .map(Branch::getName)
                .orElse("Unknown Branch");

        String reqUserName = userRepository.findById(transfer.getRequestedByUserId())
                .map(User::getUsername)
                .orElse("Unknown User");

        String recUserName = null;
        if (transfer.getReceivedByUserId() != null) {
            recUserName = userRepository.findById(transfer.getReceivedByUserId())
                    .map(User::getUsername)
                    .orElse("Unknown User");
        }

        List<StockTransferItemResponse> itemResponses = items.stream()
                .map(item -> StockTransferItemResponse.builder()
                        .itemId(item.getItemId())
                        .barcode(item.getBarcode())
                        .itemName(item.getItemName())
                        .qty(item.getDisplayQty())
                        .qtyUnit(item.getQtyUnit())
                        .build())
                .toList();

        return StockTransferResponse.builder()
                .id(transfer.getId())
                .transferNo(transfer.getTransferNo())
                .fromBranchId(transfer.getFromBranchId())
                .toBranchId(transfer.getToBranchId())
                .fromBranchName(fromBranchName)
                .toBranchName(toBranchName)
                .status(transfer.getStatus())
                .requestedByUserId(transfer.getRequestedByUserId())
                .requestedByUserName(reqUserName)
                .receivedByUserId(transfer.getReceivedByUserId())
                .receivedByUserName(recUserName)
                .note(transfer.getNote())
                .cancelReason(transfer.getCancelReason())
                .requestedAt(transfer.getRequestedAt())
                .receivedAt(transfer.getReceivedAt())
                .canceledAt(transfer.getCanceledAt())
                .items(itemResponses)
                .build();
    }
}
