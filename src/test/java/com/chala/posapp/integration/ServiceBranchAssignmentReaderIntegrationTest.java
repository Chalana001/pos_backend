package com.chala.posapp.integration;

import com.chala.posapp.dto.item.ItemBranchAssignment;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.BranchServiceItem;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.repository.BranchServiceItemRepository;
import com.chala.posapp.service.ServiceBranchAssignmentReader;
import com.chala.posapp.service.ServiceBranchAssignments;
import com.chala.posapp.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-level verification of the bulk service-assignment reader
 * ({@code ServiceBranchAssignmentReader.loadFor} over
 * {@code BranchServiceItemRepository.findActiveAssignmentsForItems}) against
 * the legacy per-item reads on identical fixtures:
 * {@code findByItemId} filtered to active rows (mapToResponse's
 * {@code distinct().sorted()} branchIds) and
 * {@code existsByBranchIdAndItemIdAndActiveTrue} (searchForPos eligibility).
 *
 * <p>Rule pinned here: the three branch scopes are NOT interchangeable —
 * a concrete branch id requires an active assignment, while {@code 0} and
 * {@code null} skip the service-assignment restriction entirely (verified
 * side by side, never collapsed into one interpretation).
 *
 * <p>Runs on the H2 test profile. Physical tenant isolation cannot be
 * demonstrated here (the H2 suite is one shared in-memory database and
 * catalog switching is a no-op); that verification lives in
 * {@code ServiceBranchAssignmentMysqlIntegrationTest} on real MySQL with two
 * provisioned tenant databases. The H2 tenant test below documents this and
 * verifies the reader is request-local instead.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ServiceBranchAssignmentReaderIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private BranchServiceItemRepository branchServiceItemRepository;

    @Autowired
    private ServiceBranchAssignmentReader reader;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // ---------------------------------------------------------------
    // A. Active assignment
    // ---------------------------------------------------------------

    @Test
    @DisplayName("an active assignment is returned with its ids, listed in branchIdsFor and eligible at that branch")
    void activeAssignmentIsReturnedAndEligible() {
        Branch branch = createBranch("SVA-" + uniqueKey("B"));
        Item service = newItem("delivery fee", ItemType.SERVICE);
        long assignmentId = assign(branch.getId(), service.getId(), true);

        List<ItemBranchAssignment> rows =
                branchServiceItemRepository.findActiveAssignmentsForItems(List.of(service.getId()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).itemId()).isEqualTo(service.getId());
        assertThat(rows.get(0).branchId()).isEqualTo(branch.getId());

        ServiceBranchAssignments result = reader.loadFor(List.of(service.getId()));
        assertThat(result.branchIdsFor(service.getId())).containsExactly(branch.getId());
        assertThat(result.isEligibleForBranch(service.getId(), branch.getId())).isTrue();
        assertThat(assignmentId).isPositive();
    }

    // ---------------------------------------------------------------
    // B. Inactive assignment
    // ---------------------------------------------------------------

    @Test
    @DisplayName("an inactive assignment is invisible to the reader, to branchIdsFor and to concrete-branch eligibility")
    void inactiveAssignmentIsExcluded() {
        Branch branch = createBranch("SVB-" + uniqueKey("B"));
        Item service = newItem("parking fee", ItemType.SERVICE);
        assign(branch.getId(), service.getId(), false);

        // The bulk active-only query must agree with the legacy filter
        // (findByItemId keeps active rows only).
        List<ItemBranchAssignment> rows =
                branchServiceItemRepository.findActiveAssignmentsForItems(List.of(service.getId()));
        assertThat(rows).isEmpty();

        ServiceBranchAssignments result = reader.loadFor(List.of(service.getId()));
        assertThat(result.branchIdsFor(service.getId())).isEmpty();
        assertThat(result.isEligibleForBranch(service.getId(), branch.getId())).isFalse();
        // The legacy existence check agrees.
        assertThat(branchServiceItemRepository.existsByBranchIdAndItemIdAndActiveTrue(branch.getId(), service.getId()))
                .isFalse();
    }

    // ---------------------------------------------------------------
    // C. Multiple branches
    // ---------------------------------------------------------------

    @Test
    @DisplayName("every active branch id is returned sorted ascending, independent of insertion order")
    void multipleBranchesAreSortedAscending() {
        Branch low = createBranch("SVC1-" + uniqueKey("B"));
        Branch high = createBranch("SVC2-" + uniqueKey("B"));
        Branch middle = createBranch("SVC3-" + uniqueKey("B"));
        Item service = newItem("corkage fee", ItemType.SERVICE);
        // Insert in deliberately unsorted order; branch ids ascend by
        // creation here, so insert high-first proves the reader sorts.
        assign(high.getId(), service.getId(), true);
        assign(low.getId(), service.getId(), true);
        assign(middle.getId(), service.getId(), true);

        List<Long> expected = java.util.stream.Stream.of(low.getId(), middle.getId(), high.getId())
                .sorted().toList();

        ServiceBranchAssignments result = reader.loadFor(List.of(service.getId()));
        assertThat(result.branchIdsFor(service.getId())).containsExactlyElementsOf(expected);
        for (Long branchId : expected) {
            assertThat(result.isEligibleForBranch(service.getId(), branchId)).isTrue();
        }
    }

    // ---------------------------------------------------------------
    // D. Unassigned service item
    // ---------------------------------------------------------------

    @Test
    @DisplayName("an unassigned service item answers empty/false at a concrete branch and true for zero and null scopes")
    void unassignedServiceItem() {
        Branch branch = createBranch("SVD-" + uniqueKey("B"));
        Item unassigned = newItem("unassigned fee", ItemType.SERVICE);

        ServiceBranchAssignments result = reader.loadFor(List.of(unassigned.getId()));

        assertThat(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(unassigned.getId()))).isEmpty();
        assertThat(result.branchIdsFor(unassigned.getId())).isEmpty();
        assertThat(result.isEligibleForBranch(unassigned.getId(), branch.getId())).isFalse();
        assertThat(result.isEligibleForBranch(unassigned.getId(), 0L)).isTrue();
        assertThat(result.isEligibleForBranch(unassigned.getId(), null)).isTrue();
    }

    // ---------------------------------------------------------------
    // E. Scope behavior side by side
    // ---------------------------------------------------------------

    @Test
    @DisplayName("concrete branch, zero and null scopes are asserted side by side for assigned and unassigned items")
    void scopesAreNotCollapsed() {
        Branch assigned = createBranch("SVE1-" + uniqueKey("B"));
        Branch other = createBranch("SVE2-" + uniqueKey("B"));
        Item service = newItem("service fee", ItemType.SERVICE);
        Item bare = newItem("bare fee", ItemType.SERVICE);
        assign(assigned.getId(), service.getId(), true);

        ServiceBranchAssignments result = reader.loadFor(List.of(service.getId(), bare.getId()));

        // Assigned item.
        assertThat(result.isEligibleForBranch(service.getId(), assigned.getId())).isTrue();
        assertThat(result.isEligibleForBranch(service.getId(), other.getId())).isFalse();
        assertThat(result.isEligibleForBranch(service.getId(), 0L)).isTrue();
        assertThat(result.isEligibleForBranch(service.getId(), null)).isTrue();
        // Unassigned item.
        assertThat(result.isEligibleForBranch(bare.getId(), assigned.getId())).isFalse();
        assertThat(result.isEligibleForBranch(bare.getId(), 0L)).isTrue();
        assertThat(result.isEligibleForBranch(bare.getId(), null)).isTrue();
    }

    // ---------------------------------------------------------------
    // F. Multiple items
    // ---------------------------------------------------------------

    @Test
    @DisplayName("overlapping branch assignments of several items group under their own item ids")
    void multipleItemsGroupByItemId() {
        Branch shared = createBranch("SVF1-" + uniqueKey("B"));
        Branch extra = createBranch("SVF2-" + uniqueKey("B"));
        Item first = newItem("first fee", ItemType.SERVICE);
        Item second = newItem("second fee", ItemType.SERVICE);
        Item third = newItem("third fee", ItemType.SERVICE);
        assign(shared.getId(), first.getId(), true);
        assign(shared.getId(), second.getId(), true);
        assign(extra.getId(), first.getId(), true);

        ServiceBranchAssignments result = reader.loadFor(List.of(first.getId(), second.getId(), third.getId()));

        assertThat(result.branchIdsByItem()).containsOnlyKeys(first.getId(), second.getId());
        assertThat(result.branchIdsFor(first.getId())).containsExactlyInAnyOrder(shared.getId(), extra.getId());
        assertThat(result.branchIdsFor(second.getId())).containsExactly(shared.getId());
        assertThat(result.branchIdsFor(third.getId())).isEmpty();
    }

    // ---------------------------------------------------------------
    // G. Duplicate protection
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the (branch_id, item_id) unique constraint rejects duplicate assignment rows")
    void uniqueConstraintRejectsDuplicateRows() {
        // branch_service_items carries UNIQUE (branch_id, item_id) — the
        // entity @UniqueConstraint and V1__baseline.sql's
        // UK9c2gowi7mf88ll9olu28rd7tp are the same constraint. A second row
        // for the same pair must be rejected by the database; nothing
        // invalid is inserted and no constraint is bypassed to prove it.
        Branch branch = createBranch("SVG-" + uniqueKey("B"));
        Item service = newItem("duplicate fee", ItemType.SERVICE);
        assign(branch.getId(), service.getId(), true);

        assertThatThrownBy(() -> branchServiceItemRepository.saveAndFlush(BranchServiceItem.builder()
                .branchId(branch.getId())
                .itemId(service.getId())
                .active(true)
                .build()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("branch id collections stay distinct even if duplicated rows reach the result")
    void collectionsAreDistinctRegardless() {
        ServiceBranchAssignments result = ServiceBranchAssignments.from(List.of(
                new ItemBranchAssignment(1L, 10L),
                new ItemBranchAssignment(1L, 10L),
                new ItemBranchAssignment(1L, 10L)));

        assertThat(result.branchIdsFor(1L)).containsExactly(10L);
    }

    // ---------------------------------------------------------------
    // H. Non-service items
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the query applies no item-type filter: assignment rows of non-service ids are reported as-is")
    void nonServiceItemIdsAreNotFiltered() {
        // Characterization: the repository (findByItemId included) carries
        // no item-type restriction, so the bulk query must not invent one.
        // Normal workflows never create assignment rows for non-SERVICE
        // items (syncServiceBranches deletes them), but rows for any id must
        // pass through unchanged. The FUTURE ASSEMBLER is responsible for
        // passing only service-item ids and for applying eligibility to
        // SERVICE items — exactly where the legacy itemType == SERVICE gate
        // sits today.
        Branch branch = createBranch("SVH-" + uniqueKey("B"));
        Item normal = newItem("normal item", ItemType.NORMAL);
        assign(branch.getId(), normal.getId(), true);

        ServiceBranchAssignments result = reader.loadFor(List.of(normal.getId()));

        assertThat(result.branchIdsFor(normal.getId())).containsExactly(branch.getId());
        assertThat(result.isEligibleForBranch(normal.getId(), branch.getId())).isTrue();
    }

    // ---------------------------------------------------------------
    // I. Tenant isolation (H2 support boundary)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the reader is request-local across tenant contexts (physical isolation is proven on MySQL)")
    void readerIsRequestLocalAcrossTenantContexts() {
        // The H2 suite is one shared in-memory database — catalog switching
        // is a no-op, so two tenants cannot be physically separated here.
        // What IS verifiable on H2: the reader holds no state between calls
        // and answers each request from a fresh snapshot, whichever tenant
        // context wraps it. The real two-tenant isolation test with
        // overlapping item and branch ids runs on MySQL in
        // ServiceBranchAssignmentMysqlIntegrationTest.
        Branch branch = createBranch("SVI-" + uniqueKey("B"));
        Item service = newItem("context fee", ItemType.SERVICE);
        assign(branch.getId(), service.getId(), true);

        ServiceBranchAssignments underFirst = TenantContext.callWith("tenant-" + uniqueKey("x"),
                () -> reader.loadFor(List.of(service.getId())));
        ServiceBranchAssignments underSecond = TenantContext.callWith("tenant-" + uniqueKey("y"),
                () -> reader.loadFor(List.of(service.getId())));

        assertThat(underFirst.branchIdsFor(service.getId())).containsExactly(branch.getId());
        assertThat(underSecond.branchIdsFor(service.getId())).containsExactly(branch.getId());
        // Distinct snapshots: mutating one request's view is impossible and
        // cannot affect the other.
        assertThat(underFirst.branchIdsByItem()).isNotSameAs(underSecond.branchIdsByItem());
    }

    // ---------------------------------------------------------------
    // J. Immutable result
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the loaded snapshot cannot be mutated through its map or lists")
    void loadedResultIsImmutable() {
        Branch branch = createBranch("SVJ-" + uniqueKey("B"));
        Item service = newItem("immutable fee", ItemType.SERVICE);
        assign(branch.getId(), service.getId(), true);

        ServiceBranchAssignments result = reader.loadFor(List.of(service.getId()));

        assertThatThrownBy(() -> result.branchIdsByItem().put(99L, List.of(1L)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.branchIdsFor(service.getId()).add(99L))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(result.branchIdsFor(service.getId())).containsExactly(branch.getId());
    }

    // ---------------------------------------------------------------
    // K. Empty input
    // ---------------------------------------------------------------

    @Test
    @DisplayName("an empty request returns an empty result and executes no SQL at all")
    void emptyInputRunsNoQuery() {
        Statistics statistics = statistics();
        statistics.clear();

        ServiceBranchAssignments result = reader.loadFor(List.of());

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.branchIdsByItem()).isEmpty();
        assertThat(statistics.getQueryExecutionCount())
                .as("the reader-level empty guard must skip the repository entirely")
                .isZero();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    // ---------------------------------------------------------------
    // L. Query count
    // ---------------------------------------------------------------

    @Test
    @DisplayName("25 service item ids cost one bulk query, and result lookups run zero additional SQL")
    void oneBulkQueryForManyItemsAndFreeLookups() {
        Branch branch = createBranch("SVL-" + uniqueKey("B"));
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

        // Repeated lookups in the immutable result touch no SQL.
        statistics.clear();
        for (int round = 0; round < 3; round++) {
            for (Long id : ids) {
                result.branchIdsFor(id);
                result.isEligibleForBranch(id, branch.getId());
                result.isEligibleForBranch(id, 0L);
                result.isEligibleForBranch(id, null);
            }
        }
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    @Test
    @DisplayName("the bulk answers equal the legacy per-item reads for every item, branch and scope")
    void bulkAnswersMatchLegacyPerItemReads() {
        Branch first = createBranch("SVM1-" + uniqueKey("B"));
        Branch second = createBranch("SVM2-" + uniqueKey("B"));
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
            // branchIdsFor = legacy active-only, distinct, sorted branch ids.
            List<Long> legacyBranchIds = branchServiceItemRepository.findByItemId(itemId).stream()
                    .filter(BranchServiceItem::isActive)
                    .map(BranchServiceItem::getBranchId)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(result.branchIdsFor(itemId))
                    .as("branchIds for item %s", itemId)
                    .containsExactlyElementsOf(legacyBranchIds);

            // isEligibleForBranch = legacy existence check at concrete
            // branches; the 0/null scopes reproduce the legacy skip.
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
    // Helpers
    // ---------------------------------------------------------------

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private Item newItem(String name, ItemType itemType) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("SVB"))
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
