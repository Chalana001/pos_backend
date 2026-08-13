package com.chala.posapp.repository;
import com.chala.posapp.entity.reorder.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface ReorderPlanRepository extends JpaRepository<ReorderPlan,Long>{List<ReorderPlan> findAllByOrderByCreatedAtDesc();List<ReorderPlan> findByBranchIdOrderByCreatedAtDesc(Long branchId);}
