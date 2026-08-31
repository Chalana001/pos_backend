package com.chala.posapp.integration;

import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.OrderItem;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.supplier.Supplier;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.repository.StockOverrideAuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs on real MySQL (not H2) — this test exercises the FULLTEXT-backed
// item search (MATCH...AGAINST), which H2 cannot execute.
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class PosWorkflowIntegrationTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            // pos_db must exist before the app boots: raw connections anchor on
            // it and the tenant migration chain runs over it.
            .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        // root, not the container user: the app provisions tenant databases at
        // runtime and the URL carries createDatabaseIfNotExist, both of which
        // need server-wide rights the per-database app user does not have.
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private StockOverrideAuditRepository stockOverrideAuditRepository;

    @Test
    void stockProcessingConsumesSourceCreatesOutputsAndTracksWaste() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("process"), 3);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Processing Grocery");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Kitchen Prep");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName("Processing Supplier");
        supplier.setPhone("0713333333");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode drumstick = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "PROC-DRUM",
                  "name": "Chicken Drumstick",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 180,
                  "reorderLevel": 0,
                  "posVisible": false
                }
                """.formatted(subCategory.getId())
        );
        long drumstickItemId = drumstick.path("id").asLong();

        JsonNode waste = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "PROC-WASTE",
                  "name": "Chicken Waste",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 0,
                  "reorderLevel": 0,
                  "itemType": "WEIGHT",
                  "defaultUnit": "G",
                  "posVisible": false
                }
                """.formatted(subCategory.getId())
        );
        long wasteItemId = waste.path("id").asLong();

        JsonNode rice = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "PROC-RICE",
                  "name": "Rice",
                  "subCategoryId": %d,
                  "costPrice": 300,
                  "sellingPrice": 360,
                  "reorderLevel": 0,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG",
                  "posVisible": false
                }
                """.formatted(subCategory.getId())
        );
        long riceItemId = rice.path("id").asLong();

        JsonNode onion = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "PROC-ONION",
                  "name": "Onion",
                  "subCategoryId": %d,
                  "costPrice": 200,
                  "sellingPrice": 260,
                  "reorderLevel": 0,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG",
                  "posVisible": false
                }
                """.formatted(subCategory.getId())
        );
        long onionItemId = onion.path("id").asLong();

        JsonNode chicken = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "PROC-CHICKEN",
                  "name": "Whole Chicken",
                  "subCategoryId": %d,
                  "costPrice": 1200,
                  "sellingPrice": 1500,
                  "reorderLevel": 0,
                  "stockProcessingEnabled": true,
                  "posVisible": false,
                  "processingOutputs": [
                    { "outputItemId": %d, "defaultQty": 2, "waste": false },
                    { "outputItemId": %d, "defaultQty": 0.15, "waste": true }
                  ]
                }
                """.formatted(subCategory.getId(), drumstickItemId, wasteItemId)
        );
        long chickenItemId = chicken.path("id").asLong();
        assertTrue(chicken.path("stockProcessingEnabled").asBoolean());
        assertEquals(2, chicken.path("processingOutputs").size());

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "PROC-GRN-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 2,
                          "costPrice": 1200,
                          "sellingPrice": 1500
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), chickenItemId)
        );
        long sourceBatchId = firstBatchFor(fixture.mainBranch().getId(), chickenItemId).getId();
        assertEquals(2000, firstBatchFor(fixture.mainBranch().getId(), chickenItemId).getQuantity());

        JsonNode processed = postJson(
                "/stock-processing",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "sourceItemId": %d,
                  "sourceBatchId": %d,
                  "sourceQty": 1,
                  "sourceQtyUnit": "PCS",
                  "note": "Cut one chicken",
                  "outputs": [
                    { "outputItemId": %d, "qty": 2, "qtyUnit": "PCS" },
                    { "outputItemId": %d, "qty": 150, "qtyUnit": "G" }
                  ]
                }
                """.formatted(fixture.mainBranch().getId(), chickenItemId, sourceBatchId, drumstickItemId, wasteItemId)
        );

        assertEquals(1000, firstBatchFor(fixture.mainBranch().getId(), chickenItemId).getQuantity());
        assertEquals(2000, firstBatchFor(fixture.mainBranch().getId(), drumstickItemId).getQuantity());
        assertTrue(stockBatchRepository.findByBranchIdAndItemId(fixture.mainBranch().getId(), wasteItemId).isEmpty());
        assertEquals(1200.0, processed.path("sourceCost").asDouble(), 0.001);
        assertFalse(processed.path("outputs").get(0).path("waste").asBoolean());
        assertTrue(processed.path("outputs").get(0).path("createdBatchId").isNumber());
        assertTrue(processed.path("outputs").get(1).path("waste").asBoolean());
        assertTrue(processed.path("outputs").get(1).path("createdBatchId").isNull());

        long processedDrumstickBatchId = processed.path("outputs").get(0).path("createdBatchId").asLong();
        StockBatch processedDrumstickBatch = stockBatchRepository.findById(processedDrumstickBatchId).orElseThrow();
        assertEquals(StockBatchSourceType.PROCESSING, processedDrumstickBatch.getSourceType());

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "PROC-GRN-002",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 1,
                          "costPrice": 130,
                          "sellingPrice": 180
                        },
                        {
                          "itemId": %d,
                          "qty": 1,
                          "costPrice": 300,
                          "sellingPrice": 360
                        },
                        {
                          "itemId": %d,
                          "qty": 1,
                          "costPrice": 200,
                          "sellingPrice": 260
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), drumstickItemId, riceItemId, onionItemId)
        );

        List<StockBatch> availableDrumstickBatches = stockBatchRepository.findAvailableBatches(
                fixture.mainBranch().getId(),
                drumstickItemId
        );
        assertEquals(processedDrumstickBatchId, availableDrumstickBatches.get(0).getId());
        assertEquals(StockBatchSourceType.PROCESSING, availableDrumstickBatches.get(0).getSourceType());
        assertEquals(StockBatchSourceType.PURCHASE, availableDrumstickBatches.get(1).getSourceType());
        long purchasedDrumstickBatchId = availableDrumstickBatches.get(1).getId();

        JsonNode chickenRice = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "PROC-CHICKEN-RICE",
                  "name": "Chicken Rice",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 750,
                  "reorderLevel": 0,
                  "itemType": "RECIPE",
                  "posVisible": true,
                  "overheadCostMode": "FIXED",
                  "overheadCostValue": 20,
                  "ingredients": [
                    {
                      "ingredientItemId": %d,
                      "quantity": 2,
                      "qtyUnit": "PCS"
                    },
                    {
                      "ingredientItemId": %d,
                      "quantity": 100,
                      "qtyUnit": "G"
                    },
                    {
                      "ingredientItemId": %d,
                      "quantity": 50,
                      "qtyUnit": "G"
                    }
                  ]
                }
                """.formatted(subCategory.getId(), drumstickItemId, riceItemId, onionItemId)
        );
        long chickenRiceItemId = chickenRice.path("id").asLong();
        assertEquals("FIXED", chickenRice.path("overheadCostMode").asText());
        assertEquals(20.0, chickenRice.path("overheadCostValue").asDouble(), 0.001);

        JsonNode recipeSale = postJson(
                "/orders",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 750,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 750,
                  "paymentMethod": "CASH"
                }
                """.formatted(fixture.mainBranch().getId(), chickenRiceItemId)
        );

        assertEquals(750.0, recipeSale.path("grandTotal").asDouble(), 0.001);
        OrderItem recipeSaleLine = orderItemRepository.findByOrderId(recipeSale.path("id").asLong()).get(0);
        assertEquals(750.0, recipeSaleLine.getLineTotal(), 0.001);
        assertEquals(1260.0, recipeSaleLine.getLineCost(), 0.001);
        assertEquals(1260.0, recipeSaleLine.getCostPrice(), 0.001);
        assertEquals(0, stockBatchRepository.findById(processedDrumstickBatchId).orElseThrow().getQuantity());
        assertEquals(1000, stockBatchRepository.findById(purchasedDrumstickBatchId).orElseThrow().getQuantity());
        assertEquals(900, firstBatchFor(fixture.mainBranch().getId(), riceItemId).getQuantity());
        assertEquals(950, firstBatchFor(fixture.mainBranch().getId(), onionItemId).getQuantity());

        JsonNode history = getJson(
                "/stock-processing?branchId=" + fixture.mainBranch().getId() + "&sourceItemId=" + chickenItemId,
                tenantId,
                adminToken
        );
        assertEquals(1, history.path("content").size());
    }

    @Test
    void managerStockOverrideAllowsNegativeStockAndWritesAudit() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("override"), 3);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Override Grocery");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Override Stock");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName("Override Supplier");
        supplier.setPhone("0711111111");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "OVR-001",
                  "name": "Override Coke",
                  "subCategoryId": %d,
                  "costPrice": 80,
                  "sellingPrice": 120,
                  "reorderLevel": 0
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "OVR-GRN-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 1,
                          "costPrice": 80,
                          "sellingPrice": 120
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), itemId)
        );
        long batchId = firstBatchFor(fixture.mainBranch().getId(), itemId).getId();

        JsonNode blocked = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 2,
                      "unitPrice": 120,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 240,
                  "paymentMethod": "CASH"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId),
                409
        );
        assertEquals("STOCK_OVERRIDE_REQUIRED", blocked.path("code").asText());
        assertTrue(blocked.path("overrideAvailable").asBoolean());

        JsonNode order = postJson(
                "/orders",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "stockOverrideReason": "Kitchen physical stock verified",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 2,
                      "unitPrice": 120,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 240,
                  "paymentMethod": "CASH"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId)
        );
        assertEquals(240.0, order.path("grandTotal").asDouble(), 0.001);

        int totalStock = stockBatchRepository.getTotalQuantityByItemAndBranch(fixture.mainBranch().getId(), itemId);
        assertEquals(-1000, totalStock);
        assertEquals(1, stockOverrideAuditRepository.findAll().size());
        assertEquals(1000, stockOverrideAuditRepository.findAll().get(0).getShortageQuantity());
    }

    @Test
    void cashierStockOverrideRequiresRolePermissionWhenAllBatchesAreZero() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("cashier-override-deny"), 3);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Cashier Override Denied");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Stock");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "COD-001",
                  "name": "Cashier Denied Coke",
                  "subCategoryId": %d,
                  "costPrice": 80,
                  "sellingPrice": 120,
                  "reorderLevel": 0
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        StockBatch zeroBatch = StockBatch.builder()
                .branch(fixture.mainBranch())
                .item(itemRepository.findById(itemId).orElseThrow())
                .quantity(0)
                .originalQuantity(0)
                .costPrice(java.math.BigDecimal.valueOf(80))
                .sellingPrice(java.math.BigDecimal.valueOf(120))
                .build();
        stockBatchRepository.save(zeroBatch);

        JsonNode blocked = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 120,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 120,
                  "paymentMethod": "CASH"
                }
                """.formatted(itemId),
                400
        );
        assertEquals("Stock override permission denied for this role", blocked.path("message").asText());
        assertEquals(0, stockBatchRepository.getTotalQuantityByItemAndBranch(fixture.mainBranch().getId(), itemId));
    }

    @Test
    void cashierStockOverridePermissionAllowsAllZeroBatchesToGoNegative() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("cashier-override-allow"), 3);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        putJson(
                "/app-configuration",
                tenantId,
                adminToken,
                """
                {
                  "recipeItemsEnabled": true,
                  "weightItemsEnabled": true,
                  "servicesEnabled": true,
                  "tableManagementEnabled": true,
                  "dineInEnabled": true,
                  "categoryMode": "MAIN_AND_SUB",
                  "stockOverrideMode": "MANAGER_OVERRIDE",
                  "adminStockOverrideAllowed": true,
                  "managerStockOverrideAllowed": true,
                  "cashierStockOverrideAllowed": true
                }
                """
        );

        Category category = new Category();
        category.setName("Cashier Override Allowed");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Stock");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "COA-001",
                  "name": "Cashier Allowed Coke",
                  "subCategoryId": %d,
                  "costPrice": 80,
                  "sellingPrice": 120,
                  "reorderLevel": 0
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        StockBatch zeroBatch = StockBatch.builder()
                .branch(fixture.mainBranch())
                .item(itemRepository.findById(itemId).orElseThrow())
                .quantity(0)
                .originalQuantity(0)
                .costPrice(java.math.BigDecimal.valueOf(80))
                .sellingPrice(java.math.BigDecimal.valueOf(120))
                .build();
        stockBatchRepository.save(zeroBatch);
        int auditCountBefore = stockOverrideAuditRepository.findAll().size();

        JsonNode needsConfirmation = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 120,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 120,
                  "paymentMethod": "CASH"
                }
                """.formatted(itemId),
                409
        );
        assertEquals("STOCK_OVERRIDE_REQUIRED", needsConfirmation.path("code").asText());
        assertTrue(needsConfirmation.path("overrideAvailable").asBoolean());

        JsonNode order = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "orderType": "CASH",
                  "allowStockOverride": true,
                  "stockOverrideReason": "Cashier confirmed zero stock sale",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 120,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 120,
                  "paymentMethod": "CASH"
                }
                """.formatted(itemId)
        );
        assertEquals(120.0, order.path("grandTotal").asDouble(), 0.001);
        assertEquals(-1000, stockBatchRepository.getTotalQuantityByItemAndBranch(fixture.mainBranch().getId(), itemId));
        assertEquals(auditCountBefore + 1, stockOverrideAuditRepository.findAll().size());
        assertEquals(1000, stockOverrideAuditRepository.findAll().get(auditCountBefore).getShortageQuantity());
    }

    @Test
    void appConfigurationPersistsStockOverrideRolePermissions() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("override-config"), 3);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        JsonNode saved = putJson(
                "/app-configuration",
                tenantId,
                adminToken,
                """
                {
                  "recipeItemsEnabled": true,
                  "weightItemsEnabled": true,
                  "servicesEnabled": true,
                  "tableManagementEnabled": true,
                  "dineInEnabled": true,
                  "categoryMode": "MAIN_AND_SUB",
                  "stockOverrideMode": "MANAGER_OVERRIDE",
                  "adminStockOverrideAllowed": false,
                  "managerStockOverrideAllowed": true,
                  "cashierStockOverrideAllowed": true
                }
                """
        );

        assertFalse(saved.path("adminStockOverrideAllowed").asBoolean());
        assertTrue(saved.path("managerStockOverrideAllowed").asBoolean());
        assertTrue(saved.path("cashierStockOverrideAllowed").asBoolean());

        JsonNode reloaded = getJson("/app-configuration", tenantId, adminToken);
        assertFalse(reloaded.path("adminStockOverrideAllowed").asBoolean());
        assertTrue(reloaded.path("managerStockOverrideAllowed").asBoolean());
        assertTrue(reloaded.path("cashierStockOverrideAllowed").asBoolean());
    }

    @Test
    void recipeIngredientCanUseFractionalPcsWithoutAllowingFractionalDirectSale() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("halfpcs"), 3);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Half PCS");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Kitchen");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName("Half Supplier");
        supplier.setPhone("0712222222");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode egg = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "HALF-EGG",
                  "name": "Half Test Egg",
                  "subCategoryId": %d,
                  "costPrice": 40,
                  "sellingPrice": 50,
                  "reorderLevel": 1
                }
                """.formatted(subCategory.getId())
        );
        long eggItemId = egg.path("id").asLong();

        postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "HALF-GRN-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 1,
                          "costPrice": 40,
                          "sellingPrice": 50
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), eggItemId)
        );
        assertEquals(1000, firstBatchFor(fixture.mainBranch().getId(), eggItemId).getQuantity());

        JsonNode recipe = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "HALF-OMELETTE",
                  "name": "Half Egg Omelette",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 120,
                  "reorderLevel": 0,
                  "itemType": "RECIPE",
                  "ingredients": [
                    {
                      "ingredientItemId": %d,
                      "quantity": 0.5,
                      "qtyUnit": "PCS"
                    }
                  ]
                }
                """.formatted(subCategory.getId(), eggItemId)
        );
        long recipeItemId = recipe.path("id").asLong();
        assertEquals(0.5, recipe.path("ingredients").get(0).path("quantity").asDouble(), 0.001);
        assertEquals(500, recipe.path("ingredients").get(0).path("baseQuantity").asInt());

        postJson(
                "/orders",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "unitPrice": 120,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 120,
                  "paymentMethod": "CASH"
                }
                """.formatted(fixture.mainBranch().getId(), recipeItemId)
        );
        assertEquals(500, firstBatchFor(fixture.mainBranch().getId(), eggItemId).getQuantity());

        long batchId = firstBatchFor(fixture.mainBranch().getId(), eggItemId).getId();
        jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                tenantId,
                adminToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 0.5,
                      "unitPrice": 50,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 25,
                  "paymentMethod": "CASH"
                }
                """.formatted(fixture.mainBranch().getId(), eggItemId, batchId),
                400
        );
    }

    @Test
    void standardPlanCreatesZeroStockBatchesForNewStockItems() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("std"), 3);
        String tenantId = fixture.tenantId();

        SubscriptionPlan standardPlan = ensurePlan("STANDARD", BillingCycle.MONTHLY, 1500.0, 1500.0, 1);
        fixture.subscription().setPlan(standardPlan);
        tenantSubscriptionRepository.save(fixture.subscription());

        Branch secondBranch = new Branch();
        secondBranch.setCode("B2");
        secondBranch.setName("Branch 2");
        secondBranch.setActive(true);
        secondBranch = branchRepository.save(secondBranch);

        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Grocery");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Dry Goods");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "name": "Standard Plan Rice",
                  "subCategoryId": %d,
                  "costPrice": 100,
                  "sellingPrice": 120,
                  "reorderLevel": 0,
                  "itemType": "NORMAL",
                  "defaultUnit": "PCS"
                }
                """.formatted(subCategory.getId())
        );

        long itemId = item.path("id").asLong();
        StockBatch mainBatch = firstBatchFor(fixture.mainBranch().getId(), itemId);
        StockBatch secondBatch = firstBatchFor(secondBranch.getId(), itemId);

        assertEquals(0, mainBatch.getQuantity());
        assertEquals(0, secondBatch.getQuantity());
        assertTrue(mainBatch.getBatchCode().startsWith("AUTO-ZERO-"));
        assertTrue(secondBatch.getBatchCode().startsWith("AUTO-ZERO-"));
    }

    @Test
    void customerOrderShiftPaymentDashboardAndReportApisWork() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("ops"), 5);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Beverages");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Tea");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        Supplier supplier = new Supplier();
        supplier.setName("Ops Supplier");
        supplier.setPhone("0111111111");
        supplier.setEmail("ops@supplier.local");
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        JsonNode item = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "90001",
                  "name": "Milk Tea",
                  "subCategoryId": %d,
                  "costPrice": 50,
                  "sellingPrice": 80,
                  "reorderLevel": 15
                }
                """.formatted(subCategory.getId())
        );
        long itemId = item.path("id").asLong();

        JsonNode purchase = postJson(
                "/purchases",
                tenantId,
                adminToken,
                """
                {
                  "supplierId": %d,
                  "invoiceNo": "INV-OPS-001",
                  "branches": [
                    {
                      "branchId": %d,
                      "items": [
                        {
                          "itemId": %d,
                          "qty": 10,
                          "costPrice": 50,
                          "sellingPrice": 80
                        }
                      ]
                    }
                  ]
                }
                """.formatted(supplier.getId(), fixture.mainBranch().getId(), itemId)
        );
        assertEquals(500.0, purchase.path("grandTotal").asDouble(), 0.001);
        long batchId = firstBatchFor(fixture.mainBranch().getId(), itemId).getId();

        JsonNode customer = postJson(
                "/customers",
                tenantId,
                cashierToken,
                """
                {
                  "name": "Nimal Perera",
                  "phone": "0712345678",
                  "address": "Kandy",
                  "creditLimit": 500
                }
                """
        );
        long customerId = customer.path("id").asLong();

        JsonNode customerById = getJson("/customers/" + customerId, tenantId, cashierToken);
        assertEquals("Nimal Perera", customerById.path("name").asText());

        JsonNode customerByPhone = getJson("/customers/phone/0712345678", tenantId, cashierToken);
        assertEquals(customerId, customerByPhone.path("id").asLong());

        JsonNode customerList = getJson("/customers?activeOnly=true", tenantId, cashierToken);
        assertEquals(1, customerList.size());

        JsonNode customerSearch = getJson("/customers/search?name=Nimal", tenantId, cashierToken);
        assertEquals(1, customerSearch.size());

        JsonNode updatedCustomer = putJson(
                "/customers/" + customerId,
                tenantId,
                adminToken,
                """
                {
                  "name": "Nimal P.",
                  "phone": "0712345678",
                  "address": "Colombo",
                  "creditLimit": 800,
                  "active": true
                }
                """
        );
        assertEquals("Nimal P.", updatedCustomer.path("name").asText());

        JsonNode createdNote = postJson(
                "/customers/" + customerId + "/notes",
                tenantId,
                adminToken,
                """
                {
                  "note": "Prefers evening delivery"
                }
                """
        );
        long noteId = createdNote.path("id").asLong();

        JsonNode listedNotes = getJson("/customers/" + customerId + "/notes?page=0&size=10", tenantId, adminToken);
        assertEquals(1, listedNotes.path("items").size());

        JsonNode updatedNote = putJson(
                "/customer-notes/" + noteId,
                tenantId,
                adminToken,
                """
                {
                  "note": "Prefers evening delivery and SMS"
                }
                """
        );
        assertTrue(updatedNote.path("note").asText().contains("SMS"));

        JsonNode adminShift = postJson(
                "/shifts/branch/open?branchId=" + fixture.mainBranch().getId(),
                tenantId,
                adminToken,
                """
                {
                  "openingCash": 3000,
                  "assignedCashierId": 0,
                  "note": "Admin counter shift"
                }
                """
        );
        long adminShiftId = adminShift.path("id").asLong();

        JsonNode cashierShift = postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 5000,
                  "note": "Morning shift"
                }
                """
        );
        long cashierShiftId = cashierShift.path("id").asLong();
        assertEquals("OPEN", cashierShift.path("status").asText());

        JsonNode myShift = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(cashierShiftId, myShift.path("id").asLong());

        JsonNode adminCurrentShift = getJson("/shifts/admin-current?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(adminShiftId, adminCurrentShift.path("id").asLong());

        JsonNode activeShifts = getJson("/shifts/active?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(2, activeShifts.size());

        JsonNode allShifts = getJson("/shifts/all?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, allShifts.path("content").size());

        JsonNode salesFilterUsers = getJson("/users/sales-filter?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertTrue(salesFilterUsers.toString().contains(fixture.cashier().getUsername()));

        JsonNode expenseTypes = getJson("/expense-types", tenantId, adminToken);
        long teaExpenseTypeId = 0L;
        for (JsonNode expenseType : expenseTypes) {
            if ("Tea".equalsIgnoreCase(expenseType.path("name").asText())) {
                teaExpenseTypeId = expenseType.path("id").asLong();
                break;
            }
        }
        assertTrue(teaExpenseTypeId > 0);

        JsonNode otherExpenseType = postJson(
                "/expense-types",
                tenantId,
                adminToken,
                """
                {
                  "name": "Recovered Oil",
                  "countInProfitReport": false,
                  "active": true
                }
                """
        );
        long otherExpenseTypeId = otherExpenseType.path("id").asLong();

        JsonNode createdExpense = postJson(
                "/expenses",
                tenantId,
                adminToken,
                """
                {
                  "expenseTypeId": %d,
                  "amount": 100,
                  "branchId": %d,
                  "fromDrawer": true,
                  "description": "Tea counter electricity"
                }
                """.formatted(teaExpenseTypeId, fixture.mainBranch().getId())
        );
        assertEquals(100.0, createdExpense.path("amount").asDouble(), 0.001);
        assertTrue(createdExpense.path("countInProfitReport").asBoolean());

        JsonNode nonDrawerExpense = postJson(
                "/expenses",
                tenantId,
                adminToken,
                """
                {
                  "expenseTypeId": %d,
                  "amount": 200,
                  "branchId": %d,
                  "isFromDrawer": false,
                  "description": "200"
                }
                """.formatted(otherExpenseTypeId, fixture.mainBranch().getId())
        );
        assertEquals(200.0, nonDrawerExpense.path("amount").asDouble(), 0.001);
        assertFalse(nonDrawerExpense.path("countInProfitReport").asBoolean());

        JsonNode adminShiftAfterExpenses = getJson("/shifts/admin-current?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(100.0, adminShiftAfterExpenses.path("totalExpenses").asDouble(), 0.001);

        JsonNode cashierCashDropShift = postJson(
                "/shifts/cashdrop",
                tenantId,
                cashierToken,
                """
                {
                  "amount": 200,
                  "reason": "Bank deposit"
                }
                """
        );
        assertEquals(200.0, cashierCashDropShift.path("totalCashDrops").asDouble(), 0.001);

        JsonNode adminCashDropShift = postJson(
                "/shifts/" + adminShiftId + "/cashdrop",
                tenantId,
                adminToken,
                """
                {
                  "amount": 150,
                  "reason": "Admin drawer transfer"
                }
                """
        );
        assertEquals(150.0, adminCashDropShift.path("totalCashDrops").asDouble(), 0.001);

        JsonNode expensePage = getJson("/expenses?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, expensePage.path("content").size());

        JsonNode teaExpensePage = getJson(
                "/expenses?branchId=" + fixture.mainBranch().getId() + "&expenseTypeId=" + teaExpenseTypeId + "&page=0&size=10",
                tenantId,
                adminToken
        );
        assertEquals(1, teaExpensePage.path("content").size());
        assertEquals("Tea counter electricity", teaExpensePage.path("content").get(0).path("description").asText());

        JsonNode searchedExpensePage = getJson(
                "/expenses?branchId=" + fixture.mainBranch().getId() + "&search=electricity&page=0&size=10",
                tenantId,
                adminToken
        );
        assertEquals(1, searchedExpensePage.path("content").size());

        JsonNode adminExpensePage = getJson(
                "/expenses?branchId=" + fixture.mainBranch().getId() + "&cashierId=" + fixture.admin().getId() + "&page=0&size=10",
                tenantId,
                adminToken
        );
        assertEquals(2, adminExpensePage.path("content").size());

        JsonNode shiftExpenses = getJson("/shifts/" + adminShiftId + "/expenses?page=0&size=10", tenantId, adminToken);
        assertEquals(2, shiftExpenses.path("content").size());

        JsonNode cashDropPage = getJson("/cash-drops?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, cashDropPage.path("content").size());

        JsonNode filteredCashDropPage = getJson(
                "/cash-drops?branchId=" + fixture.mainBranch().getId() + "&cashierUserId=" + fixture.admin().getId() + "&search=transfer&page=0&size=10",
                tenantId,
                adminToken
        );
        assertEquals(1, filteredCashDropPage.path("content").size());
        assertEquals("Admin drawer transfer", filteredCashDropPage.path("content").get(0).path("reason").asText());

        JsonNode cashDropSummary = getJson(
                "/cash-drops/summary?branchId=" + fixture.mainBranch().getId() + "&cashierUserId=" + fixture.admin().getId(),
                tenantId,
                adminToken
        );
        assertEquals(150.0, cashDropSummary.path("totalAmount").asDouble(), 0.001);
        assertEquals(1, cashDropSummary.path("dropCount").asInt());
        assertEquals(150.0, cashDropSummary.path("averageAmount").asDouble(), 0.001);

        JsonNode cashOrder = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 2,
                      "unitPrice": 80,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 160,
                  "note": "Cash order"
                }
                """.formatted(fixture.mainBranch().getId(), itemId, batchId)
        );
        String cashInvoice = cashOrder.path("invoiceNo").asText();
        assertEquals(160.0, cashOrder.path("grandTotal").asDouble(), 0.001);
        assertEquals(fixture.cashier().getUsername(), cashOrder.path("cashierName").asText());

        JsonNode shiftAfterCashOrder = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(160.0, shiftAfterCashOrder.path("cashSales").asDouble(), 0.001);
        assertEquals(200.0, shiftAfterCashOrder.path("totalCashDrops").asDouble(), 0.001);

        JsonNode creditOrder = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "customerId": %d,
                  "orderType": "CREDIT",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 3,
                      "unitPrice": 80,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 0,
                  "note": "Credit order"
                }
                """.formatted(fixture.mainBranch().getId(), customerId, itemId, batchId)
        );
        String creditInvoice = creditOrder.path("invoiceNo").asText();
        assertEquals(240.0, creditOrder.path("dueAmount").asDouble(), 0.001);
        assertEquals(fixture.cashier().getUsername(), creditOrder.path("cashierName").asText());

        JsonNode creditLimitError = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "customerId": %d,
                  "orderType": "CREDIT",
                  "items": [
                    {
                      "itemId": %d,
                      "batchId": %d,
                      "qty": 1,
                      "unitPrice": 700,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 0,
                  "note": "Blocked credit order"
                }
                """.formatted(fixture.mainBranch().getId(), customerId, itemId, batchId),
                400
        );
        assertTrue(creditLimitError.path("message").asText().contains("credit limit exceeded"));

        JsonNode fetchedCashOrder = getJson("/orders/" + cashInvoice, tenantId, cashierToken);
        assertEquals(cashInvoice, fetchedCashOrder.path("invoiceNo").asText());
        assertEquals(1, fetchedCashOrder.path("items").size());

        JsonNode ordersPage = getJson("/orders?branchId=" + fixture.mainBranch().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, ordersPage.path("content").size());
        assertEquals(fixture.cashier().getUsername(), ordersPage.path("content").get(0).path("cashierName").asText());

        JsonNode cashierOrdersPage = getJson("/orders?branchId=" + fixture.mainBranch().getId() + "&cashierId=" + fixture.cashier().getId() + "&page=0&size=10", tenantId, adminToken);
        assertEquals(2, cashierOrdersPage.path("content").size());

        JsonNode shiftDetails = getJson("/shifts/" + cashierShiftId, tenantId, adminToken);
        assertEquals(fixture.cashier().getUsername(), shiftDetails.path("cashierName").asText());

        JsonNode shiftOrders = getJson("/shifts/" + cashierShiftId + "/orders?page=0&size=10", tenantId, adminToken);
        assertEquals(2, shiftOrders.path("content").size());
        assertEquals(creditInvoice, shiftOrders.path("content").get(0).path("invoiceNo").asText());

        JsonNode customerOrders = getJson("/customers/" + customerId + "/orders?orderType=ALL&page=0&size=10", tenantId, adminToken);
        assertEquals(1, customerOrders.path("items").size());
        assertEquals(creditInvoice, customerOrders.path("items").get(0).path("invoiceNo").asText());

        JsonNode customerPayment = postJson(
                "/customers/" + customerId + "/payments",
                tenantId,
                cashierToken,
                """
                {
                  "amount": 50,
                  "paymentMethod": "CASH",
                  "note": "Customer part payment"
                }
                """
        );
        assertEquals(190.0, customerPayment.path("dueAmount").asDouble(), 0.001);

        JsonNode creditSettlement = postJson(
                "/credit/settle",
                tenantId,
                cashierToken,
                """
                {
                  "customerId": %d,
                  "amount": 40,
                  "note": "Credit desk settlement"
                }
                """.formatted(customerId)
        );
        assertEquals(40.0, creditSettlement.path("amount").asDouble(), 0.001);

        JsonNode creditHistory = getJson("/credit/payments/" + customerId, tenantId, adminToken);
        assertEquals(2, creditHistory.size());

        JsonNode canceledOrder = postJson(
                "/orders/" + cashInvoice + "/cancel",
                tenantId,
                adminToken,
                """
                {
                  "reason": "Customer changed mind"
                }
                """
        );
        assertEquals("CANCELED", canceledOrder.path("status").asText());
        assertEquals(1, stockBatchRepository.findByBranchIdAndItemId(fixture.mainBranch().getId(), itemId).size());
        assertEquals(7000, firstBatchFor(fixture.mainBranch().getId(), itemId).getQuantity());

        JsonNode shiftAfterCancel = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(0.0, shiftAfterCancel.path("cashSales").asDouble(), 0.001);
        assertEquals(200.0, shiftAfterCancel.path("totalCashDrops").asDouble(), 0.001);

        JsonNode closedCashierShift = postJson(
                "/shifts/close",
                tenantId,
                cashierToken,
                """
                {
                  "countedCash": 5040,
                  "note": "Cashier closed"
                }
                """
        );
        assertEquals("CLOSED", closedCashierShift.path("status").asText());

        JsonNode closedAdminShift = postJson(
                "/shifts/" + adminShiftId + "/close",
                tenantId,
                adminToken,
                """
                {
                  "countedCash": 2750,
                  "note": "Admin shift closed"
                }
                """
        );
        assertEquals("CLOSED", closedAdminShift.path("status").asText());

        String today = LocalDate.now().toString();

        JsonNode dashboardKpis = getJson("/dashboard/kpis?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertTrue(dashboardKpis.path("todaySales").asDouble() >= 240.0);
        assertTrue(dashboardKpis.path("todayExpenses").asDouble() >= 100.0);

        JsonNode dailyChart = getJson("/dashboard/charts/daily?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertFalse(dailyChart.isEmpty());

        JsonNode monthlyChart = getJson("/dashboard/charts/monthly?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertFalse(monthlyChart.isEmpty());

        JsonNode stockAfterOrders = getJson("/stock/branch/" + fixture.mainBranch().getId() + "?page=0&size=10", tenantId, adminToken);
        assertEquals(itemId, stockAfterOrders.path("content").get(0).path("itemId").asLong());

        JsonNode salesSummary = getJson("/reports/sales-summary?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertEquals(240.0, salesSummary.path("creditSales").asDouble(), 0.001);

        JsonNode profitSummary = getJson("/reports/profit-summary?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertTrue(profitSummary.path("totalExpenses").asDouble() >= 100.0);

        JsonNode topSelling = getJson("/reports/top-selling?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&limit=10", tenantId, adminToken);
        assertEquals(itemId, topSelling.get(0).path("itemId").asLong());

        JsonNode profitReport = getJson("/reports/profit?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&limit=10", tenantId, adminToken);
        assertFalse(profitReport.isEmpty());

        JsonNode salesTrend = getJson("/reports/sales-trend?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today + "&type=DAILY", tenantId, adminToken);
        assertFalse(salesTrend.isEmpty());

        JsonNode salesByCategory = getJson("/reports/sales-by-category?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertEquals("Beverages", salesByCategory.get(0).path("categoryName").asText());

        JsonNode recentOrders = getJson("/reports/recent-orders?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertFalse(recentOrders.isEmpty());

        JsonNode lowStock = getJson("/reports/low-stock?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertFalse(lowStock.isEmpty());

        JsonNode creditDue = getJson("/reports/credit-due", tenantId, adminToken);
        assertFalse(creditDue.isEmpty());

        JsonNode topCustomers = getJson("/reports/top-customers?branchId=" + fixture.mainBranch().getId() + "&limit=10", tenantId, adminToken);
        assertFalse(topCustomers.isEmpty());

        JsonNode topSuppliers = getJson("/reports/top-suppliers?branchId=" + fixture.mainBranch().getId() + "&limit=10", tenantId, adminToken);
        assertFalse(topSuppliers.isEmpty());

        deleteExpectingStatus("/customer-notes/" + noteId, tenantId, adminToken, 204);
        JsonNode notesAfterDelete = getJson("/customers/" + customerId + "/notes?page=0&size=10", tenantId, adminToken);
        assertTrue(notesAfterDelete.path("items").isEmpty());
    }

    @Test
    void underpaidCashOrderBecomesPartialCreditSale() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("multi-pay"), 1);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName("Services");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Repairs");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        JsonNode serviceItem = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "MP-001",
                  "name": "Screen Repair",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 100,
                  "itemType": "SERVICE",
                  "branchIds": [%d]
                }
                """.formatted(subCategory.getId(), fixture.mainBranch().getId())
        );
        long serviceItemId = serviceItem.path("id").asLong();

        JsonNode customer = postJson(
                "/customers",
                tenantId,
                cashierToken,
                """
                {
                  "name": "Partial Pay Customer",
                  "phone": "0711112222",
                  "creditLimit": 500
                }
                """
        );
        long customerId = customer.path("id").asLong();

        postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Partial payment shift"
                }
                """
        );

        JsonNode order = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "customerId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "qtyUnit": "SERVICE",
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 40,
                  "note": "Cash plus credit"
                }
                """.formatted(fixture.mainBranch().getId(), customerId, serviceItemId)
        );

        assertEquals("CREDIT", order.path("orderType").asText());
        assertEquals(100.0, order.path("grandTotal").asDouble(), 0.001);
        assertEquals(40.0, order.path("paidAmount").asDouble(), 0.001);
        assertEquals(60.0, order.path("dueAmount").asDouble(), 0.001);

        JsonNode updatedCustomer = getJson("/customers/" + customerId, tenantId, cashierToken);
        assertEquals(60.0, updatedCustomer.path("dueAmount").asDouble(), 0.001);

        JsonNode shiftAfterOrder = getJson("/shifts/me", tenantId, cashierToken);
        assertEquals(40.0, shiftAfterOrder.path("cashSales").asDouble(), 0.001);

        String today = LocalDate.now().toString();
        JsonNode salesSummary = getJson("/reports/sales-summary?branchId=" + fixture.mainBranch().getId() + "&from=" + today + "&to=" + today, tenantId, adminToken);
        assertEquals(100.0, salesSummary.path("totalSales").asDouble(), 0.001);
        assertEquals(40.0, salesSummary.path("cashSales").asDouble(), 0.001);
        assertEquals(60.0, salesSummary.path("creditSales").asDouble(), 0.001);

        JsonNode dashboardKpis = getJson("/dashboard/kpis?branchId=" + fixture.mainBranch().getId(), tenantId, adminToken);
        assertEquals(40.0, dashboardKpis.path("cashSales").asDouble(), 0.001);
        assertEquals(60.0, dashboardKpis.path("creditSales").asDouble(), 0.001);

        JsonNode closedShift = postJson(
                "/shifts/close",
                tenantId,
                cashierToken,
                """
                {
                  "countedCash": 1040,
                  "note": "Closed with partial payment"
                }
                """
        );
        assertEquals(40.0, closedShift.path("cashSales").asDouble(), 0.001);
        assertEquals(1040.0, closedShift.path("expectedCash").asDouble(), 0.001);
    }

    @Test
    void serviceItemsUseServiceUnitAndCanBeSoldWithoutStock() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("svc"), 2);
        String tenantId = fixture.tenantId();
        String adminToken = login(tenantId, fixture.admin().getUsername(), DEFAULT_PASSWORD);
        String cashierToken = login(tenantId, fixture.cashier().getUsername(), DEFAULT_PASSWORD);
        String managerToken = login(tenantId, fixture.manager().getUsername(), DEFAULT_PASSWORD);

        JsonNode secondBranch = postJson(
                "/branches",
                tenantId,
                adminToken,
                """
                {
                  "code": "SVC2",
                  "name": "Service Branch 2",
                  "address": "Branch 2",
                  "phone": "0771234567"
                }
                """
        );
        long secondBranchId = secondBranch.path("id").asLong();

        Category category = new Category();
        category.setName("Services");
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName("Delivery");
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        JsonNode serviceItem = postJson(
                "/items",
                tenantId,
                adminToken,
                """
                {
                  "barcode": "91001",
                  "name": "Home Delivery",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 100,
                  "itemType": "SERVICE",
                  "branchIds": [%d]
                }
                """.formatted(subCategory.getId(), fixture.mainBranch().getId())
        );
        long serviceItemId = serviceItem.path("id").asLong();
        assertEquals("SERVICE", serviceItem.path("itemType").asText());
        assertEquals("SERVICE", serviceItem.path("defaultUnit").asText());
        assertEquals(1, serviceItem.path("branchIds").size());
        assertEquals(fixture.mainBranch().getId(), serviceItem.path("branchIds").get(0).asLong());

        JsonNode fetchedServiceItem = getJson("/items/" + serviceItemId, tenantId, cashierToken);
        assertEquals("SERVICE", fetchedServiceItem.path("defaultUnit").asText());

        JsonNode posResults = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + fixture.mainBranch().getId(),
                tenantId,
                cashierToken
        );
        assertEquals(1, posResults.size());
        assertEquals("SERVICE", posResults.get(0).path("defaultUnit").asText());

        JsonNode hiddenInOtherBranch = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + secondBranchId,
                tenantId,
                managerToken
        );
        assertTrue(hiddenInOtherBranch.isEmpty());

        JsonNode cashierShift = postJson(
                "/shifts/open",
                tenantId,
                cashierToken,
                """
                {
                  "openingCash": 1000,
                  "note": "Service counter"
                }
                """
        );
        assertEquals("OPEN", cashierShift.path("status").asText());

        JsonNode serviceOrder = postJson(
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 2,
                      "qtyUnit": "SERVICE",
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 200,
                  "note": "Service order"
                }
                """.formatted(fixture.mainBranch().getId(), serviceItemId)
        );
        assertEquals(200.0, serviceOrder.path("grandTotal").asDouble(), 0.001);
        assertEquals("SERVICE", serviceOrder.path("items").get(0).path("qtyUnit").asText());

        JsonNode updatedServiceItem = putJson(
                "/items/" + serviceItemId,
                tenantId,
                adminToken,
                """
                {
                  "branchIds": [%d]
                }
                """.formatted(secondBranchId)
        );
        assertEquals(1, updatedServiceItem.path("branchIds").size());
        assertEquals(secondBranchId, updatedServiceItem.path("branchIds").get(0).asLong());

        JsonNode removedFromMainBranch = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + fixture.mainBranch().getId(),
                tenantId,
                cashierToken
        );
        assertTrue(removedFromMainBranch.isEmpty());

        JsonNode visibleInSecondBranch = getJson(
                "/items/searchForPos?name=Delivery&branchId=" + secondBranchId,
                tenantId,
                managerToken
        );
        assertEquals(1, visibleInSecondBranch.size());

        JsonNode blockedOrder = jsonRequest(
                org.springframework.http.HttpMethod.POST,
                "/orders",
                tenantId,
                cashierToken,
                """
                {
                  "branchId": %d,
                  "orderType": "CASH",
                  "items": [
                    {
                      "itemId": %d,
                      "qty": 1,
                      "qtyUnit": "SERVICE",
                      "unitPrice": 100,
                      "discountType": "NONE",
                      "discountValue": 0
                    }
                  ],
                  "billDiscount": 0,
                  "paidAmount": 100,
                  "note": "Blocked service order"
                }
                """.formatted(fixture.mainBranch().getId(), serviceItemId),
                400
        );
        assertTrue(blockedOrder.path("message").asText().contains("not assigned to this branch"));
    }
}
