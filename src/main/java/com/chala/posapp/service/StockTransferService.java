package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.*;
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

    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

    // ✅ branch validation
    private final BranchRepository branchRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private void validateBranches(Long fromBranchId, Long toBranchId) {
        Branch from = branchRepository.findById(fromBranchId)
                .orElseThrow(() -> new RuntimeException("From branch not found"));

        Branch to = branchRepository.findById(toBranchId)
                .orElseThrow(() -> new RuntimeException("To branch not found"));

        if (!from.isActive()) throw new RuntimeException("From branch inactive");
        if (!to.isActive()) throw new RuntimeException("To branch inactive");

        if (fromBranchId.equals(toBranchId))
            throw new RuntimeException("From and To branch cannot be same");
    }

    @Transactional
    public StockTransferResponse createTransfer(CreateStockTransferRequest request) {

        User user = getLoggedUser();

        if (user.getRole() == Role.CASHIER)
            throw new RuntimeException("Cashier cannot create transfers");

        if (request.getItems() == null || request.getItems().isEmpty())
            throw new RuntimeException("Transfer items required");

        validateBranches(request.getFromBranchId(), request.getToBranchId());

        // MANAGER can only send from own branch
        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Manager branch not assigned");

            if (!user.getBranchId().equals(request.getFromBranchId()))
                throw new RuntimeException("Manager can only transfer FROM their branch");
        }

        String transferNo = transferNumberService.generateTransferNo(request.getFromBranchId());

        // Reduce stock from sender (reserved/out)
        List<StockTransferItem> transferItems = new ArrayList<>();

        for (StockTransferItemRequest ri : request.getItems()) {
            Item item = itemRepository.findById(ri.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found: " + ri.getItemId()));

            Stock stockFrom = stockRepository.findByBranchIdAndItemId(request.getFromBranchId(), item.getId())
                    .orElseThrow(() -> new RuntimeException("No stock record in FROM branch for item: " + item.getBarcode()));

            if (stockFrom.getQuantity() < ri.getQty())
                throw new RuntimeException("Not enough stock in FROM branch for item: " + item.getName());

            stockFrom.setQuantity(stockFrom.getQuantity() - ri.getQty());
            stockRepository.save(stockFrom);

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
                .status(StockTransferStatus.REQUESTED)
                .requestedByUserId(user.getId())
                .note(request.getNote())
                .build();

        StockTransfer saved = transferRepository.save(transfer);

        for (StockTransferItem ti : transferItems) ti.setTransferId(saved.getId());
        transferItemRepository.saveAll(transferItems);

        return buildResponse(saved, transferItems);
    }

    // ✅ receive by transfer ID (preferred)
    @Transactional
    public StockTransferResponse receiveTransferById(Long transferId) {
        User user = getLoggedUser();

        if (user.getRole() == Role.CASHIER)
            throw new RuntimeException("Cashier cannot receive transfers");

        StockTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (transfer.getStatus() != StockTransferStatus.REQUESTED)
            throw new RuntimeException("Transfer is not pending");

        // MANAGER can only receive to own branch
        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Manager branch not assigned");

            if (!user.getBranchId().equals(transfer.getToBranchId()))
                throw new RuntimeException("Manager can only receive transfers to their branch");
        }

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());

        for (StockTransferItem ti : items) {
            Stock stockTo = stockRepository.findByBranchIdAndItemId(transfer.getToBranchId(), ti.getItemId())
                    .orElse(Stock.builder()
                            .branchId(transfer.getToBranchId())
                            .itemId(ti.getItemId())
                            .quantity(0)
                            .build());

            stockTo.setQuantity(stockTo.getQuantity() + ti.getQty());
            stockRepository.save(stockTo);
        }

        transfer.setStatus(StockTransferStatus.RECEIVED);
        transfer.setReceivedByUserId(user.getId());
        transfer.setReceivedAt(LocalDateTime.now());

        return buildResponse(transferRepository.save(transfer), items);
    }

    // keep old receive by transferNo (optional)
    @Transactional
    public StockTransferResponse receiveTransfer(String transferNo) {
        StockTransfer transfer = transferRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        return receiveTransferById(transfer.getId());
    }

    // ✅ cancel by transfer ID (sender/receiver/admin)
    @Transactional
    public StockTransferResponse cancelTransferById(Long transferId, CancelTransferRequest request) {

        User user = getLoggedUser();

        StockTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (transfer.getStatus() != StockTransferStatus.REQUESTED)
            throw new RuntimeException("Only pending transfers can be canceled");

        // MANAGER can cancel only if belongs to from OR to branch
        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Manager branch not assigned");

            boolean allowed = user.getBranchId().equals(transfer.getFromBranchId())
                    || user.getBranchId().equals(transfer.getToBranchId());

            if (!allowed)
                throw new RuntimeException("Manager cannot cancel this transfer");
        }

        // rollback stock back to from branch
        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());

        for (StockTransferItem ti : items) {
            Stock stockFrom = stockRepository.findByBranchIdAndItemId(transfer.getFromBranchId(), ti.getItemId())
                    .orElse(Stock.builder()
                            .branchId(transfer.getFromBranchId())
                            .itemId(ti.getItemId())
                            .quantity(0)
                            .build());

            stockFrom.setQuantity(stockFrom.getQuantity() + ti.getQty());
            stockRepository.save(stockFrom);
        }

        transfer.setStatus(StockTransferStatus.CANCELED);
        transfer.setCancelReason(request.getReason().trim());
        transfer.setCanceledAt(LocalDateTime.now());

        return buildResponse(transferRepository.save(transfer), items);
    }

    // keep old cancel by transferNo (optional)
    @Transactional
    public StockTransferResponse cancelTransfer(String transferNo, CancelTransferRequest request) {
        StockTransfer transfer = transferRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        return cancelTransferById(transfer.getId(), request);
    }

    public StockTransferResponse getTransfer(String transferNo) {
        StockTransfer transfer = transferRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());
        return buildResponse(transfer, items);
    }

    // ✅ incoming pending list (dashboard use)
    public List<StockTransferResponse> incomingPending(Long branchId) {
        return transferRepository.findByToBranchIdAndStatusOrderByRequestedAtDesc(branchId, StockTransferStatus.REQUESTED)
                .stream()
                .map(t -> buildResponse(t, transferItemRepository.findByTransferId(t.getId())))
                .toList();
    }

    // ✅ outgoing pending list (sender dashboard)
    public List<StockTransferResponse> outgoingPending(Long branchId) {
        return transferRepository.findByFromBranchIdAndStatusOrderByRequestedAtDesc(branchId, StockTransferStatus.REQUESTED)
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
                .status(transfer.getStatus())
                .requestedByUserId(transfer.getRequestedByUserId())
                .receivedByUserId(transfer.getReceivedByUserId())
                .note(transfer.getNote())
                .cancelReason(transfer.getCancelReason())
                .requestedAt(transfer.getRequestedAt())
                .receivedAt(transfer.getReceivedAt())
                .canceledAt(transfer.getCanceledAt())
                .items(itemResponses)
                .build();
    }
}
