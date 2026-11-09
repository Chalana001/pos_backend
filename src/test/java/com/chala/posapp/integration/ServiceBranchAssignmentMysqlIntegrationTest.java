package com.chala.posapp.integration;

import com.chala.posapp.dto.item.ItemBranchAssignment;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.BranchServiceItem;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.repository.BranchServiceItemRepository;
import com.chala.posapp.service.ServiceBranchAssignmentReader;
import com.chala.posapp.service.ServiceBranchAssignments;
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
 * TASK-008A MySQL verification of the bulk service-assignment reader
 * ({@code ServiceBranchAssignmentReader} over
 * {@code BranchServiceItemRepository.findActiveAssignmentsForItems}) against
 * real MySQL 8 through Testcontainers.
 *
 * <p>This class is where the H2 suite cannot reach: physical tenant
 * isolation. Two real tenant databases are provisioned with
 * {@code TenantProvisioningService}, given OVERLAPPING item and branch ids,
 * and each reader call must return only the current tenant's assignments —
 * never a combination of both.
 *
 * <p>Skip policy: {@code @Testcontainers(disabledWithoutDocker = true)} is
 * the only disable path and it fires ONLY when Docker is absent — with
 * Docker available every test here executes (or fails); nothing can
 * silently skip. If Docker is unavailable in another environment this
 * verification is PENDING, never described as passed
 * (see {@code ServiceBranchAssignmentMysqlSuiteGuardTest}).
 */
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ServiceBranchAssignmentMysqlIntegrationTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            // pos_db must exist before the app boots: raw connections anchor on
            // it and the tenant migration chain runs over it.
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
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
    private BranchServiceItemRepository branchServiceItemRepository;

    @Autowired
    private ServiceBranchAssignmentReader reader;

    @Autowired
    private TenantProvisioningService tenantProvisioningService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------
    // 0. Environment: these tests really ran against MySQL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the suite is talking to real MySQL 8 — a silent skip or an H2 fallback cannot pass")
    void environmentIsRealMySql() {
        assertThat(mysql.isRunning()).isTrue();
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertThat(version)
                .as("this class must not silently pass on H2 or without a container")
                .startsWith("8.");
    }

    // ---------------------------------------------------------------
    // 1. Bulk answers equal the legacy per-item reads on MySQL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("bulk answers equal the legacy per-item reads for every item, branch and scope on MySQL")
    void bulkAnswersMatchLegacyPerItemReadsOnMySql() {
        Branch first = createBranch("SMA1-" + uniqueKey("B"));
        Branch second = createBranch("SMA2-" + uniqueKey("B"));
        Item assigned = newItem("assigned fee", ItemType.SERVICE);
        Item inactiveOnly = newItem("inactive fee", ItemType.SERVICE);
        Item mixed = newItem("mixed fee", ItemType.SERVICE);
        Item unassigned = newItem("unassigned fee", ItemType.SERVICE);
        assign(first.getId(), assigned.getId(), true);
        assign(second.getId(), assigned.getId(), true);
        assign(first.getId(), inactiveOnly.getId(), false);
        assign(first.getId(), mixed.getId(), true);
        assign(second.getId(), mixed.getId(), false);

        List<Long> allIds = List.of(assigned.getId(), inactiveOnly.getId(), mixed.getId(), unassigned.getId());
        ServiceBranchAssignments result = reader.loadFor(allIds);

        for (Long itemId : allIds) {
            List<Long> legacyBranchIds = branchServiceItemRepository.findByItemId(itemId).stream()
                    .filter(BranchServiceItem::isActive)
                    .map(BranchServiceItem::getBranchId)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(result.branchIdsFor(itemId))
                    .as("branchIds for item %s", itemId)
                    .containsExactlyElementsOf(legacyBranchIds);

            for (Branch branch : List.of(first, second)) {
                assertThat(result.isEligibleForBranch(itemId, branch.getId()))
                        .as("eligibility of item %s at branch %s", itemId, branch.getId())
                        .isEqualTo(branchServiceItemRepository
                                .existsByBranchIdAndItemIdAndActiveTrue(branch.getId(), itemId));
            }
            assertThat(result.isEligibleForBranch(itemId, 0L)).isTrue();
            assertThat(result.isEligibleForBranch(itemId, null)).isTrue();
        }
    }

    // ---------------------------------------------------------------
    // 2. Tenant isolation with overlapping item and branch ids
    // ---------------------------------------------------------------

    @Test
    @DisplayName("two tenant databases with overlapping item and branch ids never see each other's assignments")
    void tenantIsolationWithOverlappingIds() {
        // Two REAL tenant databases (pos_<slug>), provisioned through the
        // production path. Fresh schemas give both the same auto-increment
        // ids — the overlap is asserted below, which is what makes a leak
        // detectable.
        String tenantA = uniqueKey("isoa");
        String tenantB = uniqueKey("isob");
        tenantProvisioningService.provisionTenantDatabase(tenantA);
        tenantProvisioningService.provisionTenantDatabase(tenantB);

        // Tenant A: two branches, item assigned to branch 1 only.
        long[] a = seedTenantAssignments(tenantA, 1L);
        // Tenant B: two branches, item assigned to branch 2 only.
        long[] b = seedTenantAssignments(tenantB, 2L);

        long itemA = a[0];
        long branchOneA = a[1];
        long branchTwoA = a[2];
        long itemB = b[0];
        long branchOneB = b[1];
        long branchTwoB = b[2];

        // The ids really overlap across tenants — the isolation assertion
        // below would be meaningless otherwise.
        assertThat(itemB).isEqualTo(itemA);
        assertThat(branchOneB).isEqualTo(branchOneA);
        assertThat(branchTwoB).isEqualTo(branchTwoA);

        ServiceBranchAssignments resultA = TenantContext.callWith(tenantA,
                () -> reader.loadFor(List.of(itemA)));
        ServiceBranchAssignments resultB = TenantContext.callWith(tenantB,
                () -> reader.loadFor(List.of(itemB)));

        // Each tenant sees exactly its own assignment set.
        assertThat(resultA.branchIdsFor(itemA)).containsExactly(branchOneA);
        assertThat(resultB.branchIdsFor(itemB)).containsExactly(branchTwoB);
        // And its own eligibility decisions only.
        assertThat(resultA.isEligibleForBranch(itemA, branchOneA)).isTrue();
        assertThat(resultA.isEligibleForBranch(itemA, branchTwoA)).isFalse();
        assertThat(resultB.isEligibleForBranch(itemB, branchOneB)).isFalse();
        assertThat(resultB.isEligibleForBranch(itemB, branchTwoB)).isTrue();
    }

    /**
     * Seeds one tenant database: two branches (ids 1 and 2 in every fresh
     * schema) and one SERVICE item assigned to exactly one of them. Returns
     * {itemId, branchOneId, branchTwoId}. Everything runs inside the tenant
     * context so writes land in that tenant's catalog.
     */
    private long[] seedTenantAssignments(String tenantId, long assignedBranchOrdinal) {
        return TenantContext.callWith(tenantId, () -> {
            Branch one = createBranch("SMT1-" + uniqueKey("B"));
            Branch two = createBranch("SMT2-" + uniqueKey("B"));
            Item service = newItem("tenant fee", ItemType.SERVICE);
            if (assignedBranchOrdinal == 1L) {
                assign(one.getId(), service.getId(), true);
            } else {
                assign(two.getId(), service.getId(), true);
            }
            return new long[]{service.getId(), one.getId(), two.getId()};
        });
    }

    // ---------------------------------------------------------------
    // 3. Large IN-list binding
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a 6,001-element IN list binds and executes on MySQL, returning only real rows")
    void largeInListBindsOnMySql() {
        Branch branch = createBranch("SMI-" + uniqueKey("B"));
        Item real = newItem("real fee", ItemType.SERVICE);
        assign(branch.getId(), real.getId(), true);

        // 6,000 non-matching filler ids plus the one real item, mirroring
        // the catalogue scale these queries are built for. Binding and
        // result behavior only — no performance claim is made here.
        List<Long> ids = new ArrayList<>();
        for (long id = 10_000_000L; id < 10_006_000L; id++) {
            ids.add(id);
        }
        ids.add(real.getId());

        Statistics statistics = statistics();
        statistics.clear();
        List<ItemBranchAssignment> rows =
                branchServiceItemRepository.findActiveAssignmentsForItems(ids);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).itemId()).isEqualTo(real.getId());
        assertThat(rows.get(0).branchId()).isEqualTo(branch.getId());
        assertThat(statistics.getQueryExecutionCount())
                .as("6,001 ids must still be one query")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // 4. Query counts
    // ---------------------------------------------------------------

    @Test
    @DisplayName("25 service item ids cost one bulk query on MySQL and result lookups run zero SQL")
    void oneBulkQueryForManyItemsOnMySql() {
        Branch branch = createBranch("SMQ-" + uniqueKey("B"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Item service = newItem("query count fee " + i, ItemType.SERVICE);
            assign(branch.getId(), service.getId(), true);
            ids.add(service.getId());
        }
        Statistics statistics = statistics();

        statistics.clear();
        ServiceBranchAssignments result = reader.loadFor(ids);
        assertThat(statistics.getQueryExecutionCount())
                .as("25 items must cost one query, not 25")
                .isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        statistics.clear();
        for (Long id : ids) {
            result.branchIdsFor(id);
            result.isEligibleForBranch(id, branch.getId());
        }
        assertThat(statistics.getQueryExecutionCount()).isZero();

        // The reader-level empty guard executes nothing at all.
        statistics.clear();
        assertThat(reader.loadFor(List.of()).isEmpty()).isTrue();
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private Item newItem(String name, ItemType itemType) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("SMB"))
                .name(name)
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .reorderLevel(0)
                .itemType(itemType)
                .build());
    }

    private Branch createBranch(String code) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName(code + " branch");
        branch.setAddress("Colombo");
        branch.setPhone("0770000000");
        branch.setActive(true);
        return branchRepository.save(branch);
    }

    /** Seeds one assignment row exactly as the table stores it. */
    private long assign(Long branchId, Long itemId, boolean active) {
        return branchServiceItemRepository.save(BranchServiceItem.builder()
                .branchId(branchId)
                .itemId(itemId)
                .active(active)
                .build()).getId();
    }
}
