package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.PendingPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PendingPaymentRepository extends JpaRepository<PendingPayment, Long> {

    List<PendingPayment> findBySessionId(String sessionId);

    /** Row-locks a debt while it is settled, so two settlements can't both see it as PENDING. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PendingPayment p WHERE p.id = :id")
    java.util.Optional<PendingPayment> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    // Finds all pending or cleared debts for the cafe
    List<PendingPayment> findByCafeIdAndStatus(String cafeId, String status);

    // Finds debts that were settled today to add to the daily revenue
    List<PendingPayment> findByCafeIdAndStatusAndClearedAtBetween(String cafeId, String status, LocalDateTime start, LocalDateTime end);
}