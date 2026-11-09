package com.chala.posapp.service;

import com.chala.posapp.dto.item.ItemBranchAssignment;
import com.chala.posapp.repository.BranchServiceItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Bulk reader for branch service-item assignments: one grouped query replaces
 * the per-service-item {@code findByItemId} /
 * {@code existsByBranchIdAndItemIdAndActiveTrue} round trips the POS item
 * paths make today, without changing what those calls answer.
 *
 * <p>Stateless singleton — it holds only its repository dependency and never
 * retains tenant, item or assignment values between calls. Each invocation
 * builds a fresh {@link ServiceBranchAssignments} snapshot for the current
 * request and tenant; nothing is cached globally, nothing is held in a
 * ThreadLocal, and no JPA entity leaves the repository layer (the query
 * projects into the scalar {@code ItemBranchAssignment} record).
 *
 * <p>Scope of the reader: assignments only. It deliberately does not know
 * about item types — the legacy eligibility checks are gated on
 * {@code item.getItemType() == ItemType.SERVICE} before they run, so the
 * future assembler is responsible for passing service-item ids and for
 * applying the eligibility decision to SERVICE items only. Item ids that are
 * not SERVICE items are not filtered here either; if such an id somehow has
 * assignment rows, they are reported like any other id's rows, exactly as
 * the untyped {@code findByItemId} reports them today.
 */
@Service
@RequiredArgsConstructor
public class ServiceBranchAssignmentReader {

    private final BranchServiceItemRepository branchServiceItemRepository;

    /**
     * Loads the active branch assignments of the given items in one query.
     *
     * <p>An empty (or null) request returns {@link ServiceBranchAssignments#empty()}
     * without touching the database — the service-level empty guard the
     * project applies to IN-list queries (see {@code PromotionGate.limitStates};
     * the repository itself stays safe if called with an empty collection but
     * that costs one harmless query, so callers must not rely on it).
     *
     * @param itemIds the item ids to load assignments for; duplicates are
     *                harmless. Not interpreted further — see the class
     *                javadoc for the item-type responsibility split.
     * @return an immutable, request-local snapshot keyed by item id
     */
    public ServiceBranchAssignments loadFor(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return ServiceBranchAssignments.empty();
        }
        List<ItemBranchAssignment> rows = branchServiceItemRepository.findActiveAssignmentsForItems(itemIds);
        return ServiceBranchAssignments.from(rows);
    }
}
