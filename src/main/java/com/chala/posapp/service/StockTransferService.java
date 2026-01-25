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

    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

    // ✅ branch validation
    private final BranchRepository branchRepository;
    private final StockBatchRepository stockBatchRepository;

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

        // 1. SECURITY & VALIDATION (Same as before)
        if (user.getRole() == Role.CASHIER)
            throw new RuntimeException("Cashier cannot create transfers");

        if (request.getItems() == null || request.getItems().isEmpty())
            throw new RuntimeException("Transfer items required");

        validateBranches(request.getFromBranchId(), request.getToBranchId());

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Manager branch not assigned");
            if (!user.getBranchId().equals(request.getFromBranchId()))
                throw new RuntimeException("Manager can only transfer FROM their branch");
        }

        String transferNo = transferNumberService.generateTransferNo(request.getFromBranchId());

        // 2. PROCESS ITEMS (REDUCE STOCK FROM SENDER)
        List<StockTransferItem> transferItems = new ArrayList<>();

        for (StockTransferItemRequest ri : request.getItems()) {

            // A. Item එක හොයාගන්න
            Item item = itemRepository.findById(ri.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found: " + ri.getItemId()));

            int qtyNeeded = ri.getQty();

            // B. මුළු Stock එක ඇතිද කියලා Check කරනවා (Total of all batches)
            Integer currentTotalStock = stockBatchRepository.getTotalQuantityByItemAndBranch(request.getFromBranchId(), item.getId());

            if (currentTotalStock == null || currentTotalStock < qtyNeeded) {
                throw new RuntimeException("Not enough stock for item: " + item.getName() +
                        " (Available: " + (currentTotalStock == null ? 0 : currentTotalStock) + ")");
            }

            // C. FIFO Reduction (වැදගත්ම කොටස) 🚀
            // පරණම Batches ටික උඩට එන විදියට ගන්න (OrderBy ReceivedAt ASC)
            List<StockBatch> batches = stockBatchRepository.findAvailableBatches(request.getFromBranchId(), item.getId());

            for (StockBatch batch : batches) {
                if (qtyNeeded == 0) break; // අවශ්‍ය ගාන අඩු කරලා ඉවරයි

                int availableInBatch = batch.getQuantity();

                if (availableInBatch >= qtyNeeded) {
                    // මේ Batch එකේ ඇති වෙන්න බඩු තියෙනවා
                    batch.setQuantity(availableInBatch - qtyNeeded);
                    qtyNeeded = 0; // වැඩේ ඉවරයි
                } else {
                    // මේ Batch එක මදි, තියෙන ටික ඔක්කොම ගන්නවා
                    batch.setQuantity(0);
                    qtyNeeded -= availableInBatch; // තව අඩු කරන්න ඕන ගාන ඊළඟ Batch එකට ගෙනියනවා
                }
                stockBatchRepository.save(batch); // Update Batch
            }

            // D. Transfer Item Record එක හදනවා
            transferItems.add(StockTransferItem.builder()
                    .transferId(null) // පස්සේ set කරනවා
                    .itemId(item.getId())
                    .barcode(item.getBarcode())
                    .itemName(item.getName())
                    .qty(ri.getQty())
                    .build());
        }

        // 3. SAVE TRANSFER HEADER
        StockTransfer transfer = StockTransfer.builder()
                .transferNo(transferNo)
                .fromBranchId(request.getFromBranchId())
                .toBranchId(request.getToBranchId())
                .status(StockTransferStatus.IN_TRANSIT) // Stock අඩු වුනා, තාම ලැබුනේ නෑ
                .requestedByUserId(user.getId())
                .note(request.getNote())
                .requestedAt(LocalDateTime.now())
                .build();

        StockTransfer savedTransfer = transferRepository.save(transfer);

        // 4. SAVE TRANSFER ITEMS
        for (StockTransferItem ti : transferItems) {
            ti.setTransferId(savedTransfer.getId());
        }
        transferItemRepository.saveAll(transferItems);

        return buildResponse(savedTransfer, transferItems);
    }

    // ✅ receive by transfer ID (preferred)
    @Transactional
    public StockTransferResponse receiveTransferById(Long transferId) {
        User user = getLoggedUser();

        // 1. Validations (Same as yours)
        if (user.getRole() == Role.CASHIER)
            throw new RuntimeException("Cashier cannot receive transfers");

        StockTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (transfer.getStatus() != StockTransferStatus.IN_TRANSIT)
            throw new RuntimeException("Transfer is not IN_TRANSIT status");

        // MANAGER Validation
        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Manager branch not assigned");

            if (!user.getBranchId().equals(transfer.getToBranchId()))
                throw new RuntimeException("Manager can only receive transfers to their branch");
        }

        // 2. Get Branch Entity (StockBatch එකට Object එක ඕන නිසා)
        Branch toBranch = branchRepository.findById(transfer.getToBranchId())
                .orElseThrow(() -> new RuntimeException("Receiving branch not found"));

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());

        // 3. PROCESS ITEMS (Create NEW BATCHES) 🚀
        for (StockTransferItem ti : items) {

            // Item එකේ විස්තර ගන්න (Cost Price එක දැනගන්න)
            Item item = itemRepository.findById(ti.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found"));

            // අලුත් Batch එකක් හදනවා
            StockBatch newBatch = StockBatch.builder()
                    .branch(toBranch)
                    .item(item)
                    .quantity(ti.getQty())
                    .originalQuantity(ti.getQty())

                    // Cost එක Item Master එකෙන් ගන්නවා (GRN Price)
                    .costPrice(item.getCostPrice())
                    .sellingPrice(item.getSellingPrice())

                    // Transfer එකෙන් ආපු බව දැනගන්න Batch Code එක
                    .batchCode("TRN-" + transfer.getTransferNo())

                    .receivedAt(LocalDateTime.now())
                    .build();

            stockBatchRepository.save(newBatch);
        }

        // 4. UPDATE TRANSFER STATUS
        transfer.setStatus(StockTransferStatus.COMPLETED); // IN_TRANSIT -> COMPLETED
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

        // 1. GET TRANSFER & VALIDATE
        StockTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        if (transfer.getStatus() != StockTransferStatus.IN_TRANSIT)
            throw new RuntimeException("Only IN_TRANSIT (Pending) transfers can be canceled");

        // 2. CHECK PERMISSIONS (MANAGER)
        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Manager branch not assigned");

            // Sender ට හෝ Receiver ට මේක Cancel කරන්න පුළුවන්
            boolean allowed = user.getBranchId().equals(transfer.getFromBranchId())
                    || user.getBranchId().equals(transfer.getToBranchId());

            if (!allowed)
                throw new RuntimeException("Manager cannot cancel this transfer");
        }

        // 3. GET FROM_BRANCH (බඩු ආපහු යන්නේ යවපු කෙනාටමයි)
        Branch fromBranch = branchRepository.findById(transfer.getFromBranchId())
                .orElseThrow(() -> new RuntimeException("Sender branch not found"));

        List<StockTransferItem> items = transferItemRepository.findByTransferId(transfer.getId());

        // 4. RESTOCK ITEMS (CREATE NEW BATCHES IN SENDER BRANCH) 🚀
        for (StockTransferItem ti : items) {

            Item item = itemRepository.findById(ti.getItemId())
                    .orElseThrow(() -> new RuntimeException("Item not found"));

            // යවපු කෙනාටම බඩු ටික ආපහු දානවා (Re-stocking)
            StockBatch cancelBatch = StockBatch.builder()
                    .branch(fromBranch) // Sender Branch
                    .item(item)
                    .quantity(ti.getQty())
                    .originalQuantity(ti.getQty())

                    // Cost එක Item Master එකෙන් ගන්න
                    .costPrice(item.getCostPrice())
                    .sellingPrice(item.getSellingPrice())

                    // Cancel කරපු එකක් බව හඳුනාගන්න Code එකක්
                    .batchCode("CNL-" + transfer.getTransferNo())

                    .receivedAt(LocalDateTime.now())
                    .build();

            stockBatchRepository.save(cancelBatch);
        }

        // 5. UPDATE STATUS
        transfer.setStatus(StockTransferStatus.CANCELED);
        transfer.setCancelReason(request.getReason() != null ? request.getReason().trim() : "Cancelled by User");
        transfer.setCanceledAt(LocalDateTime.now());

        // Audit: කවුද Cancel කලේ?
        // ඔයාට Entity එකේ canceledByUserId කියලා field එකක් තියෙනවා නම් දාන්න.
        // transfer.setCanceledByUserId(user.getId());

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
        return transferRepository.findByToBranchIdAndStatusOrderByRequestedAtDesc(branchId, StockTransferStatus.IN_TRANSIT)
                .stream()
                .map(t -> buildResponse(t, transferItemRepository.findByTransferId(t.getId())))
                .toList();
    }

    // ✅ outgoing pending list (sender dashboard)
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

        // 1. Branch Names සොයා ගැනීම (Handling Optional)
        String fromBranchName = branchRepository.findById(transfer.getFromBranchId())
                .map(Branch::getName)
                .orElse("Unknown Branch"); // Branch එක Delete කරලා නම් හෝ නැත්නම්

        String toBranchName = branchRepository.findById(transfer.getToBranchId())
                .map(Branch::getName)
                .orElse("Unknown Branch");

        // 2. User Names සොයා ගැනීම (Optional - අවශ්‍ය නම් විතරක් දාගන්න)
        String reqUserName = userRepository.findById(transfer.getRequestedByUserId())
                .map(User::getUsername) // හෝ getName()
                .orElse("Unknown User");

        String recUserName = null;
        if (transfer.getReceivedByUserId() != null) {
            recUserName = userRepository.findById(transfer.getReceivedByUserId())
                    .map(User::getUsername)
                    .orElse("Unknown User");
        }

        // 3. Items Map කිරීම
        List<StockTransferItemResponse> itemResponses = items.stream()
                .map(i -> StockTransferItemResponse.builder()
                        .itemId(i.getItemId())
                        .barcode(i.getBarcode())   // StockTransferItem table එකේ save කරලා තියෙන්න ඕන
                        .itemName(i.getItemName()) // StockTransferItem table එකේ save කරලා තියෙන්න ඕන
                        .qty(i.getQty())
                        .build())
                .toList();

        // 4. Final Response Build කිරීම
        return StockTransferResponse.builder()
                .id(transfer.getId())
                .transferNo(transfer.getTransferNo())

                // IDs
                .fromBranchId(transfer.getFromBranchId())
                .toBranchId(transfer.getToBranchId())

                // ✨ Names (Frontend එකේ පෙන්නන්න ලේසියි)
                .fromBranchName(fromBranchName)
                .toBranchName(toBranchName)

                .status(transfer.getStatus())

                // Users
                .requestedByUserId(transfer.getRequestedByUserId())
                .requestedByUserName(reqUserName) // ✨

                .receivedByUserId(transfer.getReceivedByUserId())
                .receivedByUserName(recUserName)  // ✨

                .note(transfer.getNote())
                .cancelReason(transfer.getCancelReason())
                .requestedAt(transfer.getRequestedAt())
                .receivedAt(transfer.getReceivedAt())
                .canceledAt(transfer.getCanceledAt())
                .items(itemResponses)
                .build();
    }
}
