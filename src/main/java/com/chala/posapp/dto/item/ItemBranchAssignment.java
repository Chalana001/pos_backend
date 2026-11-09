package com.chala.posapp.dto.item;

/**
 * One active service-item → branch assignment row, produced by the bulk
 * retrieval query in {@code BranchServiceItemRepository}
 * ({@code findActiveAssignmentsForItems}).
 *
 * <p>Raw scalar projection only — the {@code branch_service_items} table
 * stores {@code item_id} and {@code branch_id} as plain columns (no item or
 * branch association), so the query needs no joins and no entity is retained.
 * The record carries exactly the two columns the current per-item reads use:
 * {@code ItemService.mapToResponse} maps assignments to branch ids, and
 * {@code existsByBranchIdAndItemIdAndActiveTrue} decides concrete-branch
 * eligibility from the same rows.
 */
public record ItemBranchAssignment(Long itemId, Long branchId) {
}
