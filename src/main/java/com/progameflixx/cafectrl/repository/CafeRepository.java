package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.Cafe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CafeRepository extends JpaRepository<Cafe, String> {
    // By extending JpaRepository, Spring automatically generates
    // the save(), findById(), and findAll() methods behind the scenes!
}
