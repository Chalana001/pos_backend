package com.chala.posapp.repository;

import com.chala.posapp.entity.SubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubCategoryRepository extends JpaRepository<SubCategory, Long> {

    // ✅ Category ID එක අනුව Sub-categories filter කරලා ගන්න
    List<SubCategory> findByCategoryId(Long categoryId);

    // ✅ නම අනුව search කරන්න ඕන වුනොත්
    List<SubCategory> findByNameContainingIgnoreCase(String name);
}