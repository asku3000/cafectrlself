package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, String> {
    Optional<Resource> findByIdAndCafeId(String id, String cafeId);
    List<Resource> findByCafeId(String cafeId);
}