package com.chala.posapp.repository;
import com.chala.posapp.entity.supplier.SupplierItem; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface SupplierItemReadRepository extends JpaRepository<SupplierItem,Long>{List<SupplierItem> findByItemIdAndSupplierActiveTrueOrderByPrimarySupplierDescIdAsc(Long itemId);}
