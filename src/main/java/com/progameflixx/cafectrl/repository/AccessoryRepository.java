package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.Accessory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccessoryRepository extends JpaRepository<Accessory, String> {
    Optional<Accessory> findByIdAndCafeId(String id, String cafeId);
    List<Accessory> findByCafeId(String cafeId);

    /** Row-locks it for the rest of the transaction, so two concurrent edits/deletes are applied one after the other. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM Accessory a WHERE a.id = :id")
    java.util.Optional<Accessory> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
