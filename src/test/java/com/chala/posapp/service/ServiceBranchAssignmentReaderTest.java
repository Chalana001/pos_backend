package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemBranchAssignment;
import com.chala.posapp.repository.BranchServiceItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the request-local bulk assignment reader and its immutable result:
 * one bulk repository call per load (never per item), the exact legacy
 * semantics of the two answers the future assembler needs
 * ({@code branchIdsFor} = active-only, de-duplicated, sorted ascending;
 * {@code isEligibleForBranch} = concrete id needs an active assignment while
 * {@code 0} and {@code null} skip the restriction), empty-input handling at
 * the reader level, and immutability of every map and list the result hands
 * out.
 *
 * <p>The repository is mocked so only the reader/result layer is under test;
 * the query itself is exercised by the H2 and MySQL integration tests.
 */
class ServiceBranchAssignmentReaderTest {

    private BranchServiceItemRepository branchServiceItemRepository;
    private ServiceBranchAssignmentReader reader;

    @BeforeEach
    void setUp() {
        branchServiceItemRepository = mock(BranchServiceItemRepository.class);
        reader = new ServiceBranchAssignmentReader(branchServiceItemRepository);
    }

    // ---------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------

    @Test
    @DisplayName("one bulk repository call loads all requested items, never one call per item")
    void oneBulkCallPerLoad() {
        when(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(
                        new ItemBranchAssignment(1L, 10L),
                        new ItemBranchAssignment(2L, 10L),
                        new ItemBranchAssignment(3L, 20L)));

        ServiceBranchAssignments result = reader.loadFor(List.of(1L, 2L, 3L));

        verify(branchServiceItemRepository).findActiveAssignmentsForItems(List.of(1L, 2L, 3L));
        assertThat(result.branchIdsFor(1L)).containsExactly(10L);
        assertThat(result.branchIdsFor(2L)).containsExactly(10L);
        assertThat(result.branchIdsFor(3L)).containsExactly(20L);
    }

    @Test
    @DisplayName("an empty request returns an empty result without querying the repository")
    void emptyInputSkipsTheRepository() {
        assertThat(reader.loadFor(List.of()).branchIdsByItem()).isEmpty();
        assertThat(reader.loadFor(null).branchIdsByItem()).isEmpty();
        verifyNoInteractions(branchServiceItemRepository);
    }

    // ---------------------------------------------------------------
    // branchIdsFor — legacy mapToResponse semantics
    // ---------------------------------------------------------------

    @Test
    @DisplayName("branch ids are active-only rows, de-duplicated and sorted ascending regardless of row order")
    void branchIdsAreDistinctAndSorted() {
        // Rows deliberately unsorted and with a duplicated assignment to
        // prove the collection is distinct and sorted by the reader, not by
        // row order.
        when(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(1L))).thenReturn(List.of(
                new ItemBranchAssignment(1L, 30L),
                new ItemBranchAssignment(1L, 10L),
                new ItemBranchAssignment(1L, 20L),
                new ItemBranchAssignment(1L, 10L)));

        ServiceBranchAssignments result = reader.loadFor(List.of(1L));

