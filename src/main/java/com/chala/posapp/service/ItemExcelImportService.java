package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemCreateRequest;
import com.chala.posapp.dto.item.ItemImportIngredientData;
import com.chala.posapp.dto.item.ItemImportResponse;
import com.chala.posapp.dto.item.ItemImportRowData;
import com.chala.posapp.dto.item.ItemImportRowStatus;
import com.chala.posapp.dto.item.ItemIngredientRequest;
import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.RecipeIngredient;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import com.chala.posapp.util.QuantityConversionUtil;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemExcelImportService {

    private static final String SHEET_NAME = "Items";
    private static final String RECIPE_INGREDIENTS_SHEET_NAME = "Recipe Ingredients";
    private static final List<String> REQUIRED_HEADERS = List.of(
            "barcode",
            "name",
            "costPrice",
            "sellingPrice",
            "reorderLevel",
            "itemType",
            "defaultUnit",
            "active",
            "kotEnabled",
            "branchIds"
    );
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "importKey",
            "barcode",
            "name",
            "subCategoryId",
            "costPrice",
            "sellingPrice",
            "reorderLevel",
            "itemType",
            "defaultUnit",
            "active",
            "posVisible",
            "kotEnabled",
            "branchIds"
    );

    private final ItemService itemService;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final BranchRepository branchRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;

    @Transactional(readOnly = true)
    public ItemImportResponse previewItems(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Excel file is required");
        }
        return buildResponse(parseWorkbook(file));
    }

    @Transactional
    public ItemImportResponse importItems(MultipartFile file) {
        return commitRows(parseWorkbook(file));
    }

    @Transactional(readOnly = true)
    public ItemImportResponse previewRecipeIngredients(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Excel file is required");
        }
        return buildResponse(parseRecipeIngredientsWorkbook(file));
    }

    @Transactional
    public ItemImportResponse importRecipeIngredients(MultipartFile file) {
        return commitRows(parseRecipeIngredientsWorkbook(file));
    }

    @Transactional
    public ItemImportResponse commitRecipeIngredientRows(List<ItemImportRowData> inputRows) {
        return commitRows(inputRows);
    }

    @Transactional
    public ItemImportResponse commitRows(List<ItemImportRowData> inputRows) {
        if (inputRows == null || inputRows.isEmpty()) {
            throw new BadRequestException("Rows cannot be empty");
        }

        Map<Integer, ItemImportRowData> resultByRowNumber = new HashMap<>();
        Map<Integer, ValidationResult> readyRows = new HashMap<>();
        Map<String, Long> importedItemRefs = new HashMap<>();
        Set<String> selectedExplicitBarcodes = collectDuplicateCandidateBarcodes(inputRows);

        for (ItemImportRowData inputRow : inputRows) {
            boolean selected = inputRow.getSelected() == null || inputRow.getSelected();
            if (!selected) {
                resultByRowNumber.put(inputRow.getRowNumber(), copyRow(inputRow, ItemImportRowStatus.SKIPPED, "Row not selected for import"));
                continue;
            }

            boolean existingRecipeIngredientRow = inputRow.getExistingItemId() != null;
            String barcode = normalizeText(inputRow.getBarcode());
            if (!existingRecipeIngredientRow && !barcode.isBlank() && selectedExplicitBarcodes.contains(barcode.toUpperCase(Locale.ROOT))) {
                resultByRowNumber.put(inputRow.getRowNumber(), copyRow(inputRow, ItemImportRowStatus.ERROR, "Duplicate barcode found in selected rows: " + barcode));
                continue;
            }

            ValidationResult validation = validateAndResolve(inputRow);
            if (validation.row.getStatus() == ItemImportRowStatus.ERROR) {
                resultByRowNumber.put(inputRow.getRowNumber(), validation.row);
                continue;
            }
            readyRows.put(inputRow.getRowNumber(), validation);
        }

        for (ItemImportRowData inputRow : inputRows) {
            ValidationResult validation = readyRows.get(inputRow.getRowNumber());
            if (validation == null || validation.row.getExistingItemId() != null || validation.row.getItemType() == ItemType.RECIPE) {
                continue;
            }

            try {
                ItemResponse savedItem;
                if (validation.row.getExistingItemId() != null) {
                    Item existingItem = itemRepository.findById(validation.row.getExistingItemId())
                            .orElseThrow(() -> new BadRequestException("Recipe item not found: " + validation.row.getExistingItemId()));
                    if (existingItem.getItemType() != ItemType.RECIPE) {
                        throw new BadRequestException("recipeItemId must point to a RECIPE item");
                    }
                    savedItem = upsertRecipeIngredients(existingItem, validation.request.getIngredients() == null ? List.of() : validation.request.getIngredients());
                    validation.row.setMessage("Imported recipe ingredients");
                } else {
                    savedItem = itemService.createItem(validation.request);
                    validation.row.setMessage("Imported");
                    rememberImportedRefs(importedItemRefs, validation.row, savedItem.getId());
                }
                validation.row.setBarcode(savedItem.getBarcode());
                validation.row.setStatus(ItemImportRowStatus.IMPORTED);
                resultByRowNumber.put(inputRow.getRowNumber(), validation.row);
            } catch (RuntimeException ex) {
                validation.row.setStatus(ItemImportRowStatus.ERROR);
                validation.row.setMessage(ex.getMessage());
                resultByRowNumber.put(inputRow.getRowNumber(), validation.row);
            }
        }

        for (ItemImportRowData inputRow : inputRows) {
            ValidationResult validation = readyRows.get(inputRow.getRowNumber());
            if (validation == null || validation.row.getItemType() != ItemType.RECIPE) {
                continue;
            }

            List<String> ingredientErrors = new ArrayList<>();
            validation.request.setIngredients(resolveIngredientRequests(validation.row, importedItemRefs, ingredientErrors));
            if (!ingredientErrors.isEmpty()) {
                validation.row.setStatus(ItemImportRowStatus.ERROR);
                validation.row.setMessage(String.join("; ", ingredientErrors));
                resultByRowNumber.put(inputRow.getRowNumber(), validation.row);
                continue;
            }

            try {
                ItemResponse createdItem = itemService.createItem(validation.request);
                validation.row.setBarcode(createdItem.getBarcode());
                validation.row.setStatus(ItemImportRowStatus.IMPORTED);
                validation.row.setMessage("Imported");
                rememberImportedRefs(importedItemRefs, validation.row, createdItem.getId());
                resultByRowNumber.put(inputRow.getRowNumber(), validation.row);
            } catch (RuntimeException ex) {
                validation.row.setStatus(ItemImportRowStatus.ERROR);
                validation.row.setMessage(ex.getMessage());
                resultByRowNumber.put(inputRow.getRowNumber(), validation.row);
            }
        }

        List<ItemImportRowData> resultRows = inputRows.stream()
                .map(row -> resultByRowNumber.getOrDefault(row.getRowNumber(), copyRow(row, ItemImportRowStatus.SKIPPED, "Row not processed")))
                .toList();
        return buildResponse(resultRows);
    }

    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            createHeaderRow(workbook, sheet);
            createSampleRows(sheet);
            autosizeColumns(sheet, TEMPLATE_HEADERS.size());
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new BadRequestException("Failed to generate Excel template");
        }
    }

    public byte[] generateRecipeIngredientsTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            createRecipeIngredientTemplateSheet(workbook, true);
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new BadRequestException("Failed to generate recipe ingredients template");
        }
    }

    private List<ItemImportRowData> parseWorkbook(MultipartFile file) {
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
            List<ItemImportRowData> rows = new ArrayList<>();
            Set<String> seenBarcodes = new HashSet<>();

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (isBlankRow(row, headers, formatter)) {
                    continue;
                }

                ItemImportRowData rowData = fromWorkbookRow(row, rowIndex + 1, headers, formatter);
                String barcode = normalizeText(rowData.getBarcode());
                if (!barcode.isBlank()) {
                    String normalizedBarcode = barcode.toUpperCase(Locale.ROOT);
                    if (!seenBarcodes.add(normalizedBarcode)) {
                        rowData.setStatus(ItemImportRowStatus.ERROR);
                        rowData.setMessage("Duplicate barcode found in Excel file: " + barcode);
                    }
                }
                rows.add(rowData);
            }

            if (rows.isEmpty()) {
                throw new BadRequestException("Excel sheet does not contain item rows");
            }
            rows.addAll(parseRecipeIngredientSheet(workbook, formatter, rows));
            return rows;
        } catch (IOException ex) {
            throw new BadRequestException("Failed to read Excel file");
        }
    }

    private List<ItemImportRowData> parseRecipeIngredientsWorkbook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Excel file is required");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheet(RECIPE_INGREDIENTS_SHEET_NAME);
            if (sheet == null) {
                sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            }
            if (sheet == null) {
                throw new BadRequestException("Workbook does not contain any sheet");
            }

            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> headers = readRecipeIngredientHeaders(sheet, formatter);
            Map<Long, ItemImportRowData> recipeRowsByItemId = new HashMap<>();
            List<ItemImportRowData> syntheticErrors = new ArrayList<>();

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (isBlankRow(row, headers, formatter)) {
                    continue;
                }

                ItemImportIngredientData ingredient = fromRecipeIngredientRow(row, rowIndex + 1, headers, formatter);
                if (ingredient.getRecipeItemId() == null) {
                    syntheticErrors.add(ItemImportRowData.builder()
                            .rowNumber(ingredient.getRowNumber())
                            .selected(false)
                            .recipeIngredientOnly(true)
                            .name("Recipe ingredient row " + ingredient.getRowNumber())
                            .status(ItemImportRowStatus.ERROR)
                            .message("itemId is required")
                            .build());
                    continue;
                }

                ItemImportRowData recipeRow = recipeRowsByItemId.computeIfAbsent(
                        ingredient.getRecipeItemId(),
                        recipeItemId -> buildExistingRecipeIngredientRow(recipeItemId, ingredient.getRowNumber())
                );

                List<ItemImportIngredientData> ingredients = new ArrayList<>(
                        recipeRow.getIngredients() == null ? List.of() : recipeRow.getIngredients()
                );
                ingredients.add(ingredient);
                recipeRow.setIngredients(ingredients);

                List<String> previewErrors = validateExistingRecipeIngredientPreview(recipeRow, ingredient);
                if (recipeRow.getItemType() != ItemType.RECIPE) {
                    recipeRow.setStatus(ItemImportRowStatus.ERROR);
                    recipeRow.setMessage(joinMessages(recipeRow.getMessage(), List.of("Recipe ingredient row " + ingredient.getRowNumber() + " points to a non-recipe item")));
                } else if (!previewErrors.isEmpty()) {
                    recipeRow.setStatus(ItemImportRowStatus.ERROR);
                    recipeRow.setMessage(joinMessages(recipeRow.getMessage(), List.of("Recipe Ingredients row " + ingredient.getRowNumber() + ": " + String.join(", ", previewErrors))));
                } else if (recipeRow.getStatus() == ItemImportRowStatus.READY) {
                    recipeRow.setMessage("Ready (" + ingredients.size() + " ingredients)");
                }
            }

            List<ItemImportRowData> rows = new ArrayList<>(recipeRowsByItemId.values());
            validateDuplicateIngredientIds(rows);
            rows.addAll(syntheticErrors);
            if (rows.isEmpty()) {
                throw new BadRequestException("Excel sheet does not contain recipe ingredient rows");
            }
            return rows;
        } catch (IOException ex) {
            throw new BadRequestException("Failed to read Excel file");
        }
    }

    private List<ItemImportRowData> parseRecipeIngredientSheet(
            Workbook workbook,
            DataFormatter formatter,
            List<ItemImportRowData> itemRows
    ) {
        Sheet sheet = workbook.getSheet(RECIPE_INGREDIENTS_SHEET_NAME);
        if (sheet == null && workbook.getNumberOfSheets() > 1) {
            sheet = workbook.getSheetAt(1);
        }
        if (sheet == null || sheet.getPhysicalNumberOfRows() <= 1) {
            return List.of();
        }

        Map<String, Integer> headers = readRecipeIngredientHeaders(sheet, formatter);
        List<ItemImportRowData> syntheticErrors = new ArrayList<>();
        Map<Long, ItemImportRowData> existingRecipeRows = new HashMap<>();

        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isBlankRow(row, headers, formatter)) {
                continue;
            }

            ItemImportIngredientData ingredient = fromRecipeIngredientRow(row, rowIndex + 1, headers, formatter);
            ItemImportRowData recipeRow = findRecipeImportRow(itemRows, ingredient);
            if (recipeRow == null && ingredient.getRecipeItemId() != null) {
                recipeRow = existingRecipeRows.computeIfAbsent(
                        ingredient.getRecipeItemId(),
                        recipeItemId -> buildExistingRecipeIngredientRow(recipeItemId, ingredient.getRowNumber())
                );
            }
            if (recipeRow == null) {
                syntheticErrors.add(ItemImportRowData.builder()
                        .rowNumber(ingredient.getRowNumber())
                        .selected(false)
                        .name("Recipe ingredient row " + ingredient.getRowNumber())
                        .status(ItemImportRowStatus.ERROR)
                        .message("Recipe item not found in Items sheet")
                        .build());
                continue;
            }

            List<ItemImportIngredientData> ingredients = new ArrayList<>(
                    recipeRow.getIngredients() == null ? List.of() : recipeRow.getIngredients()
            );
            ingredients.add(ingredient);
            recipeRow.setIngredients(ingredients);
            List<String> previewErrors = validateIngredientPreview(ingredient);
            if (recipeRow.getItemType() != ItemType.RECIPE) {
                recipeRow.setStatus(ItemImportRowStatus.ERROR);
                recipeRow.setMessage(joinMessages(recipeRow.getMessage(), List.of("Recipe ingredient row " + ingredient.getRowNumber() + " points to a non-recipe item")));
            } else if (!previewErrors.isEmpty()) {
                recipeRow.setStatus(ItemImportRowStatus.ERROR);
                recipeRow.setMessage(joinMessages(recipeRow.getMessage(), List.of("Recipe Ingredients row " + ingredient.getRowNumber() + ": " + String.join(", ", previewErrors))));
            } else if (recipeRow.getStatus() == ItemImportRowStatus.READY) {
                recipeRow.setMessage("Ready (" + ingredients.size() + " ingredients)");
            }
        }

        List<ItemImportRowData> syntheticRows = new ArrayList<>(existingRecipeRows.values());
        syntheticRows.addAll(syntheticErrors);
        return syntheticRows;
    }

    private List<String> validateIngredientPreview(ItemImportIngredientData ingredient) {
        List<String> errors = new ArrayList<>();
        boolean hasIngredientRef = ingredient.getIngredient() != null
                || ingredient.getIngredientImportKey() != null
                || ingredient.getIngredientItemId() != null
                || ingredient.getIngredientBarcode() != null
                || ingredient.getIngredientName() != null;
        if (!hasIngredientRef) {
            errors.add("ingredient reference is required");
        }
        if (ingredient.getQuantity() == null || ingredient.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("quantity must be greater than zero");
        }
        if (ingredient.getQtyUnit() == null) {
            errors.add("qtyUnit is invalid");
        }
        return errors;
    }

    private List<String> validateExistingRecipeIngredientPreview(ItemImportRowData recipeRow, ItemImportIngredientData ingredient) {
        List<String> errors = new ArrayList<>(validateIngredientPreview(ingredient));
        if (!errors.isEmpty()) {
            return errors;
        }

        if (ingredient.getIngredientItemId() == null) {
            List<String> resolutionErrors = new ArrayList<>();
            Long resolvedIngredientId = resolveIngredientItemId(ingredient, Map.of(), resolutionErrors);
            if (resolvedIngredientId != null) {
                ingredient.setIngredientItemId(resolvedIngredientId);
            } else {
                errors.addAll(resolutionErrors);
                return errors;
            }
        }

        Optional<Item> ingredientItem = itemRepository.findById(ingredient.getIngredientItemId());
        if (ingredientItem.isEmpty()) {
            errors.add("ingredientId not found: " + ingredient.getIngredientItemId());
            return errors;
        }

        Item item = ingredientItem.get();
        if (!item.isActive()) {
            errors.add("ingredient item is inactive: " + item.getName());
        }
        if (Objects.equals(recipeRow.getExistingItemId(), item.getId())) {
            errors.add("recipe item cannot include itself as an ingredient");
        }
        if (item.getItemType() == ItemType.SERVICE || item.getItemType() == ItemType.RECIPE) {
            errors.add("ingredientId must point to a normal, weight or volume item");
        }
        try {
            QuantityConversionUtil.normalizeQuantity(item, ingredient.getQuantity(), ingredient.getQtyUnit());
        } catch (BadRequestException ex) {
            errors.add(ex.getMessage());
        }
        return errors;
    }

    private void validateDuplicateIngredientIds(List<ItemImportRowData> rows) {
        for (ItemImportRowData row : rows) {
            Set<Long> ingredientIds = new HashSet<>();
            List<String> duplicateMessages = new ArrayList<>();
            for (ItemImportIngredientData ingredient : row.getIngredients() == null ? List.<ItemImportIngredientData>of() : row.getIngredients()) {
                Long ingredientItemId = ingredient.getIngredientItemId();
                if (ingredientItemId != null && !ingredientIds.add(ingredientItemId)) {
                    duplicateMessages.add("Recipe Ingredients row " + ingredient.getRowNumber() + ": duplicate ingredientId " + ingredientItemId);
                }
            }
            if (!duplicateMessages.isEmpty()) {
                row.setStatus(ItemImportRowStatus.ERROR);
                row.setMessage(joinMessages(row.getMessage(), duplicateMessages));
            }
        }
    }

    private Map<String, Integer> readRecipeIngredientHeaders(Sheet sheet, DataFormatter formatter) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new BadRequestException("Recipe Ingredients header row is missing");
        }

        Map<String, Integer> headers = new HashMap<>();
        for (Cell cell : headerRow) {
            String headerName = formatter.formatCellValue(cell).trim();
            if (!headerName.isEmpty()) {
                headers.put(headerName, cell.getColumnIndex());
            }
        }

        if (findHeader(headers, "quantity", "qty") == null) {
            throw new BadRequestException("Missing required Recipe Ingredients column: quantity");
        }
        if (findHeader(headers, "qtyUnit", "unit") == null) {
            throw new BadRequestException("Missing required Recipe Ingredients column: qtyUnit or unit");
        }
        boolean hasRecipeRef = findHeader(headers, "itemId", "recipeItemId", "recipeId") != null
                || headers.containsKey("recipeImportKey")
                || headers.containsKey("recipeBarcode")
                || headers.containsKey("recipeName");
        if (!hasRecipeRef) {
            throw new BadRequestException("Missing required Recipe Ingredients column: itemId, recipeItemId, recipeImportKey, recipeBarcode, or recipeName");
        }
        boolean hasIngredientRef = headers.containsKey("ingredient")
                || headers.containsKey("ingredientImportKey")
                || findHeader(headers, "ingredientItemId", "ingredientId") != null
                || headers.containsKey("ingredientBarcode")
                || headers.containsKey("ingredientName");
        if (!hasIngredientRef) {
            throw new BadRequestException("Missing required Recipe Ingredients column: ingredientId, ingredientItemId, ingredient, ingredientBarcode, or ingredientName");
        }
        return headers;
    }

    private ItemImportIngredientData fromRecipeIngredientRow(Row row, int rowNumber, Map<String, Integer> headers, DataFormatter formatter) {
        Long recipeItemId = parseLongOrNull(readCell(row, findHeader(headers, "itemId", "recipeItemId", "recipeId"), formatter));
        Long ingredientItemId = parseLongOrNull(readCell(row, findHeader(headers, "ingredientItemId", "ingredientId"), formatter));

        return ItemImportIngredientData.builder()
                .rowNumber(rowNumber)
                .recipeItemId(recipeItemId)
                .recipeImportKey(normalizeBlankToNull(readCell(row, headers.get("recipeImportKey"), formatter)))
                .recipeBarcode(normalizeBlankToNull(readCell(row, headers.get("recipeBarcode"), formatter)))
                .recipeName(normalizeBlankToNull(readCell(row, headers.get("recipeName"), formatter)))
                .ingredient(normalizeBlankToNull(readCell(row, headers.get("ingredient"), formatter)))
                .ingredientImportKey(normalizeBlankToNull(readCell(row, headers.get("ingredientImportKey"), formatter)))
                .ingredientItemId(ingredientItemId)
                .ingredientBarcode(normalizeBlankToNull(readCell(row, headers.get("ingredientBarcode"), formatter)))
                .ingredientName(normalizeBlankToNull(readCell(row, headers.get("ingredientName"), formatter)))
                .quantity(parseDecimalOrNull(readCell(row, findHeader(headers, "quantity", "qty"), formatter)))
                .qtyUnit(parseEnumOrNull(readCell(row, findHeader(headers, "qtyUnit", "unit"), formatter), MeasurementUnit.class))
                .status(ItemImportRowStatus.READY)
                .message("Ready")
                .build();
    }

    private ItemImportRowData buildExistingRecipeIngredientRow(Long recipeItemId, int ingredientSheetRowNumber) {
        int syntheticRowNumber = 100000 + ingredientSheetRowNumber;
        Optional<Item> item = itemRepository.findById(recipeItemId);
        if (item.isEmpty()) {
            return ItemImportRowData.builder()
                    .rowNumber(syntheticRowNumber)
                    .selected(false)
                    .existingItemId(recipeItemId)
                    .recipeIngredientOnly(true)
                    .name("Recipe item " + recipeItemId)
                    .itemType(ItemType.RECIPE)
                    .status(ItemImportRowStatus.ERROR)
                    .message("recipeItemId not found: " + recipeItemId)
                    .build();
        }

        Item recipeItem = item.get();
        SubCategory subCategory = recipeItem.getSubCategory();
        Category category = subCategory == null ? null : subCategory.getCategory();
        ItemImportRowStatus status = recipeItem.getItemType() == ItemType.RECIPE
                ? ItemImportRowStatus.READY
                : ItemImportRowStatus.ERROR;
        String message = recipeItem.getItemType() == ItemType.RECIPE
                ? "Ready"
                : "recipeItemId must point to a RECIPE item";

        return ItemImportRowData.builder()
                .rowNumber(syntheticRowNumber)
                .selected(recipeItem.getItemType() == ItemType.RECIPE)
                .existingItemId(recipeItem.getId())
                .recipeIngredientOnly(true)
                .barcode(recipeItem.getBarcode())
                .name(recipeItem.getName())
                .categoryId(category == null ? null : category.getId())
                .categoryName(category == null ? null : category.getName())
                .subCategoryId(subCategory == null ? null : subCategory.getId())
                .subCategoryName(subCategory == null ? null : subCategory.getName())
                .costPrice(recipeItem.getCostPrice())
                .sellingPrice(recipeItem.getSellingPrice())
                .reorderLevel(BigDecimal.valueOf(recipeItem.getReorderLevel()))
                .itemType(recipeItem.getItemType())
                .defaultUnit(recipeItem.getDefaultUnit())
                .active(recipeItem.isActive())
                .posVisible(recipeItem.isPosVisible())
                .kotEnabled(recipeItem.isKotEnabled())
                .branchIds(List.of())
                .ingredients(List.of())
                .status(status)
                .message(message)
                .build();
    }

    private ItemImportRowData findRecipeImportRow(List<ItemImportRowData> itemRows, ItemImportIngredientData ingredient) {
        if (ingredient.getRecipeImportKey() != null) {
            Optional<ItemImportRowData> match = itemRows.stream()
                    .filter(row -> ingredient.getRecipeImportKey().equalsIgnoreCase(normalizeText(row.getImportKey())))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
        }
        if (ingredient.getRecipeBarcode() != null) {
            Optional<ItemImportRowData> match = itemRows.stream()
                    .filter(row -> ingredient.getRecipeBarcode().equalsIgnoreCase(normalizeText(row.getBarcode())))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
        }
        if (ingredient.getRecipeName() != null) {
            Optional<ItemImportRowData> match = itemRows.stream()
                    .filter(row -> ingredient.getRecipeName().equalsIgnoreCase(normalizeText(row.getName())))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
        }
        return null;
    }

    private ItemImportRowData fromWorkbookRow(Row row, int rowNumber, Map<String, Integer> headers, DataFormatter formatter) {
        List<String> parseErrors = new ArrayList<>();
        String rawSubCategory = readCell(row, headers.get("subCategory"), formatter);
        String rawSubCategoryId = readCell(row, headers.get("subCategoryId"), formatter);
        Long parsedSubCategoryId = null;
        String subCategoryNameFromIdColumn = null;
        if (!rawSubCategoryId.isBlank()) {
            try {
                parsedSubCategoryId = Long.valueOf(rawSubCategoryId.trim());
            } catch (NumberFormatException ex) {
                if (rawSubCategory.isBlank()) {
                    subCategoryNameFromIdColumn = rawSubCategoryId;
                } else {
                    parseErrors.add("subCategoryId is invalid");
                }
            }
        }

        ItemImportRowData rowData = ItemImportRowData.builder()
                .rowNumber(rowNumber)
                .selected(true)
                .importKey(normalizeBlankToNull(readCell(row, headers.get("importKey"), formatter)))
                .barcode(normalizeBlankToNull(readCell(row, headers.get("barcode"), formatter)))
                .name(normalizeBlankToNull(readCell(row, headers.get("name"), formatter)))
                .enteredMainCategory(normalizeBlankToNull(readCell(row, headers.get("mainCategory"), formatter)))
                .enteredSubCategory(normalizeBlankToNull(rawSubCategory.isBlank() ? subCategoryNameFromIdColumn : rawSubCategory))
                .subCategoryId(parsedSubCategoryId)
                .costPrice(parseDecimalOrNull(readCell(row, headers.get("costPrice"), formatter)))
                .sellingPrice(parseDecimalOrNull(readCell(row, headers.get("sellingPrice"), formatter)))
                .reorderLevel(parseDecimalOrNull(readCell(row, headers.get("reorderLevel"), formatter)))
                .itemType(parseEnumOrNull(readCell(row, headers.get("itemType"), formatter), ItemType.class))
                .defaultUnit(parseEnumOrNull(readCell(row, headers.get("defaultUnit"), formatter), MeasurementUnit.class))
                .active(parseBooleanOrDefault(readCell(row, headers.get("active"), formatter), true))
                .posVisible(parseBooleanOrDefault(readCell(row, headers.get("posVisible"), formatter), true))
                .kotEnabled(parseBooleanOrDefault(readCell(row, headers.get("kotEnabled"), formatter), false))
                .branchIds(parseBranchIds(readCell(row, headers.get("branchIds"), formatter)))
                .build();

        ValidationResult validationResult = validateAndResolve(rowData);
        if (!parseErrors.isEmpty()) {
            validationResult.row.setStatus(ItemImportRowStatus.ERROR);
            validationResult.row.setMessage(joinMessages(validationResult.row.getMessage(), parseErrors));
        }
        return validationResult.row;
    }

    private ValidationResult validateAndResolve(ItemImportRowData inputRow) {
        ItemImportRowData row = ItemImportRowData.builder()
                .rowNumber(inputRow.getRowNumber())
                .selected(inputRow.getSelected() == null || inputRow.getSelected())
                .existingItemId(inputRow.getExistingItemId())
                .recipeIngredientOnly(inputRow.getRecipeIngredientOnly())
                .importKey(normalizeBlankToNull(inputRow.getImportKey()))
                .barcode(normalizeBlankToNull(inputRow.getBarcode()))
                .name(normalizeBlankToNull(inputRow.getName()))
                .enteredMainCategory(normalizeBlankToNull(inputRow.getEnteredMainCategory()))
                .enteredSubCategory(normalizeBlankToNull(inputRow.getEnteredSubCategory()))
                .costPrice(inputRow.getCostPrice())
                .sellingPrice(inputRow.getSellingPrice())
                .reorderLevel(inputRow.getReorderLevel())
                .itemType(inputRow.getItemType())
                .defaultUnit(inputRow.getDefaultUnit())
                .active(inputRow.getActive() == null ? true : inputRow.getActive())
                .posVisible(inputRow.getPosVisible() == null ? true : inputRow.getPosVisible())
                .kotEnabled(inputRow.getKotEnabled() == null ? false : inputRow.getKotEnabled())
                .branchIds(inputRow.getBranchIds() == null ? List.of() : inputRow.getBranchIds().stream().distinct().toList())
                .ingredients(inputRow.getIngredients() == null ? List.of() : inputRow.getIngredients())
                .categoryId(inputRow.getCategoryId())
                .subCategoryId(inputRow.getSubCategoryId())
                .build();

        List<String> errors = new ArrayList<>();

        Category category = resolveCategory(row, errors);
        SubCategory subCategory = resolveSubCategory(row, category, errors);
        if (category == null && subCategory != null) {
            category = subCategory.getCategory();
        }

        if (row.getName() == null || row.getName().isBlank()) {
            errors.add("name is required");
        }
        if (row.getCostPrice() == null) {
            errors.add("costPrice is required");
        } else if (row.getCostPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("costPrice cannot be negative");
        }
        if (row.getSellingPrice() == null) {
            errors.add("sellingPrice is required");
        } else if (row.getSellingPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("sellingPrice cannot be negative");
        }
        if (row.getReorderLevel() == null && row.getItemType() == ItemType.RECIPE) {
            row.setReorderLevel(BigDecimal.ZERO);
        }
        if (row.getReorderLevel() == null) {
            errors.add("reorderLevel is required");
        } else if (row.getReorderLevel().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("reorderLevel cannot be negative");
        }
        if (row.getItemType() == null) {
            errors.add("itemType is invalid");
        }
        if (row.getDefaultUnit() == null) {
            errors.add("defaultUnit is invalid");
        }
        if (row.getActive() == null) {
            errors.add("active must be true or false");
        }
        if (row.getPosVisible() == null) {
            errors.add("posVisible must be true or false");
        }
        if (row.getKotEnabled() == null) {
            errors.add("kotEnabled must be true or false");
        }
        if (row.getBranchIds().stream().anyMatch(Objects::isNull)) {
            errors.add("branchIds must be comma-separated numbers");
        } else {
            for (Long branchId : row.getBranchIds()) {
                if (!branchRepository.existsById(branchId)) {
                    errors.add("Branch not found: " + branchId);
                }
            }
        }
        if (row.getItemType() == ItemType.SERVICE && row.getBranchIds().isEmpty()) {
            errors.add("Service items must be assigned to at least one branch");
        }
        if (row.getItemType() != null && row.getItemType() != ItemType.RECIPE && row.getIngredients() != null && !row.getIngredients().isEmpty()) {
            errors.add("Recipe ingredients can only be attached to RECIPE items");
        }

        if (row.getItemType() != null && row.getDefaultUnit() != null) {
            try {
                QuantityConversionUtil.normalizeItemUnit(row.getItemType(), row.getDefaultUnit());
            } catch (BadRequestException ex) {
                errors.add(ex.getMessage());
            }
        }

        row.setCategoryId(category != null ? category.getId() : null);
        row.setCategoryName(category != null ? category.getName() : null);
        row.setSubCategoryId(subCategory != null ? subCategory.getId() : null);
        row.setSubCategoryName(subCategory != null ? subCategory.getName() : null);

        if (!errors.isEmpty()) {
            row.setStatus(ItemImportRowStatus.ERROR);
            row.setMessage(String.join("; ", errors));
            return new ValidationResult(row, null);
        }

        ItemCreateRequest request = new ItemCreateRequest();
        request.setBarcode(row.getBarcode());
        request.setName(row.getName());
        request.setSubCategoryId(row.getSubCategoryId());
        request.setCostPrice(row.getCostPrice());
        request.setSellingPrice(row.getSellingPrice());
        request.setReorderLevel(row.getReorderLevel());
        request.setItemType(row.getItemType());
        request.setDefaultUnit(row.getDefaultUnit());
        request.setActive(row.getActive());
        request.setPosVisible(row.getPosVisible());
        request.setIsKotEnabled(row.getKotEnabled());
        request.setBranchIds(row.getBranchIds().isEmpty() ? null : row.getBranchIds());
        request.setIngredients(row.getIngredients() == null || row.getIngredients().isEmpty() ? null : List.of());

        row.setStatus(ItemImportRowStatus.READY);
        int ingredientCount = row.getIngredients() == null ? 0 : row.getIngredients().size();
        row.setMessage(ingredientCount > 0 ? "Ready (" + ingredientCount + " ingredients)" : "Ready");
        return new ValidationResult(row, request);
    }

    private Category resolveCategory(ItemImportRowData row, List<String> errors) {
        if (row.getCategoryId() != null) {
            Optional<Category> category = categoryRepository.findById(row.getCategoryId());
            if (category.isPresent()) {
                return category.get();
            }
            row.setCategoryId(null);
            errors.add("Category not found for selected id");
        }

        if (row.getEnteredMainCategory() == null || row.getEnteredMainCategory().isBlank()) {
            return null;
        }

        return categoryRepository.findByNameIgnoreCase(row.getEnteredMainCategory())
                .orElseGet(() -> {
                    errors.add("Main category not found: " + row.getEnteredMainCategory());
                    return null;
                });
    }

    private SubCategory resolveSubCategory(ItemImportRowData row, Category category, List<String> errors) {
        if (row.getSubCategoryId() != null) {
            Optional<SubCategory> subCategory = subCategoryRepository.findById(row.getSubCategoryId());
            if (subCategory.isPresent()) {
                if (category == null || Objects.equals(subCategory.get().getCategory().getId(), category.getId())) {
                    return subCategory.get();
                }
                row.setSubCategoryId(null);
                errors.add("Sub category does not belong to selected main category");
                return null;
            }
            row.setSubCategoryId(null);
            errors.add("Sub category not found for selected id");
        }

        if ((row.getEnteredSubCategory() == null || row.getEnteredSubCategory().isBlank()) && row.getSubCategoryId() == null) {
            errors.add("subCategory or subCategoryId is required");
            return null;
        }
        if (category == null) {
            if (row.getSubCategoryId() != null) {
                Optional<SubCategory> subCategory = subCategoryRepository.findById(row.getSubCategoryId());
                if (subCategory.isPresent()) {
                    return subCategory.get();
                }
                errors.add("Sub category not found for selected id");
                return null;
            }
            return subCategoryRepository.findByNameIgnoreCase(row.getEnteredSubCategory())
                    .orElseGet(() -> {
                        errors.add("Sub category not found: " + row.getEnteredSubCategory());
                        return null;
                    });
        }

        return subCategoryRepository.findByCategoryIdAndNameIgnoreCase(category.getId(), row.getEnteredSubCategory())
                .orElseGet(() -> {
                    errors.add("Sub category not found under main category: " + row.getEnteredSubCategory());
                    return null;
                });
    }

    private List<ItemIngredientRequest> resolveIngredientRequests(
            ItemImportRowData recipeRow,
            Map<String, Long> importedItemRefs,
            List<String> errors
    ) {
        List<ItemImportIngredientData> importIngredients = recipeRow.getIngredients() == null ? List.of() : recipeRow.getIngredients();
        if (importIngredients.isEmpty()) {
            return null;
        }

        List<ItemIngredientRequest> ingredientRequests = new ArrayList<>();
        Set<Long> ingredientIds = new HashSet<>();
        for (ItemImportIngredientData ingredient : importIngredients) {
            List<String> rowErrors = new ArrayList<>();
            Long ingredientItemId = resolveIngredientItemId(ingredient, importedItemRefs, rowErrors);
            if (ingredient.getQuantity() == null || ingredient.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                rowErrors.add("quantity must be greater than zero");
            }
            if (ingredient.getQtyUnit() == null) {
                rowErrors.add("qtyUnit is invalid");
            }

            if (ingredientItemId != null && !ingredientIds.add(ingredientItemId)) {
                rowErrors.add("Duplicate ingredient item");
            }

            if (!rowErrors.isEmpty()) {
                errors.add("Recipe Ingredients row " + ingredient.getRowNumber() + ": " + String.join(", ", rowErrors));
                continue;
            }

            ItemIngredientRequest request = new ItemIngredientRequest();
            request.setIngredientItemId(ingredientItemId);
            request.setQuantity(ingredient.getQuantity());
            request.setQtyUnit(ingredient.getQtyUnit());
            ingredientRequests.add(request);
        }
        return ingredientRequests;
    }

    private ItemResponse upsertRecipeIngredients(Item recipeItem, List<ItemIngredientRequest> ingredientRequests) {
        if (ingredientRequests == null || ingredientRequests.isEmpty()) {
            return itemService.getItem(recipeItem.getId());
        }

        Set<Long> distinctIngredientIds = new HashSet<>();
        List<RecipeIngredient> ingredients = new ArrayList<>();

        for (ItemIngredientRequest ingredientRequest : ingredientRequests) {
            if (!distinctIngredientIds.add(ingredientRequest.getIngredientItemId())) {
                throw new BadRequestException("Duplicate ingredient item found in recipe");
            }

            Item ingredientItem = itemRepository.findById(ingredientRequest.getIngredientItemId())
                    .orElseThrow(() -> new BadRequestException("Ingredient item not found: " + ingredientRequest.getIngredientItemId()));

            if (!ingredientItem.isActive()) {
                throw new BadRequestException("Ingredient item is inactive: " + ingredientItem.getName());
            }
            if (ingredientItem.getId().equals(recipeItem.getId())) {
                throw new BadRequestException("Recipe item cannot include itself as an ingredient");
            }
            if (ingredientItem.getItemType() == ItemType.SERVICE || ingredientItem.getItemType() == ItemType.RECIPE) {
                throw new BadRequestException("Recipe ingredients must be stock-tracked grocery items");
            }

            int normalizedQuantity = QuantityConversionUtil.normalizeQuantity(
                    ingredientItem,
                    ingredientRequest.getQuantity(),
                    ingredientRequest.getQtyUnit()
            );

            RecipeIngredient recipeIngredient = recipeIngredientRepository
                    .findByParentItemIdAndIngredientId(recipeItem.getId(), ingredientItem.getId())
                    .orElseGet(() -> RecipeIngredient.builder()
                            .parentItemId(recipeItem.getId())
                            .ingredientId(ingredientItem.getId())
                            .build());
            recipeIngredient.setQuantity(normalizedQuantity);
            ingredients.add(recipeIngredient);
        }

        recipeIngredientRepository.saveAll(ingredients);
        return itemService.getItem(recipeItem.getId());
    }

    private Long resolveIngredientItemId(
            ItemImportIngredientData ingredient,
            Map<String, Long> importedItemRefs,
            List<String> errors
    ) {
        if (ingredient.getIngredient() != null) {
            Long byIngredient = resolveIngredientTextReference(ingredient.getIngredient(), importedItemRefs, errors);
            if (byIngredient != null) {
                return byIngredient;
            }
        }

        if (ingredient.getIngredientItemId() != null) {
            if (itemRepository.existsById(ingredient.getIngredientItemId())) {
                return ingredient.getIngredientItemId();
            }
            errors.add("ingredientItemId not found: " + ingredient.getIngredientItemId());
        }

        Long byImportKey = lookupImportedRef(importedItemRefs, "key", ingredient.getIngredientImportKey());
        if (byImportKey != null) {
            return byImportKey;
        }

        Long byBarcode = lookupImportedRef(importedItemRefs, "barcode", ingredient.getIngredientBarcode());
        if (byBarcode != null) {
            return byBarcode;
        }
        if (ingredient.getIngredientBarcode() != null) {
            Optional<Item> item = itemRepository.findByBarcode(ingredient.getIngredientBarcode());
            if (item.isPresent()) {
                return item.get().getId();
            }
        }

        Long byName = lookupImportedRef(importedItemRefs, "name", ingredient.getIngredientName());
        if (byName != null) {
            return byName;
        }
        if (ingredient.getIngredientName() != null) {
            List<Item> items = itemRepository.findAllByNameIgnoreCase(ingredient.getIngredientName());
            if (items.size() == 1) {
                return items.get(0).getId();
            }
            if (items.size() > 1) {
                errors.add("ingredientName matched multiple items; use ingredientItemId or ingredientBarcode");
                return null;
            }
        }

        errors.add("ingredient item not found");
        return null;
    }

    private Long resolveIngredientTextReference(String value, Map<String, Long> importedItemRefs, List<String> errors) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            return null;
        }

        Long byBarcode = lookupImportedRef(importedItemRefs, "barcode", normalized);
        if (byBarcode != null) {
            return byBarcode;
        }
        Long byName = lookupImportedRef(importedItemRefs, "name", normalized);
        if (byName != null) {
            return byName;
        }
        Long byImportKey = lookupImportedRef(importedItemRefs, "key", normalized);
        if (byImportKey != null) {
            return byImportKey;
        }

        Optional<Item> barcodeMatch = itemRepository.findByBarcode(normalized);
        if (barcodeMatch.isPresent()) {
            return barcodeMatch.get().getId();
        }

        List<Item> nameMatches = itemRepository.findAllByNameIgnoreCase(normalized);
        if (nameMatches.size() == 1) {
            return nameMatches.get(0).getId();
        }
        if (nameMatches.size() > 1) {
            errors.add("ingredient matched multiple item names; use ingredientItemId or ingredientBarcode");
            return null;
        }

        try {
            Long id = Long.valueOf(normalized);
            if (itemRepository.existsById(id)) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }

        return null;
    }

    private Long lookupImportedRef(Map<String, Long> refs, String prefix, String value) {
        String normalized = normalizeText(value);
        return normalized.isBlank() ? null : refs.get(prefix + ":" + normalized.toLowerCase(Locale.ROOT));
    }

    private void rememberImportedRefs(Map<String, Long> refs, ItemImportRowData row, Long itemId) {
        rememberImportedRef(refs, "key", row.getImportKey(), itemId);
        rememberImportedRef(refs, "barcode", row.getBarcode(), itemId);
        rememberImportedRef(refs, "name", row.getName(), itemId);
    }

    private void rememberImportedRef(Map<String, Long> refs, String prefix, String value, Long itemId) {
        String normalized = normalizeText(value);
        if (!normalized.isBlank() && itemId != null) {
            refs.put(prefix + ":" + normalized.toLowerCase(Locale.ROOT), itemId);
        }
    }

    private Set<String> collectDuplicateCandidateBarcodes(List<ItemImportRowData> rows) {
        return rows.stream()
                .filter(row -> row.getSelected() == null || row.getSelected())
                .filter(row -> row.getExistingItemId() == null)
                .map(ItemImportRowData::getBarcode)
                .filter(Objects::nonNull)
                .map(this::normalizeText)
                .filter(value -> !value.isBlank())
                .collect(Collectors.groupingBy(value -> value.toUpperCase(Locale.ROOT), Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private ItemImportRowData copyRow(ItemImportRowData row, ItemImportRowStatus status, String message) {
        return ItemImportRowData.builder()
                .rowNumber(row.getRowNumber())
                .selected(row.getSelected())
                .existingItemId(row.getExistingItemId())
                .recipeIngredientOnly(row.getRecipeIngredientOnly())
                .importKey(row.getImportKey())
                .barcode(row.getBarcode())
                .name(row.getName())
                .enteredMainCategory(row.getEnteredMainCategory())
                .categoryId(row.getCategoryId())
                .categoryName(row.getCategoryName())
                .enteredSubCategory(row.getEnteredSubCategory())
                .subCategoryId(row.getSubCategoryId())
                .subCategoryName(row.getSubCategoryName())
                .costPrice(row.getCostPrice())
                .sellingPrice(row.getSellingPrice())
                .reorderLevel(row.getReorderLevel())
                .itemType(row.getItemType())
                .defaultUnit(row.getDefaultUnit())
                .active(row.getActive())
                .posVisible(row.getPosVisible())
                .kotEnabled(row.getKotEnabled())
                .branchIds(row.getBranchIds())
                .ingredients(row.getIngredients())
                .status(status)
                .message(message)
                .build();
    }

    private ItemImportResponse buildResponse(List<ItemImportRowData> rows) {
        int readyCount = (int) rows.stream().filter(row -> row.getStatus() == ItemImportRowStatus.READY).count();
        int importedCount = (int) rows.stream().filter(row -> row.getStatus() == ItemImportRowStatus.IMPORTED).count();
        int errorCount = (int) rows.stream().filter(row -> row.getStatus() == ItemImportRowStatus.ERROR).count();
        int skippedCount = (int) rows.stream().filter(row -> row.getStatus() == ItemImportRowStatus.SKIPPED).count();

        return ItemImportResponse.builder()
                .success(errorCount == 0)
                .totalRows(rows.size())
                .readyCount(readyCount)
                .importedCount(importedCount)
                .errorCount(errorCount)
                .skippedCount(skippedCount)
                .rows(rows)
                .build();
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

        for (String requiredHeader : REQUIRED_HEADERS) {
            if (!headers.containsKey(requiredHeader)) {
                throw new BadRequestException("Missing required column: " + requiredHeader);
            }
        }
        if (!headers.containsKey("subCategory") && !headers.containsKey("subCategoryId")) {
            throw new BadRequestException("Missing required column: subCategory or subCategoryId");
        }
        return headers;
    }

    private String readCell(Row row, Integer columnIndex, DataFormatter formatter) {
        if (row == null || columnIndex == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
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

    private Boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return Boolean.FALSE;
        }
        return null;
    }

    private <E extends Enum<E>> E parseEnumOrNull(String value, Class<E> enumType) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private List<Long> parseBranchIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        List<Long> branchIds = new ArrayList<>();
        for (String token : value.split(",")) {
            String normalized = token.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            try {
                branchIds.add(Long.valueOf(normalized));
            } catch (NumberFormatException ex) {
                branchIds.add(null);
            }
        }
        return branchIds;
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer findHeader(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            Integer column = headers.get(name);
            if (column != null) {
                return column;
            }
        }
        return null;
    }

    private String normalizeBlankToNull(String value) {
        String normalized = normalizeText(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String joinMessages(String existingMessage, List<String> extraMessages) {
        List<String> messages = new ArrayList<>();
        if (existingMessage != null && !existingMessage.isBlank()) {
            messages.add(existingMessage);
        }
        messages.addAll(extraMessages.stream().filter(message -> message != null && !message.isBlank()).toList());
        return String.join("; ", messages.stream().distinct().toList());
    }

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
        Row normalRow = sheet.createRow(1);
        normalRow.createCell(0).setCellValue("N001");
        normalRow.createCell(1).setCellValue("");
        normalRow.createCell(2).setCellValue("Sample Ingredient");
        normalRow.createCell(3).setCellValue(1);
        normalRow.createCell(4).setCellValue(100);
        normalRow.createCell(5).setCellValue(150);
        normalRow.createCell(6).setCellValue(5);
        normalRow.createCell(7).setCellValue("NORMAL");
        normalRow.createCell(8).setCellValue("PCS");
        normalRow.createCell(9).setCellValue("true");
        normalRow.createCell(10).setCellValue("true");
        normalRow.createCell(11).setCellValue("false");
        normalRow.createCell(12).setCellValue("");

        Row serviceRow = sheet.createRow(2);
        serviceRow.createCell(0).setCellValue("S001");
        serviceRow.createCell(1).setCellValue("");
        serviceRow.createCell(2).setCellValue("Delivery Fee");
        serviceRow.createCell(3).setCellValue(9);
        serviceRow.createCell(4).setCellValue(0);
        serviceRow.createCell(5).setCellValue(300);
        serviceRow.createCell(6).setCellValue(0);
        serviceRow.createCell(7).setCellValue("SERVICE");
        serviceRow.createCell(8).setCellValue("SERVICE");
        serviceRow.createCell(9).setCellValue("true");
        serviceRow.createCell(10).setCellValue("true");
        serviceRow.createCell(11).setCellValue("false");
        serviceRow.createCell(12).setCellValue("1,2");

        Row volumeRow = sheet.createRow(3);
        volumeRow.createCell(0).setCellValue("V001");
        volumeRow.createCell(1).setCellValue("");
        volumeRow.createCell(2).setCellValue("Coconut Oil");
        volumeRow.createCell(3).setCellValue(1);
        volumeRow.createCell(4).setCellValue(900);
        volumeRow.createCell(5).setCellValue(1200);
        volumeRow.createCell(6).setCellValue(2);
        volumeRow.createCell(7).setCellValue("VOLUME");
        volumeRow.createCell(8).setCellValue("L");
        volumeRow.createCell(9).setCellValue("true");
        volumeRow.createCell(10).setCellValue("true");
        volumeRow.createCell(11).setCellValue("false");
        volumeRow.createCell(12).setCellValue("");

        Row recipeRow = sheet.createRow(4);
        recipeRow.createCell(0).setCellValue("R001");
        recipeRow.createCell(1).setCellValue("");
        recipeRow.createCell(2).setCellValue("Sample Recipe");
        recipeRow.createCell(3).setCellValue(3);
        recipeRow.createCell(4).setCellValue(250);
        recipeRow.createCell(5).setCellValue(450);
        recipeRow.createCell(6).setCellValue(0);
        recipeRow.createCell(7).setCellValue("RECIPE");
        recipeRow.createCell(8).setCellValue("PCS");
        recipeRow.createCell(9).setCellValue("true");
        recipeRow.createCell(10).setCellValue("true");
        recipeRow.createCell(11).setCellValue("true");
        recipeRow.createCell(12).setCellValue("");
    }

    private void createRecipeIngredientTemplateSheet(Workbook workbook, boolean ingredientsOnly) {
        Sheet sheet = workbook.createSheet(RECIPE_INGREDIENTS_SHEET_NAME);
        Row headerRow = sheet.createRow(0);
        List<String> headers = ingredientsOnly
                ? List.of("itemId", "ingredientId", "ingredientName", "qty", "qtyUnit")
                : List.of("recipeImportKey", "ingredient", "quantity", "qtyUnit");
        for (int i = 0; i < headers.size(); i++) {
            headerRow.createCell(i).setCellValue(headers.get(i));
        }

        Row ingredientRow = sheet.createRow(1);
        if (ingredientsOnly) {
            ingredientRow.createCell(0).setCellValue(1001);
            ingredientRow.createCell(1).setCellValue(2001);
            ingredientRow.createCell(2).setCellValue("");
            ingredientRow.createCell(3).setCellValue(1);
            ingredientRow.createCell(4).setCellValue("PCS");
        } else {
            ingredientRow.createCell(0).setCellValue("R001");
            ingredientRow.createCell(1).setCellValue("Sample Ingredient");
            ingredientRow.createCell(2).setCellValue(1);
            ingredientRow.createCell(3).setCellValue("PCS");
        }
        autosizeColumns(sheet, headers.size());
    }

    private void autosizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private record ValidationResult(ItemImportRowData row, ItemCreateRequest request) {}
}
