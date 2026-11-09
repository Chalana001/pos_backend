package com.chala.posapp.repository;

import com.chala.posapp.dto.item.ItemBranchAssignment;
import com.chala.posapp.entity.BranchServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BranchServiceItemRepository extends JpaRepository<BranchServiceItem, Long> {

    boolean existsByBranchIdAndItemIdAndActiveTrue(Long branchId, Long itemId);

    List<BranchServiceItem> findByItemId(Long itemId);

    void deleteByItemId(Long itemId);

    // ------------------------------------------------------------------
    // Bulk active-assignment retrieval. The predicate mirrors the per-item
    // reads above exactly: mapToResponse keeps only isActive rows of
    // findByItemId, and existsByBranchIdAndItemIdAndActiveTrue checks the
    // same active population. branch_id and item_id are plain columns on
    // this table (no item or branch association), so no join is needed and
    // none is added. Ordered by item then branch so grouping is
    // deterministic; callers still sort the per-item branch ids themselves,
    // exactly like the legacy Java-side distinct().sorted().
    //
    // Empty itemIds is safe but not free: Hibernate renders the empty IN
    // list as a query matching no rows (no invalid IN () SQL), one
    // execution, empty result. The project convention for IN-list queries
    // is a service-level empty guard (PromotionGate.limitStates);
    // ServiceBranchAssignmentReader.loadFor guards the same way — callers
    // must not rely on the repository for empty-input performance.
    // ------------------------------------------------------------------

    /**
     * Active service-item → branch assignments for many items at once — the
     * bulk form of {@link #findByItemId} (active rows only) and
     * {@link #existsByBranchIdAndItemIdAndActiveTrue}, replacing one
     * per-service-item round trip with one query.
     */
    @Query("""
            SELECT new com.chala.posapp.dto.item.ItemBranchAssignment(bsi.itemId, bsi.branchId)
            FROM BranchServiceItem bsi
            WHERE bsi.itemId IN (:itemIds) AND bsi.active = true
            ORDER BY bsi.itemId ASC, bsi.branchId ASC
            """)
    List<ItemBranchAssignment> findActiveAssignmentsForItems(@Param("itemIds") Collection<Long> itemIds);
}
