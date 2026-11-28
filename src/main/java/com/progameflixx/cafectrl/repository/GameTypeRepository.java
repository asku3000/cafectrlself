package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.GameType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameTypeRepository extends JpaRepository<GameType, String> {
    List<GameType> findByCafeId(String cafeId);

    /** Row-locks it for the rest of the transaction, so two concurrent edits/deletes are applied one after the other. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT g FROM GameType g WHERE g.id = :id")
    java.util.Optional<GameType> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
