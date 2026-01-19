package com.chala.posapp.repository;

import com.chala.posapp.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {
    boolean existsByBarcode(String barcode);
    Optional<Item> findByBarcode(String barcode);

    List<Item> findByNameContainingIgnoreCase(String name);
}
