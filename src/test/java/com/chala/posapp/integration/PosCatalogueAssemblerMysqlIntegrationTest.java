package com.chala.posapp.integration;

import com.chala.posapp.dto.item.ItemResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.service.PosCatalogueAssembler;
import com.chala.posapp.service.TenantProvisioningService;
import com.chala.posapp.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PosCatalogueAssemblerMysqlIntegrationTest extends ApiIntegrationTestSupport {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master").withUsername("posapp").withPassword("posapp")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired private PosCatalogueAssembler assembler;
    @Autowired private ItemRepository items;
    @Autowired private TenantProvisioningService provisioning;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @PersistenceContext private EntityManager entityManager;

    private final LegacyItemApiFixture fixture = new LegacyItemApiFixture(this);

    @Test
    @DisplayName("full assembler matches the legacy endpoint on real MySQL for concrete, zero and null scopes")
    void fullAssemblyMatchesLegacyOnMysql() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        LegacyItemApiFixture.Dataset data = fixture.seed("asmmysql");
        for (Long branchId : java.util.Arrays.asList(data.mainBranchId(), data.secondBranchId(), 0L, null)) {
            JsonNode legacy = fixture.posBaseline(data, branchId);
            List<ItemResponse> responses = TenantContext.callWith(data.tenantId(),
                    () -> inTransaction(() -> assembler.assemble(branchId)));
            JsonNode assembled = objectMapper.valueToTree(responses);
            List<JsonNode> oldRows = fixture.scopedToDataset(legacy, data);
            List<JsonNode> newRows = assembled.findParents("id").stream()
                    .filter(item -> data.allItemIds().contains(item.path("id").asLong())).toList();
            JsonNode oldArray = objectMapper.valueToTree(oldRows);
            JsonNode newArray = objectMapper.valueToTree(newRows);
            ItemApiResponseComparator.assertSameItemIds(oldArray, newArray);
            for (JsonNode oldItem : oldRows) {
                ItemApiResponseComparator.assertSameItem(oldItem,
                        ItemApiResponseComparator.itemById(newArray, oldItem.path("id").asLong()), Set.of());
            }
        }
    }

    @Test
    @DisplayName("all candidate nested data stays inside the selected physical tenant database")
    void physicalTenantIsolation() {
        String tenantA = uniqueTenant("asm-a");
        String tenantB = uniqueTenant("asm-b");
        TenantContext.runWith(com.chala.posapp.tenant.CurrentTenantResolver.MASTER_TENANT,
                () -> { provisioning.provisionTenantDatabase(tenantA); provisioning.provisionTenantDatabase(tenantB); });
        long[] a = seedPhysicalTenant(tenantA, "Tenant A catalogue", "A-ITEM");
        long[] b = seedPhysicalTenant(tenantB, "Tenant B catalogue", "B-ITEM");
        assertThat(b[0]).isEqualTo(a[0]);

        Long branchA = a[1];
        Long branchB = b[1];
        List<ItemResponse> resultA = TenantContext.callWith(tenantA, () -> inTransaction(() -> assembler.assemble(branchA)));
        List<ItemResponse> resultB = TenantContext.callWith(tenantB, () -> inTransaction(() -> assembler.assemble(branchB)));
        assertThat(resultA).extracting(ItemResponse::getBarcode).containsExactly("A-ITEM");
        assertThat(resultB).extracting(ItemResponse::getBarcode).containsExactly("B-ITEM");
        assertThat(resultA.get(0).getCategoryName()).isEqualTo("Tenant A catalogue category");
        assertThat(resultB.get(0).getCategoryName()).isEqualTo("Tenant B catalogue category");
        assertThat(resultA.get(0).getAvailableBaseQty()).isEqualTo(1000);
        assertThat(resultB.get(0).getAvailableBaseQty()).isEqualTo(1000);
        assertThat(resultA.get(0).getBatches()).hasSize(1);
        assertThat(resultB.get(0).getBatches()).hasSize(1);
    }

    @Test
    @DisplayName("MySQL aggregate integer boundaries match legacy values and failures")
    void numericBoundariesMatchLegacyOnMysql() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("asmnumeric");
        TenantContext.runWith(data.tenantId(), () -> {
            assertBoundary(data, new long[]{Integer.MAX_VALUE}, Integer.MAX_VALUE);
            assertBoundary(data, new long[]{Integer.MIN_VALUE}, Integer.MIN_VALUE);
            assertBoundary(data, new long[]{Integer.MAX_VALUE, 1}, null);
            assertBoundary(data, new long[]{Integer.MIN_VALUE, -1}, null);
        });
    }

    @Test
    @DisplayName("MySQL query count remains bounded as candidate item count grows")
    void boundedQueryCount() throws Exception {
        LegacyItemApiFixture.Dataset data = fixture.seed("asmcount");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        TenantContext.runWith(data.tenantId(), () -> {
            statistics.clear();
            long small = inTransaction(() -> { assembler.assemble(data.mainBranchId()); return statistics.getQueryExecutionCount(); });
            inTransaction(() -> {
                for (int i = 0; i < 100; i++) items.save(newItem("MySQL count " + i, "MC-" + i));
                items.flush();
                return null;
            });
            statistics.clear();
            long large = inTransaction(() -> { assembler.assemble(data.mainBranchId()); return statistics.getQueryExecutionCount(); });
            assertThat(large).isLessThanOrEqualTo(small + 1);
            assertThat(large).isLessThan(30);
        });
    }

    private void assertBoundary(LegacyItemApiFixture.Dataset data, long[] quantities, Integer expected) {
        Long itemId = inTransaction(() -> {
            Branch branch = branchRepository.findById(data.mainBranchId()).orElseThrow();
            Item item = items.save(newItem("MySQL numeric " + quantities[0] + " " + UUID.randomUUID(),
                    "MN-" + UUID.randomUUID().toString().substring(0, 10)));
            for (long quantity : quantities) {
                stockBatchRepository.save(StockBatch.builder().item(item).branch(branch)
                        .quantity(Math.toIntExact(quantity)).originalQuantity(Math.toIntExact(quantity))
                        .costPrice(new BigDecimal("1.00")).sellingPrice(new BigDecimal("2.00"))
                        .sourceType(StockBatchSourceType.PURCHASE).batchCode(uniqueKey("MN-BATCH")).build());
            }
            items.flush();
            return item.getId();
        });
        Throwable legacyFailure = capture(() -> inTransaction(() ->
                stockBatchRepository.getTotalQuantityByItemAndScope(data.mainBranchId(), itemId)));
        Throwable assemblerFailure = capture(() -> inTransaction(() ->
                assembler.assemble(data.mainBranchId()).stream().filter(row -> row.getId().equals(itemId)).findFirst().orElse(null)));
        if (expected == null) {
            assertThat(legacyFailure).isInstanceOf(ConversionFailedException.class);
            assertThat(assemblerFailure).isInstanceOf(ConversionFailedException.class).isNotInstanceOf(ArithmeticException.class);
        } else {
            assertThat(legacyFailure).isNull();
            assertThat(assemblerFailure).isNull();
            assertThat(inTransaction(() -> stockBatchRepository.getTotalQuantityByItemAndScope(data.mainBranchId(), itemId)))
                    .isEqualTo(expected);
            assertThat(inTransaction(() -> assembler.assemble(data.mainBranchId()).stream()
                    .filter(row -> row.getId().equals(itemId)).findFirst().orElseThrow().getAvailableBaseQty()))
                    .isEqualTo(expected);
        }
    }

    private Throwable capture(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        try { callable.call(); return null; } catch (Throwable failure) { return failure; }
    }

    private long[] seedPhysicalTenant(String tenantId, String itemName, String barcode) {
        return TenantContext.callWith(tenantId, () -> inTransaction(() -> {
            Branch branch = new Branch();
            branch.setCode("B-" + tenantId.substring(tenantId.length() - 6));
            branch.setName("Branch " + tenantId); branch.setAddress("A"); branch.setPhone("000"); branch.setActive(true);
            branch = branchRepository.save(branch);
            Category category = new Category(); category.setName(itemName + " category"); category = categoryRepository.save(category);
            SubCategory sub = new SubCategory(); sub.setName(itemName + " sub"); sub.setCategory(category); sub = subCategoryRepository.save(sub);
            Item item = items.save(newItem(itemName, barcode));
            item.setSubCategory(sub); items.save(item);
            stockBatchRepository.save(StockBatch.builder().item(item).branch(branch).quantity(1000).originalQuantity(1000)
                    .costPrice(new BigDecimal("1.00")).sellingPrice(new BigDecimal("2.00"))
                    .sourceType(StockBatchSourceType.PURCHASE).batchCode(uniqueKey("ASM-BATCH")).build());
            entityManager.flush();
            return new long[]{item.getId(), branch.getId()};
        }));
    }

    private Item newItem(String name, String barcode) {
        return Item.builder().name(name).barcode(barcode).costPrice(new BigDecimal("1.00"))
                .sellingPrice(new BigDecimal("2.00")).reorderLevel(0).itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS).build();
    }

    private String uniqueTenant(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
