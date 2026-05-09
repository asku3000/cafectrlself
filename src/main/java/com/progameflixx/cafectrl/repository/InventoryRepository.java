package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryItem, String> {
    Optional<InventoryItem> findByIdAndCafeId(String id, String cafeId);
}