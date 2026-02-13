package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
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

    private void validateBranches(Long fromBranchId, Long toBranchId) {
        Branch from = branchRepository.findById(fromBranchId)
                .orElseThrow(() -> new ResourceNotFoundException("From branch not found"));

        Branch to = branchRepository.findById(toBranchId)
                .orElseThrow(() -> new ResourceNotFoundException("To branch not found"));

        if (!from.isActive()) throw new BadRequestException("From branch inactive");
        if (!to.isActive()) throw new BadRequestException("To branch inactive");

        if (fromBranchId.equals(toBranchId))
            throw new BadRequestException("From and To branch cannot be same");
    }

    @Transactional
    public StockTransferResponse createTransfer(CreateStockTransferRequest request) {

        User user = getLoggedUser();

        if (user.getRole() == Role.CASHIER)
            throw new BadRequestException("Cashier cannot create transfers");

        if (request.getItems() == null || request.getItems().isEmpty())
            throw new BadRequestException("Transfer items required");


        validateBranches(request.getFromBranchId(), request.getToBranchId());

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new NotAssignedException("Manager branch not assigned");
            if (!user.getBranchId().equals(request.getFromBranchId()))
                throw new BadRequestException("Manager can only transfer FROM their branch");
        }

        String transferNo = transferNumberService.generateTransferNo(request.getFromBranchId());

        List<StockTransferItem> transferItems = new ArrayList<>();

        for (StockTransferItemRequest ri : request.getItems()) {

            Item item = itemRepository.findById(ri.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + ri.getItemId()));

            int qtyNeeded = ri.getQty();

            Integer currentTotalStock = stockBatchRepository.getTotalQuantityByItemAndBranch(request.getFromBranchId(), item.getId());

            if (currentTotalStock == null || currentTotalStock < qtyNeeded) {
                throw new RuntimeException("Not enough stock for item: " + item.getName() +
                        " (Available: " + (currentTotalStock == null ? 0 : currentTotalStock) + ")");
            }

            List<StockBatch> batches = stockBatchRepository.findAvailableBatches(request.getFromBranchId(), item.getId());

            for (StockBatch batch : batches) {
                if (qtyNeeded == 0) break;

                int availableInBatch = batch.getQuantity();

                if (availableInBatch >= qtyNeeded) {
                    batch.setQuantity(availableInBatch - qtyNeeded);
                    qtyNeeded = 0;
                } else {
                    batch.setQuantity(0);
                    qtyNeeded -= availableInBatch;
                }
                stockBatchRepository.save(batch);
            }

            transferItems.add(StockTransferItem.builder()
                    .transferId(null)
                    .itemId(item.getId())
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .qty(ri.getQty())
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

        for (StockTransferItem ti : transferItems) {
            ti.setTransferId(savedTransfer.getId());
        }
        transferItemRepository.saveAll(transferItems);

        return buildResponse(savedTransfer, transferItems);
    }

    @Transactional
    public StockTransferResponse receiveTransferById(Long transferId) {
        User user = getLoggedUser();

        if (user.getRole() == Role.CASHIER)
            throw new RuntimeException("Cashier cannot receive transfers");

        StockTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));

        if (transfer.getStatus() != StockTransferStatus.IN_TRANSIT)
            throw new BadRequestException("Transfer is not IN_TRANSIT status");

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new NotAssignedException("Manager branch not assigned");

            if (!user.getBranchId().equals(transfer.getToBranchId()))
                throw new BadRequestException("Manager can only receive transfers to their branch");
        }

        Branch toBranch = branchRepository.findById(transfer.getToBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving branch not found"));

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());

        for (StockTransferItem ti : items) {

            Item item = itemRepository.findById(ti.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

            StockBatch newBatch = StockBatch.builder()
                    .branch(toBranch)
                    .item(item)
                    .quantity(ti.getQty())
                    .originalQuantity(ti.getQty())

                    .costPrice(item.getCostPrice())
                    .sellingPrice(item.getSellingPrice())

                    .batchCode("TRN-" + transfer.getTransferNo())

                    .receivedAt(LocalDateTime.now())
                    .build();

            stockBatchRepository.save(newBatch);
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

        if (transfer.getStatus() != StockTransferStatus.IN_TRANSIT)
            throw new BadRequestException("Only IN_TRANSIT (Pending) transfers can be canceled");

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new BadRequestException("Manager branch not assigned");

            boolean allowed = user.getBranchId().equals(transfer.getFromBranchId())
                    || user.getBranchId().equals(transfer.getToBranchId());

            if (!allowed)
                throw new BadRequestException("Manager cannot cancel this transfer");
        }

        Branch fromBranch = branchRepository.findById(transfer.getFromBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Sender branch not found"));

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());

        for (StockTransferItem ti : items) {

            Item item = itemRepository.findById(ti.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

            StockBatch cancelBatch = StockBatch.builder()
                    .branch(fromBranch) // Sender Branch
                    .item(item)
                    .quantity(ti.getQty())
                    .originalQuantity(ti.getQty())

                    .costPrice(item.getCostPrice())
                    .sellingPrice(item.getSellingPrice())

                    .batchCode("CNL-" + transfer.getTransferNo())

                    .receivedAt(LocalDateTime.now())
                    .build();

            stockBatchRepository.save(cancelBatch);
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
        StockTransfer transfer = transferRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());
        return buildResponse(transfer, items);
    }

    public List<StockTransferResponse> incomingPending(Long branchId) {
        return transferRepository.findByToBranchIdAndStatusOrderByRequestedAtDesc(branchId, StockTransferStatus.IN_TRANSIT)
                .stream()
                .map(t -> buildResponse(t, transferItemRepository.findByTransferId(t.getId())))
                .toList();
    }

    public List<StockTransferResponse> outgoingPending(Long branchId) {
        return transferRepository.findByFromBranchIdAndStatusOrderByRequestedAtDesc(branchId, StockTransferStatus.IN_TRANSIT)
                .stream()
                .map(t -> buildResponse(t, transferItemRepository.findByTransferId(t.getId())))
                .toList();
    }

    public List<StockTransferResponse> listOutgoing(Long fromBranchId) {
        return transferRepository.findByFromBranchIdOrderByRequestedAtDesc(fromBranchId).stream()
                .map(t -> buildResponse(t, transferItemRepository.findByTransferId(t.getId())))
                .toList();
    }

    public List<StockTransferResponse> listIncoming(Long toBranchId) {
        return transferRepository.findByToBranchIdOrderByRequestedAtDesc(toBranchId).stream()
                .map(t -> buildResponse(t, transferItemRepository.findByTransferId(t.getId())))
                .toList();
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
                .map(i -> StockTransferItemResponse.builder()
                        .itemId(i.getItemId())
                        .barcode(i.getBarcode())
                        .itemName(i.getItemName())
                        .qty(i.getQty())
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
