package com.chala.posapp.integration;

import com.chala.posapp.repository.SubCategoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A spreadsheet naming categories the shop does not have yet.
 *
 * <p>Before this, such a row failed with "Sub category not found" and the operator had to leave,
 * create every category by hand, and come back - on a real file that was thousands of rows
 * blocked on a dozen names the sheet had already spelled out.
 *
 * <p>The rule these tests pin is where the danger is: preview must create nothing. Looking at a
 * file is not a decision, and a shop that uploads a spreadsheet to see what is in it must not be
 * left with categories from a file it never imported.
 */
class ItemImportCategoryCreationIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private SubCategoryRepository subCategoryRepository;

    private static final List<String> HEADERS = List.of(
            "importKey", "barcode", "name", "subCategory", "costPrice", "sellingPrice",
            "reorderLevel", "itemType", "defaultUnit", "active", "posVisible", "kotEnabled", "branchIds");

    /** One sheet, one row, naming a category by a name the shop has never heard of. */
    private MockMultipartFile sheetNaming(String subCategory, String barcode, Long branchId) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Items");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                header.createCell(i).setCellValue(HEADERS.get(i));
            }
            List<String> values = List.of(
                    "", barcode, "Imported Item", subCategory, "100", "150",
                    "0", "NORMAL", "PCS", "true", "true", "false", String.valueOf(branchId));
            Row row = sheet.createRow(1);
            for (int i = 0; i < values.size(); i++) {
                row.createCell(i).setCellValue(values.get(i));
            }
            workbook.write(out);
            return new MockMultipartFile("file", "items.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private JsonNode postSheet(String path, String tenantId, String token, MockMultipartFile file) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.multipart(path)
                        .file(file)
                        .header("X-Tenant-ID", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private TenantFixture singleCategoryShop() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("imp"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        putJson("/app-configuration", fixture.tenantId(), token, """
                {
                  "recipeItemsEnabled": true, "weightItemsEnabled": true, "servicesEnabled": true,
                  "tableManagementEnabled": false, "dineInEnabled": false,
                  "categoryMode": "SINGLE_CATEGORY", "stockOverrideMode": "MANAGER_OVERRIDE",
                  "adminStockOverrideAllowed": true, "managerStockOverrideAllowed": true,
                  "cashierStockOverrideAllowed": true
                }
                """);
        return fixture;
    }

    @Test
    @DisplayName("preview promises the category and creates nothing")
    void previewCreatesNothing() throws Exception {
        TenantFixture fixture = singleCategoryShop();
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode response = postSheet("/items/import/preview", fixture.tenantId(), token,
                sheetNaming("Biscuits", "IMP-PREVIEW-1", fixture.mainBranch().getId()));

        JsonNode row = response.path("rows").get(0);
        assertThat(row.path("status").asText()).isEqualTo("READY");
        assertThat(row.path("message").asText()).contains("will create category: Biscuits");
        // The whole point: looking at the file left nothing behind.
        assertThat(subCategoryRepository.findByNameIgnoreCase("Biscuits")).isEmpty();
    }

    @Test
    @DisplayName("importing creates the category the sheet named, and the item lands in it")
    void importCreatesTheCategory() throws Exception {
        TenantFixture fixture = singleCategoryShop();
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode response = postSheet("/items/import", fixture.tenantId(), token,
                sheetNaming("Chocolates", "IMP-CREATE-1", fixture.mainBranch().getId()));

        JsonNode row = response.path("rows").get(0);
        assertThat(row.path("status").asText()).isEqualTo("IMPORTED");
        assertThat(subCategoryRepository.findByNameIgnoreCase("Chocolates")).isPresent();
        // Under the one parent a single-category shop keeps, the same as the Add Category
        // screen. Read inside a transaction: the parent is a lazy association.
        assertThat(inTransaction(() -> subCategoryRepository.findByNameIgnoreCase("Chocolates")
                .orElseThrow().getCategory().getName())).isEqualTo("General");

        JsonNode item = getJson("/items/barcode/IMP-CREATE-1?branchId=" + fixture.mainBranch().getId(),
                fixture.tenantId(), token);
        assertThat(item.path("subCategoryName").asText()).isEqualTo("Chocolates");
    }

    @Test
    @DisplayName("a second row naming the same category reuses it rather than making a twin")
    void oneCategoryPerName() throws Exception {
        TenantFixture fixture = singleCategoryShop();
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        postSheet("/items/import", fixture.tenantId(), token,
                sheetNaming("Spices", "IMP-ONCE-1", fixture.mainBranch().getId()));
        postSheet("/items/import", fixture.tenantId(), token,
                sheetNaming("spices", "IMP-ONCE-2", fixture.mainBranch().getId()));

        assertThat(subCategoryRepository.findAll().stream()
                .filter(sub -> sub.getName().equalsIgnoreCase("Spices"))
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("with main and sub categories, a name with no parent named is still an error")
    void mainAndSubNeedsAParent() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("imp"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        // MAIN_AND_SUB is the default; the sheet names no mainCategory column, so there is no
        // parent to infer. Inventing one would bury the file under a category nobody chose.
        JsonNode response = postSheet("/items/import", fixture.tenantId(), token,
                sheetNaming("Orphans", "IMP-ORPHAN-1", fixture.mainBranch().getId()));

        JsonNode row = response.path("rows").get(0);
        assertThat(row.path("status").asText()).isEqualTo("ERROR");
        assertThat(row.path("message").asText()).contains("Sub category not found: Orphans");
        assertThat(subCategoryRepository.findByNameIgnoreCase("Orphans")).isEmpty();
    }
}
