package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RateCardRepository extends JpaRepository<RateCard, String> {
    List<RateCard> findByCafeId(String cafeId);

    /** Row-locks it for the rest of the transaction, so two concurrent edits/deletes are applied one after the other. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT r FROM RateCard r WHERE r.id = :id")
    java.util.Optional<RateCard> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}