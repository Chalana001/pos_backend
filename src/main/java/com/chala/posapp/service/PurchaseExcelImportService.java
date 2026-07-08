package com.chala.posapp.service;

import com.chala.posapp.dto.purchase.PurchaseImportLookupRequest;
import com.chala.posapp.dto.purchase.PurchaseImportLookupResponse;
import com.chala.posapp.dto.purchase.PurchaseImportPreviewResponse;
import com.chala.posapp.dto.purchase.PurchaseImportRowData;
import com.chala.posapp.dto.purchase.PurchaseImportRowStatus;
import com.chala.posapp.entity.Item;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PurchaseExcelImportService {

    private static final String SHEET_NAME = "PurchaseItems";
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "barcode",
            "name",
            "costPrice",
            "sellPrice",
            "qty",
            "expiry"
    );
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ItemRepository itemRepository;

    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            createHeaderRow(workbook, sheet);
            createSampleRows(sheet);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BadRequestException("Failed to generate purchase import template");
        }
    }

    @Transactional(readOnly = true)
    public PurchaseImportPreviewResponse previewFromFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Excel file is required");
        }

        List<PurchaseImportRowData> rows = parseWorkbook(file);
        for (PurchaseImportRowData row : rows) {
            if (row.getStatus() == PurchaseImportRowStatus.INVALID) {
                continue;
            }
            applyLookup(row);
        }
        return buildResponse(rows);
    }

    @Transactional(readOnly = true)
    public PurchaseImportLookupResponse lookupSingleRow(PurchaseImportLookupRequest req) {
        if (req == null) {
            throw new BadRequestException("Lookup request is required");
        }

        PurchaseImportRowData row = PurchaseImportRowData.builder()
                .rowNumber(req.getRowNumber())
                .barcode(normalizeBlankToNull(req.getBarcode()))
                .name(normalizeBlankToNull(req.getName()))
                .costPrice(req.getCostPrice())
                .sellPrice(req.getSellPrice())
                .qty(req.getQty())
                .expiry(req.getExpiry())
                .build();

        validatePricesAndQty(row);
        if (row.getStatus() != PurchaseImportRowStatus.INVALID) {
            applyLookup(row);
        }
        return PurchaseImportLookupResponse.builder().row(row).build();
    }

    // --- core lookup ----------------------------------------------------

    private void applyLookup(PurchaseImportRowData row) {
        String barcode = normalizeBlankToNull(row.getBarcode());
        String name = normalizeBlankToNull(row.getName());

        if (barcode == null && name == null) {
            row.setStatus(PurchaseImportRowStatus.INVALID);
            row.setMessage(joinMessage(row.getMessage(), "barcode or name is required"));
            return;
        }

        if (barcode != null) {
            Optional<Item> match = itemRepository.findByBarcode(barcode);
            if (match.isPresent()) {
                applyMatch(row, match.get());
                if (name != null && !name.equalsIgnoreCase(match.get().getName())) {
                    row.setMessage(joinMessage(row.getMessage(),
                            "Name in Excel (" + name + ") differs from item name (" + match.get().getName() + ")"));
                }
                return;
            }
            // barcode given but not found — fall through to name lookup only if name provided
            if (name == null) {
                row.setStatus(PurchaseImportRowStatus.NOT_FOUND);
                row.setMessage("No item with barcode " + barcode);
                return;
            }
        }

        // name-only lookup
        List<Item> nameMatches = itemRepository.findAllByNameIgnoreCase(name);
        if (nameMatches.isEmpty()) {
            row.setStatus(PurchaseImportRowStatus.NOT_FOUND);
            String msg = barcode != null
                    ? "Neither barcode " + barcode + " nor name '" + name + "' matched any item"
                    : "No item with name '" + name + "'";
            row.setMessage(msg);
            return;
        }
        if (nameMatches.size() > 1) {
            row.setStatus(PurchaseImportRowStatus.AMBIGUOUS);
            row.setMessage("Name '" + name + "' matched " + nameMatches.size() + " items — enter a barcode to disambiguate");
            return;
        }
        applyMatch(row, nameMatches.get(0));
    }

    private void applyMatch(PurchaseImportRowData row, Item item) {
        row.setMatchedItemId(item.getId());
        row.setMatchedName(item.getName());
        row.setMatchedBarcode(item.getBarcode());
        row.setQtyUnit(item.getDefaultUnit());
        row.setStatus(PurchaseImportRowStatus.READY);
        row.setMessage("Ready");
    }

    private void validatePricesAndQty(PurchaseImportRowData row) {
        List<String> errors = new ArrayList<>();
        if (row.getCostPrice() == null || row.getCostPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("costPrice must be 0 or greater");
        }
        if (row.getSellPrice() == null || row.getSellPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("sellPrice must be 0 or greater");
        }
        if (row.getQty() == null || row.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("qty must be greater than zero");
        }
        if (!errors.isEmpty()) {
            row.setStatus(PurchaseImportRowStatus.INVALID);
            row.setMessage(String.join("; ", errors));
        }
    }

    // --- workbook parsing -----------------------------------------------

    private List<PurchaseImportRowData> parseWorkbook(MultipartFile file) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            }
            if (sheet == null) {
                throw new BadRequestException("Workbook does not contain any sheet");
            }

            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> headers = readHeaders(sheet, formatter);

            List<PurchaseImportRowData> rows = new ArrayList<>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (isBlankRow(row, headers, formatter)) {
                    continue;
                }
                rows.add(buildRow(row, rowIndex + 1, headers, formatter));
            }

            if (rows.isEmpty()) {
                throw new BadRequestException("Excel sheet does not contain any data rows");
            }
            return rows;
        } catch (IOException ex) {
            throw new BadRequestException("Failed to read Excel file");
        }
    }

    private PurchaseImportRowData buildRow(Row row, int rowNumber, Map<String, Integer> headers, DataFormatter formatter) {
        PurchaseImportRowData data = PurchaseImportRowData.builder()
                .rowNumber(rowNumber)
                .barcode(normalizeBlankToNull(readCell(row, headers.get("barcode"), formatter)))
                .name(normalizeBlankToNull(readCell(row, headers.get("name"), formatter)))
                .costPrice(parseDecimalOrNull(readCell(row, headers.get("costPrice"), formatter)))
                .sellPrice(parseDecimalOrNull(readCell(row, headers.get("sellPrice"), formatter)))
                .qty(parseDecimalOrNull(readCell(row, headers.get("qty"), formatter)))
                .expiry(parseDateOrNull(readCell(row, headers.get("expiry"), formatter)))
                .build();

        validatePricesAndQty(data);
        return data;
    }

    private Map<String, Integer> readHeaders(Sheet sheet, DataFormatter formatter) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new BadRequestException("Header row is missing");
        }

        Map<String, Integer> headers = new HashMap<>();
        for (Cell cell : headerRow) {
            String headerName = formatter.formatCellValue(cell).trim();
            if (!headerName.isEmpty()) {
                headers.put(headerName, cell.getColumnIndex());
            }
        }

        for (String required : List.of("costPrice", "sellPrice", "qty")) {
            if (!headers.containsKey(required)) {
                throw new BadRequestException("Missing required column: " + required);
            }
        }
        if (!headers.containsKey("barcode") && !headers.containsKey("name")) {
            throw new BadRequestException("Sheet must include either 'barcode' or 'name' column");
        }
        return headers;
    }

    private boolean isBlankRow(Row row, Map<String, Integer> headers, DataFormatter formatter) {
        if (row == null) {
            return true;
        }
        for (Integer columnIndex : headers.values()) {
            if (!readCell(row, columnIndex, formatter).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String readCell(Row row, Integer columnIndex, DataFormatter formatter) {
        if (row == null || columnIndex == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private BigDecimal parseDecimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), ISO_DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String joinMessage(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        if (existing.equals(addition)) {
            return existing;
        }
        return existing + "; " + addition;
    }

    private PurchaseImportPreviewResponse buildResponse(List<PurchaseImportRowData> rows) {
        int ready = 0, notFound = 0, ambiguous = 0, invalid = 0;
        for (PurchaseImportRowData row : rows) {
            PurchaseImportRowStatus status = row.getStatus();
            if (status == PurchaseImportRowStatus.READY) ready++;
            else if (status == PurchaseImportRowStatus.NOT_FOUND) notFound++;
            else if (status == PurchaseImportRowStatus.AMBIGUOUS) ambiguous++;
            else if (status == PurchaseImportRowStatus.INVALID) invalid++;
        }
        return PurchaseImportPreviewResponse.builder()
                .totalRows(rows.size())
                .readyCount(ready)
                .notFoundCount(notFound)
                .ambiguousCount(ambiguous)
                .invalidCount(invalid)
                .rows(rows)
                .build();
    }

    // --- template -------------------------------------------------------

    private void createHeaderRow(Workbook workbook, Sheet sheet) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        headerStyle.setFont(font);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(TEMPLATE_HEADERS.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    private void createSampleRows(Sheet sheet) {
        // Columns: barcode(0), name(1), costPrice(2), sellPrice(3), qty(4), expiry(5)
        Row barcodeOnly = sheet.createRow(1);
        barcodeOnly.createCell(0).setCellValue("4901234567894");
        barcodeOnly.createCell(1).setCellValue("");
        barcodeOnly.createCell(2).setCellValue(100);
        barcodeOnly.createCell(3).setCellValue(150);
        barcodeOnly.createCell(4).setCellValue(10);
        barcodeOnly.createCell(5).setCellValue("");

        Row nameOnly = sheet.createRow(2);
        nameOnly.createCell(0).setCellValue("");
        nameOnly.createCell(1).setCellValue("Coca Cola 500ml");
        nameOnly.createCell(2).setCellValue(180);
        nameOnly.createCell(3).setCellValue(220);
        nameOnly.createCell(4).setCellValue(24);
        nameOnly.createCell(5).setCellValue("2027-01-31");

        Row both = sheet.createRow(3);
        both.createCell(0).setCellValue("4901234567900");
        both.createCell(1).setCellValue("Pepsi 1L");
        both.createCell(2).setCellValue(300);
        both.createCell(3).setCellValue(400);
        both.createCell(4).setCellValue(6);
        both.createCell(5).setCellValue("2027-03-15");
    }
}
