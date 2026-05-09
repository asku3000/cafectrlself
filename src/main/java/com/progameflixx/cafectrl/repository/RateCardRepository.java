package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RateCardRepository extends JpaRepository<RateCard, String> {
    List<RateCard> findByCafeId(String cafeId);
}