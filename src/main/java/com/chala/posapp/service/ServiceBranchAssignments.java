package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemBranchAssignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable, request-local snapshot of the active service-item → branch
 * assignments produced by {@link ServiceBranchAssignmentReader}, keyed by
 * item id.
 *
 * <p>It reproduces exactly what the per-item reads answer today:
 * <ul>
 *   <li>{@code branchIdsFor(itemId)} — the active assigned branch ids of one
 *       item, de-duplicated and sorted ascending, the same list
 *       {@code ItemService.mapToResponse} builds from {@code findByItemId}
 *       (active rows only, {@code distinct().sorted()}); an item with no
 *       active assignments yields an empty list, never {@code null};</li>
 *   <li>{@code isEligibleForBranch(itemId, branchId)} — the concrete-branch
 *       eligibility decision {@code ItemService.searchForPos} makes with
 *       {@code existsByBranchIdAndItemIdAndActiveTrue}. The three branch
 *       scopes are NOT interchangeable and are not collapsed here:
 *       a concrete id requires an active assignment to that branch, while
 *       {@code 0} (all-branch scope) and {@code null} (no scope) skip the
 *       service-assignment restriction entirely and therefore answer
 *       {@code true}. Callers apply this only to SERVICE items — the legacy
 *       checks are gated on {@code item.getItemType() == ItemType.SERVICE}
 *       before they ever run.</li>
 * </ul>
 *
 * <p>The snapshot holds only scalar {@link ItemBranchAssignment} values —
 * never JPA entities — and every map and list it hands out is unmodifiable,
 * so a caller cannot mutate the result out from under the request. Nothing
 * is cached or shared between invocations: each reader call builds a fresh
 * snapshot for the current request and tenant.
 */
public final class ServiceBranchAssignments {

    private static final ServiceBranchAssignments EMPTY =
            new ServiceBranchAssignments(Collections.emptyMap());

    /** Item id → active branch ids, ascending and distinct. Fully immutable. */
    private final Map<Long, List<Long>> branchIdsByItem;

    private ServiceBranchAssignments(Map<Long, List<Long>> branchIdsByItem) {
        this.branchIdsByItem = branchIdsByItem;
    }

    /** An empty snapshot — the answer for an empty request. */
    public static ServiceBranchAssignments empty() {
        return EMPTY;
    }

    /**
     * Groups the active assignment rows by item id. Per item the branch ids
     * are de-duplicated and sorted ascending regardless of row order (the
     * database unique constraint on (branch_id, item_id) already prevents
     * duplicate rows; {@code distinct()} keeps the collections distinct even
     * if a caller ever constructs one from duplicated rows).
     */
    public static ServiceBranchAssignments from(List<ItemBranchAssignment> rows) {
        Map<Long, List<Long>> grouped = new TreeMap<>();
        for (ItemBranchAssignment row : rows) {
            grouped.computeIfAbsent(row.itemId(), key -> new ArrayList<>()).add(row.branchId());
        }
        Map<Long, List<Long>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Long>> entry : grouped.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().stream().distinct().sorted().toList());
        }
        return new ServiceBranchAssignments(Collections.unmodifiableMap(snapshot));
    }

    /**
     * The active assigned branch ids of one item, ascending and distinct.
     * An item without active assignments (or never loaded) returns an empty
     * immutable list — never {@code null}.
     */
    public List<Long> branchIdsFor(Long itemId) {
        return branchIdsByItem.getOrDefault(itemId, List.of());
    }

    /**
     * Whether the service-assignment restriction allows this item at the
     * requested scope, matching {@code ItemService.searchForPos}:
     * a concrete branch id requires an active assignment to that branch;
     * {@code 0} and {@code null} skip the restriction and answer
     * {@code true}. Only meaningful for SERVICE items — the legacy eligibility
     * checks never run for any other item type.
     */
    public boolean isEligibleForBranch(Long itemId, Long branchId) {
        if (branchId == null || branchId == 0L) {
            return true;
        }
        return branchIdsFor(itemId).contains(branchId);
    }

    /** The full item-id → branch-ids snapshot. Map and lists are unmodifiable. */
    public Map<Long, List<Long>> branchIdsByItem() {
        return branchIdsByItem;
    }

    public boolean isEmpty() {
        return branchIdsByItem.isEmpty();
    }
}
