package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.GameType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameTypeRepository extends JpaRepository<GameType, String> {
    List<GameType> findByCafeId(String cafeId);
}
