package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.MembershipLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipLedgerRepository extends JpaRepository<MembershipLedgerEntry, Long> {

    List<MembershipLedgerEntry> findByCardIdOrderByIdDesc(String cardId);

    Optional<MembershipLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    /** All entries of a cafe in [from, to), oldest first — reports. */
    List<MembershipLedgerEntry> findByCafeIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(
            String cafeId, java.time.LocalDateTime from, java.time.LocalDateTime to);

    List<MembershipLedgerEntry> findBySessionIdAndEntryTypeOrderByIdAsc(String sessionId, MembershipLedgerEntry.Type entryType);

    /** [sum of paid portions, sum of bonus portions] for a card — used to reconcile cached balances. */
    @Query("SELECT COALESCE(SUM(e.paidPortionPaise), 0), COALESCE(SUM(e.bonusPortionPaise), 0) " +
            "FROM MembershipLedgerEntry e WHERE e.cardId = :cardId")
    List<Object[]> sumPortions(@Param("cardId") String cardId);
}
