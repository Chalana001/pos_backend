package com.chala.posapp.service;

import com.chala.posapp.dto.order.LegacySalesImportCommitResponse;
import com.chala.posapp.dto.order.LegacySalesImportIssue;
import com.chala.posapp.dto.order.LegacySalesImportPreviewResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.OrderItemRepository;
import com.chala.posapp.repository.OrderRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.util.QuantityConversionUtil;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LegacySalesImportService {

    private static final DateTimeFormatter OLD_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final double TOTAL_MISMATCH_TOLERANCE = 0.05;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;

    @Transactional(readOnly = true)
    public LegacySalesImportPreviewResponse preview(MultipartFile salesFile, MultipartFile mappingFile, Long branchId, Long cashierUserId) {
        ImportPlan plan = buildPlan(salesFile, mappingFile, branchId, cashierUserId);
        return buildPreview(plan);
    }

    @Transactional
    public LegacySalesImportCommitResponse commit(MultipartFile salesFile, MultipartFile mappingFile, Long branchId, Long cashierUserId) {
        ImportPlan plan = buildPlan(salesFile, mappingFile, branchId, cashierUserId);
        LegacySalesImportPreviewResponse preview = buildPreview(plan);
        boolean hasErrors = preview.getIssues().stream().anyMatch(issue -> "ERROR".equals(issue.getSeverity()));
        if (hasErrors) {
            throw new BadRequestException("Legacy sales import has errors. Run preview and fix the reported issues first.");
        }

        List<String> invoiceNos = new ArrayList<>();
        int importedItems = 0;
        double importedGrandTotal = 0.0;

        for (LegacySale sale : plan.sales.values()) {
            if (!sale.errors.isEmpty()) {
                continue;
            }

            Order order = Order.builder()
                    .invoiceNo(sale.saleNo)
                    .clientSaleId("legacy:" + sale.internalSaleId)
                    .branchId(plan.branch.getId())
                    .receiptBranchName(plan.branch.getName())
                    .receiptBranchAddress(plan.branch.getAddress())
                    .receiptBranchPhone(plan.branch.getPhone())
                    .receiptBranchLogo(plan.branch.getLogo())
                    .cashierUserId(plan.cashier.getId())
                    .customerId(null)
                    .orderType(OrderType.CASH)
                    .paymentMethod(sale.paymentMethod)
                    .saleMode(resolveSaleMode(sale.orderType))
                    .status(OrderStatus.COMPLETED)
                    .subTotal(roundMoney(sale.lineTotalSum))
                    .billDiscount(roundMoney(Math.max(0.0, sale.lineTotalSum - sale.totalPayable)))
                    .promotionDiscountTotal(0.0)
                    .grandTotal(roundMoney(sale.totalPayable))
                    .paidAmount(roundMoney(Math.min(sale.totalPayable, sale.paidAmount)))
                    .dueAmount(roundMoney(Math.max(0.0, sale.totalPayable - sale.paidAmount)))
                    .salePaidAmount(roundMoney(Math.min(sale.totalPayable, sale.paidAmount)))
                    .saleDueAmount(roundMoney(Math.max(0.0, sale.totalPayable - sale.paidAmount)))
                    .note(buildOrderNote(sale))
                    .createdAt(sale.soldAt)
                    .offlineSoldAt(sale.soldAt)
                    .importedAt(LocalDateTime.now())
                    .offlineImported(true)
                    .build();

            Order savedOrder = orderRepository.save(order);
            List<OrderItem> orderItems = new ArrayList<>();

            for (LegacySaleLine line : sale.lines) {
                Item item = line.resolvedItem;
                BigDecimal displayQty = line.qty;
                int normalizedQty = QuantityConversionUtil.normalizeSaleQuantity(item, displayQty, item.getDefaultUnit());
                double costPrice = item.getCostPrice() == null ? 0.0 : item.getCostPrice().doubleValue();
                double lineCost = QuantityConversionUtil.calculateActualAmount(item, BigDecimal.valueOf(costPrice), normalizedQty).doubleValue();

                orderItems.add(OrderItem.builder()
                        .orderId(savedOrder.getId())
                        .itemId(item.getId())
                        .batchId(null)
                        .barcode(item.getBarcode())
                        .itemName(item.getName())
                        .itemType(item.getItemType())
                        .qty(normalizedQty)
                        .displayQty(displayQty)
                        .qtyUnit(item.getDefaultUnit())
                        .costPrice(costPrice)
                        .unitPrice(line.unitPrice)
                        .discountType(DiscountType.NONE)
                        .discountValue(0.0)
                        .promotionDiscountAmount(0.0)
                        .finalUnitPrice(line.unitPrice)
                        .lineCost(lineCost)
                        .lineTotal(line.lineTotal)
                        .build());
            }

            orderItemRepository.saveAll(orderItems);
            importedItems += orderItems.size();
            importedGrandTotal += sale.totalPayable;
            invoiceNos.add(savedOrder.getInvoiceNo());
        }

        return LegacySalesImportCommitResponse.builder()
                .importedSales(invoiceNos.size())
                .importedItems(importedItems)
                .importedGrandTotal(roundMoney(importedGrandTotal))
                .invoiceNos(invoiceNos)
                .build();
    }

    @Transactional
    public int repairImportedSaleDates(Long branchId) {
        Branch branch = resolveBranch(branchId);
        return orderRepository.repairLegacyImportedCreatedAt(branch.getId());
    }

    private ImportPlan buildPlan(MultipartFile salesFile, MultipartFile mappingFile, Long branchId, Long cashierUserId) {
        Branch branch = resolveBranch(branchId);
        User cashier = resolveCashier(cashierUserId, branch.getId());
        Map<String, String> cMappings = readMappingFile(mappingFile);
        List<CsvRow> rows = readCsvRows(salesFile);
        validateRequiredColumns(rows, List.of(
                "sn", "sale_no", "internal_sale_id", "order_type", "date_time", "customer",
                "total_payable", "payment_method", "added_by", "item_name", "qty", "unit_price", "line_total"
        ), "sales file");

        Map<String, List<Item>> itemMap = itemRepository.findAll().stream()
                .collect(Collectors.groupingBy(item -> normalizeName(item.getName()), LinkedHashMap::new, Collectors.toList()));
        Map<String, List<Item>> looseItemMap = itemRepository.findAll().stream()
                .collect(Collectors.groupingBy(item -> normalizeLooseName(item.getName()), LinkedHashMap::new, Collectors.toList()));

        Map<String, LegacySale> sales = new LinkedHashMap<>();
        List<LegacySalesImportIssue> issues = new ArrayList<>();
        Set<String> mappedLegacyLineKeys = new HashSet<>();

        for (CsvRow csvRow : rows) {
            String saleNo = csvRow.value("sale_no");
            if (saleNo.isBlank()) {
                issues.add(issue("ERROR", "MISSING_SALE_NO", null, csvRow.rowNumber, null, "sale_no is required"));
                continue;
            }

            LegacySale sale = sales.computeIfAbsent(saleNo, key -> buildSale(csvRow));
            String oldItemName = csvRow.value("item_name");
            String resolvedName = oldItemName;
            boolean mappedLegacyItem = false;
            String configuredMapping = cMappings.get(cMappingKey(oldItemName, csvRow.value("unit_price")));
            if (configuredMapping != null && !configuredMapping.isBlank()) {
                resolvedName = configuredMapping;
                mappedLegacyItem = true;
                mappedLegacyLineKeys.add(csvRow.rowNumber + ":" + oldItemName);
            }

            if (isLegacyShortcutItem(oldItemName) && !mappedLegacyItem) {
                String mappingKey = cMappingKey(csvRow.value("old_item_name").isBlank() ? oldItemName : csvRow.value("old_item_name"), csvRow.value("unit_price"));
                resolvedName = cMappings.get(mappingKey);
                if (resolvedName == null || resolvedName.isBlank()) {
                    LegacySalesImportIssue issue = issue("ERROR", "MISSING_SHORTCUT_MAPPING", saleNo, csvRow.rowNumber, oldItemName,
                            "Missing mapped_item_name for " + oldItemName + " at unit price " + csvRow.value("unit_price"));
                    sale.errors.add(issue);
                    issues.add(issue);
                    continue;
                }
                mappedLegacyItem = true;
                mappedLegacyLineKeys.add(csvRow.rowNumber + ":" + oldItemName);
            }

            String normalizedResolvedName = normalizeName(resolvedName);
            List<Item> matches = itemMap.getOrDefault(normalizedResolvedName, List.of());
            boolean looseMatched = false;
            if (matches.isEmpty()) {
                matches = looseItemMap.getOrDefault(normalizeLooseName(resolvedName), List.of());
                looseMatched = !matches.isEmpty();
            }
            if (matches.isEmpty()) {
                LegacySalesImportIssue issue = issue("ERROR", "UNMATCHED_ITEM", saleNo, csvRow.rowNumber, resolvedName,
                        "No item found with name: " + resolvedName);
                sale.errors.add(issue);
                issues.add(issue);
                continue;
            }
            Item resolvedItem = matches.stream()
                    .min(Comparator.comparing(Item::getId))
                    .orElseThrow();
            if (matches.size() > 1) {
                LegacySalesImportIssue issue = issue("WARN", "DUPLICATE_ITEM_NAME", saleNo, csvRow.rowNumber, resolvedName,
                        "Multiple items found with this name; using item ID " + resolvedItem.getId() + ": " + resolvedName);
                sale.warnings.add(issue);
                issues.add(issue);
            }
            if (looseMatched) {
                LegacySalesImportIssue issue = issue("WARN", "LOOSE_ITEM_NAME_MATCH", saleNo, csvRow.rowNumber, resolvedName,
                        "Matched by normalized name to item ID " + resolvedItem.getId() + ": " + resolvedItem.getName());
                sale.warnings.add(issue);
                issues.add(issue);
            }

            BigDecimal qty = parseDecimal(csvRow.value("qty"), "qty", csvRow.rowNumber);
            double unitPrice = parseMoney(csvRow.value("unit_price"));
            double lineTotal = parseMoney(csvRow.value("line_total"));
            sale.lineTotalSum += lineTotal;
            sale.lines.add(LegacySaleLine.builder()
                    .rowNumber(csvRow.rowNumber)
                    .oldItemName(oldItemName)
                    .resolvedItemName(resolvedName)
                    .resolvedItem(resolvedItem)
                    .qty(qty)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .mappedLegacyItem(mappedLegacyItem)
                    .build());
        }

        for (LegacySale sale : sales.values()) {
            orderRepository.findByInvoiceNo(sale.saleNo).ifPresent(existing -> {
                LegacySalesImportIssue issue = issue("ERROR", "DUPLICATE_INVOICE", sale.saleNo, null, null,
                        "Invoice already exists: " + sale.saleNo);
                sale.errors.add(issue);
                issues.add(issue);
            });

            orderRepository.findByClientSaleId("legacy:" + sale.internalSaleId).ifPresent(existing -> {
                LegacySalesImportIssue issue = issue("ERROR", "DUPLICATE_CLIENT_SALE", sale.saleNo, null, null,
                        "Legacy sale already imported: " + sale.internalSaleId);
                sale.errors.add(issue);
                issues.add(issue);
            });

            if (Math.abs(sale.lineTotalSum - sale.totalPayable) > TOTAL_MISMATCH_TOLERANCE) {
                LegacySalesImportIssue issue = issue("WARN", "TOTAL_MISMATCH", sale.saleNo, null, null,
                        "Item line total " + roundMoney(sale.lineTotalSum) + " does not match sale total " + roundMoney(sale.totalPayable));
                sale.warnings.add(issue);
                issues.add(issue);
            }
        }

        return new ImportPlan(rows.size(), branch, cashier, sales, issues, mappedLegacyLineKeys.size());
    }

    private LegacySalesImportPreviewResponse buildPreview(ImportPlan plan) {
        int blockedSales = (int) plan.sales.values().stream().filter(sale -> !sale.errors.isEmpty()).count();
        int totalItems = plan.sales.values().stream().mapToInt(sale -> sale.lines.size()).sum();
        int duplicateSales = (int) plan.issues.stream()
                .filter(issue -> "DUPLICATE_INVOICE".equals(issue.getCode()) || "DUPLICATE_CLIENT_SALE".equals(issue.getCode()))
                .count();
        int totalMismatchWarnings = (int) plan.issues.stream().filter(issue -> "TOTAL_MISMATCH".equals(issue.getCode())).count();
        List<String> unmatchedItems = plan.issues.stream()
                .filter(issue -> "UNMATCHED_ITEM".equals(issue.getCode()) || "DUPLICATE_ITEM_NAME".equals(issue.getCode()))
                .map(LegacySalesImportIssue::getItemName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        double sourceGrandTotal = plan.sales.values().stream().mapToDouble(sale -> sale.totalPayable).sum();
        double importGrandTotal = plan.sales.values().stream()
                .filter(sale -> sale.errors.isEmpty())
                .mapToDouble(sale -> sale.totalPayable)
                .sum();

        return LegacySalesImportPreviewResponse.builder()
                .csvRows(plan.csvRows)
                .totalSales(plan.sales.size())
                .importableSales(plan.sales.size() - blockedSales)
                .blockedSales(blockedSales)
                .totalItems(totalItems)
                .mappedLegacyItems(plan.mappedLegacyItems)
                .unmatchedItemNames(unmatchedItems.size())
                .duplicateSales(duplicateSales)
                .totalMismatchWarnings(totalMismatchWarnings)
                .sourceGrandTotal(roundMoney(sourceGrandTotal))
                .importGrandTotal(roundMoney(importGrandTotal))
                .unmatchedItems(unmatchedItems)
                .issues(plan.issues)
                .build();
    }

    private Branch resolveBranch(Long branchId) {
        User user = securityUtils.getCurrentUser();
        Long resolvedBranchId = branchId;
        if (!securityUtils.isAdminLike(user)) {
            if (user.getBranchId() == null) {
                throw new NotAssignedException("User branch not assigned");
            }
            resolvedBranchId = user.getBranchId();
        }
        if (resolvedBranchId == null) {
            throw new BadRequestException("branchId is required");
        }
        if (!securityUtils.isAdminLike(user) && !resolvedBranchId.equals(user.getBranchId())) {
            throw new BadRequestException("Cannot import sales for another branch");
        }
        return branchRepository.findById(resolvedBranchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
    }

    private User resolveCashier(Long cashierUserId, Long branchId) {
        User loggedUser = securityUtils.getCurrentUser();
        Long resolvedCashierId = cashierUserId == null ? loggedUser.getId() : cashierUserId;
        User cashier = userRepository.findById(resolvedCashierId)
                .orElseThrow(() -> new ResourceNotFoundException("Cashier user not found"));
        if (cashier.getBranchId() != null && !cashier.getBranchId().equals(branchId) && cashier.getRole() != Role.ADMIN) {
            throw new BadRequestException("Cashier does not belong to the selected branch");
        }
        return cashier;
    }

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() / securityUtils.isAdminLike() — use SecurityUtils instead

    private Map<String, String> readMappingFile(MultipartFile mappingFile) {
        if (mappingFile == null || mappingFile.isEmpty()) {
            return Map.of();
        }

        List<CsvRow> rows = readCsvRows(mappingFile);
        validateRequiredColumns(rows, List.of("old_item_name", "unit_price", "mapped_item_name"), "mapping file");
        Map<String, String> mappings = new LinkedHashMap<>();
        for (CsvRow row : rows) {
            String oldName = row.value("old_item_name");
            String unitPrice = row.value("unit_price");
            String mappedName = row.value("mapped_item_name");
            if (!oldName.isBlank() && !unitPrice.isBlank() && !mappedName.isBlank()) {
                mappings.put(cMappingKey(oldName, unitPrice), mappedName.trim());
            }
        }
        return mappings;
    }

    private LegacySale buildSale(CsvRow row) {
        double totalPayable = parseMoney(row.value("total_payable"));
        return LegacySale.builder()
                .saleNo(row.value("sale_no"))
                .internalSaleId(row.value("internal_sale_id"))
                .orderType(row.value("order_type"))
                .customer(row.value("customer"))
                .paymentMethod(normalizePaymentMethod(row.value("payment_method")))
                .addedBy(row.value("added_by"))
                .soldAt(parseDate(row.value("date_time"), row.rowNumber))
                .totalPayable(totalPayable)
                .paidAmount(parsePaidAmount(row.value("payment_method"), totalPayable))
                .build();
    }

    private List<CsvRow> readCsvRows(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("CSV file is required");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            List<List<String>> records = parseCsv(reader);
            if (records.isEmpty()) {
                throw new BadRequestException("CSV file is empty");
            }
            List<String> headers = records.get(0).stream()
                    .map(this::normalizeHeader)
                    .toList();
            List<CsvRow> rows = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                List<String> record = records.get(i);
                if (record.stream().allMatch(value -> value == null || value.isBlank())) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    values.put(headers.get(c), c < record.size() ? stripBom(record.get(c).trim()) : "");
                }
                rows.add(new CsvRow(i + 1, values));
            }
            return rows;
        } catch (IOException e) {
            throw new BadRequestException("Failed to read CSV file");
        }
    }

    private List<List<String>> parseCsv(BufferedReader reader) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentValue = new StringBuilder();
        boolean inQuotes = false;
        int ch;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;
            if (c == '"') {
                reader.mark(1);
                int next = reader.read();
                if (inQuotes && next == '"') {
                    currentValue.append('"');
                } else {
                    inQuotes = !inQuotes;
                    if (next != -1) {
                        reader.reset();
                    }
                }
            } else if (c == ',' && !inQuotes) {
                currentRow.add(currentValue.toString());
                currentValue.setLength(0);
            } else if ((c == '\n' || c == '\r') && !inQuotes) {
                if (c == '\r') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.reset();
                    }
                }
                currentRow.add(currentValue.toString());
                currentValue.setLength(0);
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            } else {
                currentValue.append(c);
            }
        }
        if (!currentRow.isEmpty() || currentValue.length() > 0) {
            currentRow.add(currentValue.toString());
            rows.add(currentRow);
        }
        return rows;
    }

    private void validateRequiredColumns(List<CsvRow> rows, List<String> requiredColumns, String label) {
        if (rows.isEmpty()) {
            throw new BadRequestException(label + " has no data rows");
        }
        Set<String> columns = rows.get(0).values.keySet();
        for (String column : requiredColumns) {
            if (!columns.contains(normalizeHeader(column))) {
                throw new BadRequestException("Missing required " + label + " column: " + column);
            }
        }
    }

    private String normalizeHeader(String header) {
        return stripBom(header == null ? "" : header).trim().replace(" ", "_").toLowerCase(Locale.ROOT);
    }

    private String stripBom(String value) {
        return value == null ? "" : value.replace("\uFEFF", "");
    }

    private boolean isLegacyShortcutItem(String itemName) {
        String normalized = normalizeName(itemName);
        return normalized.matches("^[a-z] (large|medium|small|regular)$");
    }

    private String cMappingKey(String itemName, String unitPrice) {
        return normalizeName(itemName) + "|" + roundMoney(parseMoney(unitPrice));
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeLooseName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private LocalDateTime parseDate(String value, int rowNumber) {
        try {
            return LocalDateTime.parse(value.trim(), OLD_DATE_FORMAT);
        } catch (Exception e) {
            throw new BadRequestException("Invalid date_time at row " + rowNumber + ": " + value);
        }
    }

    private BigDecimal parseDecimal(String value, String field, int rowNumber) {
        try {
            return new BigDecimal(cleanMoney(value));
        } catch (Exception e) {
            throw new BadRequestException("Invalid " + field + " at row " + rowNumber + ": " + value);
        }
    }

    private double parseMoney(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        return roundMoney(Double.parseDouble(cleanMoney(value)));
    }

    private String cleanMoney(String value) {
        return value == null ? "0" : value.replace(",", "").trim();
    }

    private double parsePaidAmount(String paymentMethod, double fallback) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return fallback;
        }
        double total = 0.0;
        for (String part : paymentMethod.split("[|;]")) {
            int colon = part.lastIndexOf(':');
            if (colon >= 0 && colon < part.length() - 1) {
                try {
                    total += parseMoney(part.substring(colon + 1));
                } catch (Exception ignored) {
                    // Ignore malformed payment fragments and use fallback below.
                }
            }
        }
        return total > 0 ? total : fallback;
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "CASH";
        }
        String method = paymentMethod.split(":")[0].trim().toUpperCase(Locale.ROOT);
        return method.replace(" ", "_");
    }

    private SaleMode resolveSaleMode(String orderType) {
        if (orderType != null && orderType.trim().equalsIgnoreCase("Dine In")) {
            return SaleMode.DINE_IN;
        }
        return SaleMode.TAKEAWAY;
    }

    private String buildOrderNote(LegacySale sale) {
        return "Legacy import. Old internal_sale_id=" + sale.internalSaleId
                + ", old customer=" + nullToBlank(sale.customer)
                + ", old cashier=" + nullToBlank(sale.addedBy);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private double roundMoney(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private LegacySalesImportIssue issue(String severity, String code, String saleNo, Integer rowNumber, String itemName, String message) {
        return LegacySalesImportIssue.builder()
                .severity(severity)
                .code(code)
                .saleNo(saleNo)
                .rowNumber(rowNumber)
                .itemName(itemName)
                .message(message)
                .build();
    }

    private record CsvRow(int rowNumber, Map<String, String> values) {
        private String value(String key) {
            return values.getOrDefault(key == null ? "" : key.trim().replace(" ", "_").toLowerCase(Locale.ROOT), "");
        }
    }

    private record ImportPlan(
            int csvRows,
            Branch branch,
            User cashier,
            Map<String, LegacySale> sales,
            List<LegacySalesImportIssue> issues,
            int mappedLegacyItems
    ) {
    }

    @lombok.Data
    @lombok.Builder
    private static class LegacySale {
        private String saleNo;
        private String internalSaleId;
        private String orderType;
        private String customer;
        private String paymentMethod;
        private String addedBy;
        private LocalDateTime soldAt;
        private double totalPayable;
        private double paidAmount;
        @lombok.Builder.Default
        private double lineTotalSum = 0.0;
        @lombok.Builder.Default
        private List<LegacySaleLine> lines = new ArrayList<>();
        @lombok.Builder.Default
        private List<LegacySalesImportIssue> errors = new ArrayList<>();
        @lombok.Builder.Default
        private List<LegacySalesImportIssue> warnings = new ArrayList<>();
    }

    @lombok.Data
    @lombok.Builder
    private static class LegacySaleLine {
        private int rowNumber;
        private String oldItemName;
        private String resolvedItemName;
        private Item resolvedItem;
        private BigDecimal qty;
        private double unitPrice;
        private double lineTotal;
        private boolean mappedLegacyItem;
    }
}
