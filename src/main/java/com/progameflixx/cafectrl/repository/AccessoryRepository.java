package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.Accessory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccessoryRepository extends JpaRepository<Accessory, String> {
    Optional<Accessory> findByIdAndCafeId(String id, String cafeId);
}
