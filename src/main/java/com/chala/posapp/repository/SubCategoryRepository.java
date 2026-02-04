package com.chala.posapp.repository;

import com.chala.posapp.entity.SubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubCategoryRepository extends JpaRepository<SubCategory, Long> {

    List<SubCategory> findByCategoryId(Long categoryId);

    List<SubCategory> findByNameContainingIgnoreCase(String name);
}