        assertThat(result.branchIdsFor(1L)).containsExactly(10L, 20L, 30L);
    }

    @Test
    @DisplayName("an item without active assignments returns an empty list, never null")
    void unassignedItemReturnsEmptyListNotNull() {
        when(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(1L, 2L)))
                .thenReturn(List.of(new ItemBranchAssignment(1L, 10L)));

        ServiceBranchAssignments result = reader.loadFor(List.of(1L, 2L));

        assertThat(result.branchIdsFor(2L)).isEmpty();
        assertThat(result.branchIdsFor(2L)).isNotNull();
        // An id that was never loaded answers the same way.
        assertThat(result.branchIdsFor(999L)).isEmpty();
    }

    @Test
    @DisplayName("rows group under their own item ids")
    void rowsGroupByItemId() {
        when(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(1L, 2L))).thenReturn(List.of(
                new ItemBranchAssignment(1L, 10L),
                new ItemBranchAssignment(2L, 20L),
                new ItemBranchAssignment(1L, 30L)));

        ServiceBranchAssignments result = reader.loadFor(List.of(1L, 2L));

        assertThat(result.branchIdsFor(1L)).containsExactly(10L, 30L);
        assertThat(result.branchIdsFor(2L)).containsExactly(20L);
    }

    // ---------------------------------------------------------------
    // isEligibleForBranch — legacy searchForPos semantics
    // ---------------------------------------------------------------

    @Test
    @DisplayName("concrete branch, zero and null scopes are decided side by side and never collapsed")
    void scopeBehaviorIsNotCollapsed() {
        when(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(1L, 2L))).thenReturn(List.of(
                new ItemBranchAssignment(1L, 10L)));

        ServiceBranchAssignments result = reader.loadFor(List.of(1L, 2L));

        // Assigned item 1 at its assigned branch 10.
        assertThat(result.isEligibleForBranch(1L, 10L)).isTrue();
        // Assigned item 1 at a different concrete branch.
        assertThat(result.isEligibleForBranch(1L, 20L)).isFalse();
        // Unassigned item 2 at a concrete branch.
        assertThat(result.isEligibleForBranch(2L, 10L)).isFalse();
        // All-branch scope skips the restriction for both.
        assertThat(result.isEligibleForBranch(1L, 0L)).isTrue();
        assertThat(result.isEligibleForBranch(2L, 0L)).isTrue();
        // Null scope skips the restriction for both.
        assertThat(result.isEligibleForBranch(1L, null)).isTrue();
        assertThat(result.isEligibleForBranch(2L, null)).isTrue();
    }

    // ---------------------------------------------------------------
    // Duplicate protection
    // ---------------------------------------------------------------

    @Test
    @DisplayName("duplicated assignment rows cannot produce duplicated branch ids")
    void duplicatedRowsStillProduceDistinctBranchIds() {
        // The (branch_id, item_id) unique constraint prevents this at the
        // database level; the result must stay distinct regardless.
        List<ItemBranchAssignment> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new ItemBranchAssignment(1L, 10L));
            rows.add(new ItemBranchAssignment(1L, 20L));
        }

        ServiceBranchAssignments result = ServiceBranchAssignments.from(rows);

        assertThat(result.branchIdsFor(1L)).containsExactly(10L, 20L);
    }

    // ---------------------------------------------------------------
    // Immutability
    // ---------------------------------------------------------------

    @Test
    @DisplayName("no caller can mutate the assignment map or the branch id lists")
    void resultIsImmutable() {
        when(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(1L))).thenReturn(List.of(
                new ItemBranchAssignment(1L, 10L)));

        ServiceBranchAssignments result = reader.loadFor(List.of(1L));
        Map<Long, List<Long>> exposed = result.branchIdsByItem();

        // Map mutation attempts fail and leave the snapshot untouched.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exposed.put(2L, List.of(1L)))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exposed.remove(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        // List mutation attempts fail too.
        List<Long> branchIds = result.branchIdsFor(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> branchIds.add(99L))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> branchIds.clear())
                .isInstanceOf(UnsupportedOperationException.class);

        // The snapshot is exactly as it was.
        assertThat(result.branchIdsFor(1L)).containsExactly(10L);
        assertThat(exposed).containsOnlyKeys(1L);
        assertThat(result.isEligibleForBranch(1L, 99L)).isFalse();
    }

    @Test
    @DisplayName("the empty result is immutable as well")
    void emptyResultIsImmutable() {
        Map<Long, List<Long>> exposed = ServiceBranchAssignments.empty().branchIdsByItem();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exposed.put(1L, List.of(1L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------
    // Item-type responsibility
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the reader applies no item-type filter — assignment rows of any id are reported as-is")
    void readerDoesNotFilterByItemType() {
        // The repository query is deliberately untyped (like findByItemId);
        // the future assembler passes service-item ids and applies
        // eligibility to SERVICE items only. A row reaching the reader for a
        // non-service id must not be silently dropped here.
        when(branchServiceItemRepository.findActiveAssignmentsForItems(List.of(42L)))
                .thenReturn(List.of(new ItemBranchAssignment(42L, 10L)));

        ServiceBranchAssignments result = reader.loadFor(List.of(42L));

        assertThat(result.branchIdsFor(42L)).containsExactly(10L);
    }

    @Test
    @DisplayName("a null map view never escapes: unknown ids read through the snapshot API only")
    void unknownIdsReadThroughSnapshotApi() {
        ServiceBranchAssignments result = ServiceBranchAssignments.from(
                List.of(new ItemBranchAssignment(1L, 10L)));

        // LinkedHashMap grouping keeps the full view consistent with the
        // per-item accessor for every id.
        Map<Long, List<Long>> expected = new LinkedHashMap<>();
        expected.put(1L, List.of(10L));
        assertThat(result.branchIdsByItem()).isEqualTo(expected);
    }
}
