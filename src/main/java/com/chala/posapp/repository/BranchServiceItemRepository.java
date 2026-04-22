package com.chala.posapp.repository;

import com.chala.posapp.entity.BranchServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BranchServiceItemRepository extends JpaRepository<BranchServiceItem, Long> {

    boolean existsByBranchIdAndItemIdAndActiveTrue(Long branchId, Long itemId);

    List<BranchServiceItem> findByItemId(Long itemId);

    void deleteByItemId(Long itemId);
}
