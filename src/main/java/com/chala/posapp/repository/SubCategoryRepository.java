package com.chala.posapp.repository;

import com.chala.posapp.entity.SubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubCategoryRepository extends JpaRepository<SubCategory, Long> {

    List<SubCategory> findByCategoryId(Long categoryId);

    List<SubCategory> findByNameContainingIgnoreCase(String name);

    Optional<SubCategory> findByNameIgnoreCase(String name);

    Optional<SubCategory> findByCategoryIdAndNameIgnoreCase(Long categoryId, String name);
}
