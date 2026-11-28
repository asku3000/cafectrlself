package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryItem, String> {
    Optional<InventoryItem> findByIdAndCafeId(String id, String cafeId);
    List<InventoryItem> findByCafeId(String cafeId);
    List<InventoryItem> findByCafeIdAndCategoryNotIn(String cafeId, Collection<String> categories);

    /** Row-locks it for the rest of the transaction, so two concurrent edits/deletes are applied one after the other. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
    java.util.Optional<InventoryItem> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}