package com.chala.posapp.integration;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.repository.CategoryRepository;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.SubCategoryRepository;
import com.chala.posapp.service.PosCatalogueCandidateReader;
import com.chala.posapp.service.TenantProvisioningService;
import com.chala.posapp.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real MySQL 8 verification for the tenant-global candidate reader. Docker is
 * the only skip condition; with Docker available this class must execute.
 */
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PosCatalogueCandidateReaderMysqlIntegrationTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                        + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private SubCategoryRepository subCategoryRepository;

    @Autowired
    private PosCatalogueCandidateReader reader;

    @Autowired
    private TenantProvisioningService tenantProvisioningService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("the suite is talking to real MySQL 8")
    void environmentIsRealMySql() {
        assertThat(mysql.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
    }

    @Test
    @DisplayName("candidate query and category fetch execute in one bounded MySQL statement")
    void candidateQueryAndCategoryFetchWorkOnMysql() {
        String tenant = uniqueTenant("candidate-a");
        provision(tenant);
        TenantContext.callWith(tenant, () -> inTransaction(() -> {
            Category category = new Category();
            category.setName("MySQL category " + tenant);
            category = categoryRepository.save(category);
            SubCategory subCategory = new SubCategory();
            subCategory.setName("MySQL subcategory " + tenant);
            subCategory.setCategory(category);
            subCategory = subCategoryRepository.save(subCategory);
            Item item = itemRepository.save(item("MySQL candidate " + tenant, "MYSQL-" + tenant, subCategory));
            entityManager.flush();
            entityManager.clear();

            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            List<Item> candidates = reader.loadCandidates();
            Item found = candidates.stream().filter(candidate -> candidate.getId().equals(item.getId())).findFirst().orElseThrow();

            assertThat(found.getSubCategory().getName()).isEqualTo(subCategory.getName());
            assertThat(found.getSubCategory().getCategory().getName()).isEqualTo(category.getName());
            assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
            return null;
        }));
    }

    @Test
    @DisplayName("two physical tenant databases never mix overlapping item ids or metadata")
    void physicalTenantIsolation() {
        String tenantA = uniqueTenant("candidate-a");
        String tenantB = uniqueTenant("candidate-b");
        provision(tenantA);
        provision(tenantB);

        long idA = TenantContext.callWith(tenantA, () -> inTransaction(() -> saveTenantItem("Tenant A item", "TENANT-A")));
        long idB = TenantContext.callWith(tenantB, () -> inTransaction(() -> saveTenantItem("Tenant B item", "TENANT-B")));

        Set<Long> idsA = TenantContext.callWith(tenantA, () -> inTransaction(() -> reader.loadCandidates().stream().map(Item::getId).collect(Collectors.toSet())));
        Set<Long> idsB = TenantContext.callWith(tenantB, () -> inTransaction(() -> reader.loadCandidates().stream().map(Item::getId).collect(Collectors.toSet())));

        assertThat(idA).isEqualTo(idB);
        assertThat(idsA).contains(idA);
        assertThat(idsB).contains(idB);
        assertThat(TenantContext.callWith(tenantA, () -> inTransaction(() -> reader.loadCandidates())))
                .extracting(Item::getBarcode).containsExactly("TENANT-A");
        assertThat(TenantContext.callWith(tenantB, () -> inTransaction(() -> reader.loadCandidates())))
                .extracting(Item::getBarcode).containsExactly("TENANT-B");
    }

    @Test
    @DisplayName("MySQL soft deletion remains excluded and 6001 rows are not paged")
    void softDeleteAndLargeCatalogueOnMysql() {
        String tenant = uniqueTenant("candidate-large");
        provision(tenant);
        TenantContext.callWith(tenant, () -> inTransaction(() -> {
            for (int i = 0; i < 6001; i++) {
                itemRepository.save(item("MySQL large " + i, "MYSQL-LARGE-" + i, null));
                if (i % 500 == 499) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }
            Item deleted = itemRepository.save(item("MySQL deleted", "MYSQL-DELETED", null));
            deleted.setDeletedAt(java.time.LocalDateTime.now());
            itemRepository.save(deleted);
            entityManager.flush();
            entityManager.clear();

            List<Item> candidates = reader.loadCandidates();
            assertThat(candidates).hasSize(6001);
            assertThat(candidates).extracting(Item::getBarcode)
                    .contains("MYSQL-LARGE-0", "MYSQL-LARGE-6000")
                    .doesNotContain("MYSQL-DELETED");
            return null;
        }));
    }

    private void provision(String tenant) {
        TenantContext.callWith(com.chala.posapp.tenant.CurrentTenantResolver.MASTER_TENANT,
                () -> tenantProvisioningService.provisionTenantDatabase(tenant));
    }

    private long saveTenantItem(String name, String barcode) {
        Item item = itemRepository.save(item(name, barcode, null));
        entityManager.flush();
        return item.getId();
    }

    private Item item(String name, String barcode, SubCategory subCategory) {
        return Item.builder()
                .name(name)
                .barcode(barcode)
                .subCategory(subCategory)
                .costPrice(new BigDecimal("1.00"))
                .sellingPrice(new BigDecimal("2.00"))
                .reorderLevel(0)
                .itemType(ItemType.NORMAL)
                .defaultUnit(MeasurementUnit.PCS)
                .active(true)
                .posVisible(true)
                .build();
    }

    private String uniqueTenant(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
