package com.chala.posapp.integration;

import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.supplier.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.chala.posapp.integration.SqlCaptureStatementInspector"
})
class PurchaseDuplicateValidationIntegrationTest extends ApiIntegrationTestSupport {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysql.getHost() + ":"
                + mysql.getMappedPort(3306) + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Test
    void duplicateRowsReturnStructured422AndDoNotWritePurchaseOrStockState() throws Exception {
        Fixture fixture = fixture("dup-no-write");
        seedStock(fixture, 5_000);
        Snapshot before = snapshot(fixture);

        JsonNode response = postPurchaseExpecting(fixture, request(fixture,
                row(fixture.item().getId(), "20.00", "30.00", "2027-05-01", "2"),
                row(fixture.item().getId(), "20", "30.0", "2027-05-01", "2.000")));

        assertProblem(response, List.of(new Duplicate(1, 0)));
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    @Test
    void reportsEveryRelationshipAcrossMultipleDuplicateGroups() throws Exception {
        Fixture fixture = fixture("dup-groups");
        Item secondItem = createItem(fixture, "Second item");
        Snapshot before = snapshot(fixture, fixture.item(), secondItem);

        JsonNode response = postPurchaseExpecting(fixture, request(fixture,
                row(fixture.item().getId(), "20", "30", null, "2"),
                row(secondItem.getId(), "20", "30", null, "2"),
                row(fixture.item().getId(), "20.0", "30.00", "", "2.0"),
                row(secondItem.getId(), "20.00", "30.0", null, "2.000")));

        assertProblem(response, List.of(new Duplicate(2, 0), new Duplicate(3, 1)));
        Snapshot after = snapshot(fixture, fixture.item(), secondItem);
        assertThat(after.items().get(fixture.item().getId()))
                .as("first affected item %d inventory state", fixture.item().getId())
                .isEqualTo(before.items().get(fixture.item().getId()));
        assertThat(after.items().get(secondItem.getId()))
                .as("second affected item %d inventory state", secondItem.getId())
                .isEqualTo(before.items().get(secondItem.getId()));
        assertThat(after.purchaseCount()).as("purchase count").isEqualTo(before.purchaseCount());
        assertThat(after.grnCount()).as("GRN count").isEqualTo(before.grnCount());
        assertThat(after.grnItemCount()).as("GRN item count").isEqualTo(before.grnItemCount());
        assertThat(after.stockBatchCount()).as("stock batch count").isEqualTo(before.stockBatchCount());
        assertThat(after.supplierDue()).as("supplier due balance").isEqualTo(before.supplierDue());
    }

    @Test
    void validPurchaseWithDistinctRowsStillCreatesReceiptAndStock() throws Exception {
        Fixture fixture = fixture("dup-valid");
        Snapshot before = snapshot(fixture);

        JsonNode response = postPurchase(fixture, request(fixture,
                row(fixture.item().getId(), "20", "30", "2027-05-01", "2"),
                row(fixture.item().getId(), "20", "31", "2027-05-01", "2"),
                row(fixture.item().getId(), "20", "30", "2027-06-01", "2"),
                row(fixture.item().getId(), "20", "30", "2027-05-01", "3")));

        assertThat(response.path("purchaseId").asLong()).isPositive();
        Snapshot after = snapshot(fixture);
        assertThat(after.purchaseCount()).isEqualTo(before.purchaseCount() + 1);
        assertThat(after.grnCount()).isEqualTo(before.grnCount() + 1);
        assertThat(after.grnItemCount()).isEqualTo(before.grnItemCount() + 4);
        assertThat(after.stockBatchCount()).isEqualTo(before.stockBatchCount() + 4);
        assertThat(after.items().get(fixture.item().getId()).stockQuantity())
                .isEqualTo(before.items().get(fixture.item().getId()).stockQuantity() + 9_000);
    }

    @Test
    void duplicateReplacementLeavesOriginalPurchaseAndStockUnchanged() throws Exception {
        Fixture fixture = fixture("dup-replace");
        JsonNode created = postPurchase(fixture, request(fixture,
                row(fixture.item().getId(), "20", "30", null, "2")));
        long originalId = created.path("purchaseId").asLong();
        Snapshot before = snapshot(fixture);
        Purchase originalBefore = purchaseRepository.findById(originalId).orElseThrow();

        JsonNode response = jsonRequest(HttpMethod.POST, "/purchases/" + originalId + "/replace",
                fixture.tenantId(), fixture.token(), request(fixture,
                        row(fixture.item().getId(), "21", "31", "2027-06-01", "1"),
                        row(fixture.item().getId(), "21.0", "31.00", "2027-06-01", "1.000")), 422);

        assertProblem(response, List.of(new Duplicate(1, 0)));
        Snapshot after = snapshot(fixture);
        Purchase originalAfter = purchaseRepository.findById(originalId).orElseThrow();
        assertThat(after).isEqualTo(before);
        assertThat(originalAfter.getStatus()).isEqualTo(originalBefore.getStatus());
        assertThat(originalAfter.getCanceledAt()).isEqualTo(originalBefore.getCanceledAt());
        assertThat(originalAfter.getReplacedByPurchaseId()).isEqualTo(originalBefore.getReplacedByPurchaseId());
    }

    private Fixture fixture(String key) throws Exception {
        TenantFixture tenant = seedTenantShop(uniqueKey(key), 3);
        String token = login(tenant.tenantId(), tenant.admin().getUsername(), DEFAULT_PASSWORD);
        JsonNode category = postJson("/categories", tenant.tenantId(), token,
                "{\"name\":\"" + uniqueKey("Purchase category") + "\"}");
        JsonNode subCategory = postJson("/categories/sub-categories", tenant.tenantId(), token,
                "{\"name\":\"" + uniqueKey("Purchase subcategory") + "\",\"categoryId\":" + category.path("id").asLong() + "}");
        JsonNode supplierJson = postJson("/suppliers", tenant.tenantId(), token,
                "{\"name\":\"" + uniqueKey("Purchase supplier") + "\",\"phone\":\"07"
                        + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L)) + "\",\"active\":true}");
        Supplier supplier = supplierRepository.findById(supplierJson.path("id").asLong()).orElseThrow();
        Item item = createItem(tenant, token, subCategory.path("id").asLong(), "Main purchase item");
        return new Fixture(tenant.tenantId(), token, tenant.mainBranch().getId(), supplier, item);
    }

    private Item createItem(Fixture fixture, String name) throws Exception {
        return createItem(fixture.tenantId(), fixture.token(), fixture.item().getSubCategory().getId(), name);
    }

    private Item createItem(TenantFixture tenant, String token, long subCategoryId, String name) throws Exception {
        return createItem(tenant.tenantId(), token, subCategoryId, name);
    }

    private Item createItem(String tenantId, String token, long subCategoryId, String name) throws Exception {
        JsonNode created = postJson("/items", tenantId, token,
                "{\"barcode\":\"" + uniqueKey("PURCHASE") + "\",\"name\":\"" + name
                        + "\",\"subCategoryId\":" + subCategoryId + ",\"costPrice\":20,\"sellingPrice\":30,\"reorderLevel\":0}");
        return itemRepository.findById(created.path("id").asLong()).orElseThrow();
    }

    private void seedStock(Fixture fixture, int quantity) {
        stockBatchRepository.save(StockBatch.builder()
                .branch(branchRepository.findById(fixture.branchId()).orElseThrow())
                .item(fixture.item())
                .supplier(fixture.supplier())
                .quantity(quantity)
                .originalQuantity(quantity)
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .batchCode("DUP-BASE-" + UUID.randomUUID())
                .build());
    }

    private Snapshot snapshot(Fixture fixture) {
        return snapshot(fixture, fixture.item());
    }

    private Snapshot snapshot(Fixture fixture, Item... items) {
        java.util.Map<Long, ItemSnapshot> itemStates = new java.util.LinkedHashMap<>();
        for (Item requestedItem : items) {
            Item item = itemRepository.findById(requestedItem.getId()).orElseThrow();
            Integer itemStock = stockBatchRepository.getTotalQuantityByItemAndBranch(fixture.branchId(), item.getId());
            itemStates.put(item.getId(), new ItemSnapshot(itemStock, item.getCostPrice(), item.getSellingPrice()));
        }
        Supplier supplier = supplierRepository.findById(fixture.supplier().getId()).orElseThrow();
        return new Snapshot(purchaseRepository.count(), grnRepository.count(), grnItemRepository.count(),
                stockBatchRepository.count(), itemStates, supplier.getDueAmount());
    }

    private String request(Fixture fixture, String... items) {
        return "{\"supplierId\":" + fixture.supplier().getId() + ",\"invoiceNo\":\"INV-" + uniqueKey("DUPINV")
                + "\",\"branches\":[{\"branchId\":" + fixture.branchId() + ",\"items\":[" + String.join(",", items) + "]}]}";
    }

    private String row(long itemId, String cost, String sell, String expiry, String qty) {
        String expiryValue = expiry == null ? "null" : "\"" + expiry + "\"";
        return "{\"itemId\":" + itemId + ",\"qty\":" + qty + ",\"costPrice\":" + cost
                + ",\"sellingPrice\":" + sell + ",\"expiryDate\":" + expiryValue + "}";
    }

    private JsonNode postPurchase(Fixture fixture, String body) throws Exception {
        return jsonRequest(HttpMethod.POST, "/purchases", fixture.tenantId(), fixture.token(), body, 200);
    }

    private JsonNode postPurchaseExpecting(Fixture fixture, String body) throws Exception {
        return jsonRequest(HttpMethod.POST, "/purchases", fixture.tenantId(), fixture.token(), body, 422);
    }

    private void assertProblem(JsonNode response, List<Duplicate> expected) {
        assertThat(response.path("status").asInt()).isEqualTo(422);
        assertThat(response.path("title").asText()).contains("Unprocessable");
        assertThat(response.path("detail").asText()).isEqualTo("Duplicate purchase entries detected.");
        assertThat(response.path("success").asBoolean()).isFalse();
        assertThat(response.path("code").asText()).isEqualTo("DUPLICATE_PURCHASE_ROWS");
        assertThat(response.path("correlationId").asText()).isNotBlank();
        assertThat(response.path("duplicates").isArray()).isTrue();
        assertThat(response.path("duplicates")).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(response.path("duplicates").get(i).path("rowIndex").asInt()).isEqualTo(expected.get(i).rowIndex());
            assertThat(response.path("duplicates").get(i).path("duplicateOfRowIndex").asInt()).isEqualTo(expected.get(i).duplicateOfRowIndex());
        }
    }

    private record Fixture(String tenantId, String token, long branchId, Supplier supplier, Item item) {}
    private record ItemSnapshot(Integer stockQuantity, BigDecimal costPrice, BigDecimal sellingPrice) {}
    private record Snapshot(long purchaseCount, long grnCount, long grnItemCount, long stockBatchCount,
                            java.util.Map<Long, ItemSnapshot> items, BigDecimal supplierDue) {}
    private record Duplicate(int rowIndex, int duplicateOfRowIndex) {}
}
