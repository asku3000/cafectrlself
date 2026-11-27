package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.MembershipCard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipCardRepository extends JpaRepository<MembershipCard, String> {

    Optional<MembershipCard> findByIdAndCafeId(String id, String cafeId);

    /** Row-locks the card (SELECT ... FOR UPDATE) so concurrent balance changes serialise. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MembershipCard c WHERE c.id = :id AND c.cafeId = :cafeId")
    Optional<MembershipCard> findForUpdate(@Param("id") String id, @Param("cafeId") String cafeId);

    List<MembershipCard> findByCafeIdOrderByIssuedAtDesc(String cafeId);

    /** Name / phone / card-number search, newest first. */
    @Query("SELECT c FROM MembershipCard c WHERE c.cafeId = :cafeId AND (" +
            "LOWER(c.customerName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "c.customerPhone LIKE CONCAT('%', :q, '%') OR " +
            "LOWER(c.cardNumber) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY c.issuedAt DESC")
    List<MembershipCard> search(@Param("cafeId") String cafeId, @Param("q") String q);

    /** Cards (any cafe) whose validity has passed but whose expiry has not been recorded yet. */
    @Query("SELECT c.id FROM MembershipCard c WHERE c.status IN :statuses AND c.expiryDate < :today")
    List<String> findIdsDueForExpiry(@Param("statuses") Collection<MembershipCard.Status> statuses,
                                     @Param("today") LocalDate today);
}
