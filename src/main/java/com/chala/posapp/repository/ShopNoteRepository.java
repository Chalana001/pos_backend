package com.chala.posapp.repository;

import com.chala.posapp.entity.ShopNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ShopNoteRepository extends JpaRepository<ShopNote, Long> {

    /** Pinned first, then newest — the order an operator wants to read them in. */
    List<ShopNote> findByTenantIdOrderByPinnedDescCreatedAtDesc(String tenantId);

    long countByTenantId(String tenantId);

    @Modifying
    @Query("delete from ShopNote n where n.tenantId = :tenantId")
    void deleteByTenantId(@Param("tenantId") String tenantId);

    /** Note counts for the whole shop list in one query. Rows of {tenantId, count}. */
    @Query("select n.tenantId, count(n) from ShopNote n group by n.tenantId")
    List<Object[]> countGroupedByTenant();
}
