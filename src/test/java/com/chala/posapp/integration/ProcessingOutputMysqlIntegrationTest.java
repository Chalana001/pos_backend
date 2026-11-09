package com.chala.posapp.integration;

import com.chala.posapp.dto.item.ProcessingOutputValue;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.StockProcessingOutputLink;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.StockProcessingOutputLinkRepository;
import com.chala.posapp.service.ProcessingOutputReader;
import com.chala.posapp.service.ProcessingOutputResult;
import com.chala.posapp.service.TenantProvisioningService;
import com.chala.posapp.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real MySQL 8 verification for the processing-output bulk reader. Docker is
 * the only skip condition; the companion suite guard rejects other disabling
 * paths while Docker is available.
 */
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProcessingOutputMysqlIntegrationTest extends ApiIntegrationTestSupport {

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
    private StockProcessingOutputLinkRepository linkRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ProcessingOutputReader reader;

    @Autowired
    private TenantProvisioningService tenantProvisioningService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("suite runs against real MySQL 8")
    void realMySql() {
        assertThat(mysql.isRunning()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
    }

    @Test
    @DisplayName("MySQL relationship and output projections preserve legacy fields")
    void bulkMappingOnMySql() {
        Item normal = newItem("Normal", "MY-PROC-N", ItemType.NORMAL, MeasurementUnit.PCS, "19.50");
        Item weight = newItem("Weight", "MY-PROC-W", ItemType.WEIGHT, MeasurementUnit.KG, "21.00");
        Item source = newItem("Processor", "MY-PROC-S", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(source, normal, 3000, new BigDecimal("8.75"), true);
        saveLink(source, weight, 1500, null, false);

        ProcessingOutputResult result = reader.loadForSources(List.of(source.getId()));
        List<ProcessingOutputValue> values = result.outputsFor(source.getId());

        assertThat(values).hasSize(2);
        assertThat(find(values, normal.getId()).defaultQty()).isEqualByComparingTo("3");
        assertThat(find(values, normal.getId()).defaultSellingPrice()).isEqualByComparingTo("8.75");
        assertThat(find(values, normal.getId()).waste()).isTrue();
        assertThat(find(values, weight.getId()).defaultQty()).isEqualByComparingTo("1.5");
        assertThat(find(values, weight.getId()).defaultQtyUnit()).isEqualTo(MeasurementUnit.KG);
        assertThat(find(values, weight.getId()).defaultSellingPrice()).isEqualByComparingTo("21.00");
    }

    @Test
    @DisplayName("two physical tenant databases isolate processing relationships and output metadata")
    void tenantIsolation() {
        String tenantA = uniqueKey("proca");
        String tenantB = uniqueKey("procb");
        tenantProvisioningService.provisionTenantDatabase(tenantA);
        tenantProvisioningService.provisionTenantDatabase(tenantB);

        long[] a = seedTenant(tenantA, "Tenant A", 2000, new BigDecimal("7.25"), true);
        long[] b = seedTenant(tenantB, "Tenant B", 500, new BigDecimal("8.75"), false);

        assertThat(b[0]).isEqualTo(a[0]);
        assertThat(b[1]).isEqualTo(a[1]);

        ProcessingOutputResult resultA = TenantContext.callWith(tenantA,
                () -> reader.loadForSources(List.of(a[0])));
        ProcessingOutputResult resultB = TenantContext.callWith(tenantB,
                () -> reader.loadForSources(List.of(b[0])));
        ProcessingOutputValue valueA = resultA.outputsFor(a[0]).get(0);
        ProcessingOutputValue valueB = resultB.outputsFor(b[0]).get(0);

        assertThat(valueA.defaultQty()).isEqualByComparingTo("2");
        assertThat(valueB.defaultQty()).isEqualByComparingTo("0.5");
        assertThat(valueA.outputName()).isEqualTo("Tenant A output");
        assertThat(valueB.outputName()).isEqualTo("Tenant B output");
        assertThat(valueA.defaultSellingPrice()).isEqualByComparingTo("7.25");
        assertThat(valueB.defaultSellingPrice()).isEqualByComparingTo("8.75");
        assertThat(valueA.waste()).isTrue();
        assertThat(valueB.waste()).isFalse();
    }

    @Test
    @DisplayName("MySQL bulk query count is two and result access is SQL-free")
    void boundedQueries() {
        List<Long> sourceIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Item output = newItem("Output " + i, uniqueKey("MY-OUT"), ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
            Item source = newItem("Source " + i, uniqueKey("MY-SRC"), ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
            saveLink(source, output, 1000, null, false);
            sourceIds.add(source.getId());
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        ProcessingOutputResult result = reader.loadForSources(sourceIds);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        statistics.clear();
        sourceIds.forEach(id -> result.outputsFor(id).forEach(ProcessingOutputValue::toResponse));
        assertThat(statistics.getQueryExecutionCount()).isZero();
        statistics.clear();
        assertThat(reader.loadForSources(List.of()).isEmpty()).isTrue();
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    @Test
    @DisplayName("6,001 source ids bind safely on MySQL")
    void largeSourceCollection() {
        Item output = newItem("Large output", "MY-LARGE-OUT", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        Item source = newItem("Large source", "MY-LARGE-SRC", ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
        saveLink(source, output, 1000, null, false);
        List<Long> ids = new ArrayList<>();
        for (long id = 20_000_000L; id < 20_006_000L; id++) {
            ids.add(id);
        }
        ids.add(source.getId());
        ProcessingOutputResult result = reader.loadForSources(ids);
        assertThat(result.valuesBySource()).containsOnlyKeys(source.getId());
        assertThat(result.outputsFor(source.getId())).hasSize(1);
    }

    private long[] seedTenant(String tenantId, String label, int quantity,
                              BigDecimal override, boolean waste) {
        return TenantContext.callWith(tenantId, () -> {
            Item output = newItem(label + " output", uniqueKey("TEN-OUT"),
                    ItemType.NORMAL, MeasurementUnit.PCS, "33.00");
            Item source = newItem(label + " source", uniqueKey("TEN-SRC"),
                    ItemType.NORMAL, MeasurementUnit.PCS, "1.00");
            saveLink(source, output, quantity, override, waste);
            return new long[]{source.getId(), output.getId()};
        });
    }

    private Item newItem(String name, String barcode, ItemType type,
                         MeasurementUnit unit, String price) {
        return itemRepository.save(Item.builder().name(name).barcode(barcode)
                .costPrice(new BigDecimal("1.00")).sellingPrice(new BigDecimal(price)).reorderLevel(0)
                .itemType(type).defaultUnit(unit).build());
    }

    private StockProcessingOutputLink saveLink(Item source, Item output, int quantity,
                                                BigDecimal override, boolean waste) {
        return linkRepository.save(StockProcessingOutputLink.builder()
                .sourceItemId(source.getId()).outputItemId(output.getId())
                .defaultQuantity(quantity).defaultSellingPrice(override).waste(waste).build());
    }

    private ProcessingOutputValue find(List<ProcessingOutputValue> values, long id) {
        return values.stream().filter(value -> value.outputItemId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("missing output " + id));
    }
}
