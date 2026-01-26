package com.chala.posapp.repository;

import com.chala.posapp.entity.GRN;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GrnRepository extends JpaRepository<GRN, Long> {

    Optional<GRN> findByGrnNo(String grnNo);

    // අන්තිම GRN Number එක ගන්න (අලුත් එක හදන්න උදව්වක් විදියට)
    @Query(value = "SELECT * FROM grn WHERE branch_id = :branchId ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<GRN> findLastGrnByBranch(@Param("branchId") Long branchId);

    Optional<GRN> findTopByBranchIdOrderByIdDesc(Long branchId);

    List<GRN> findAllByOrderByIdDesc();

    // Custom Query එකක් ලියනවා Search + Pagination සඳහා
    @Query("SELECT g FROM GRN g WHERE " +
            "(:query IS NULL OR :query = '' OR " +
            "LOWER(g.grnNo) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(g.supplier.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(g.note) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<GRN> searchGrns(@Param("query") String query, Pageable pageable);
}