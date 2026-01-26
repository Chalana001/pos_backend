package com.chala.posapp.repository;

import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.GrnItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GrnItemRepository extends JpaRepository<GrnItem, Long> {

    List<GrnItem> findByGrn(GRN grn);

    List<GrnItem> findByGrnId(Long grnId);
}