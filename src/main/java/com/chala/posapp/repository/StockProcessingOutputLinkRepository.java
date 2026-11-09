package com.chala.posapp.repository;

import com.chala.posapp.dto.item.ProcessingOutputLinkData;
import com.chala.posapp.entity.StockProcessingOutputLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StockProcessingOutputLinkRepository extends JpaRepository<StockProcessingOutputLink, Long> {
    List<StockProcessingOutputLink> findBySourceItemIdOrderByIdAsc(Long sourceItemId);
    void deleteBySourceItemId(Long sourceItemId);

    /**
     * Bulk counterpart of {@link #findBySourceItemIdOrderByIdAsc(Long)}.
     * Source ids make groups contiguous; link ids preserve the exact legacy
     * ascending order within each source item.
     */
    @Query("""
            SELECT new com.chala.posapp.dto.item.ProcessingOutputLinkData(
                link.id,
                link.sourceItemId,
                link.outputItemId,
                link.defaultQuantity,
                link.defaultSellingPrice,
                link.waste
            )
            FROM StockProcessingOutputLink link
            WHERE link.sourceItemId IN (:sourceItemIds)
            ORDER BY link.sourceItemId ASC, link.id ASC
            """)
    List<ProcessingOutputLinkData> findProcessingOutputLinksForSources(
            @Param("sourceItemIds") Collection<Long> sourceItemIds);
}
