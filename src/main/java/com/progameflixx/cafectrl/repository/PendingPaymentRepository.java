package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.PendingPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PendingPaymentRepository extends JpaRepository<PendingPayment, Long> {

    // Finds all pending or cleared debts for the cafe
    List<PendingPayment> findByCafeIdAndStatus(String cafeId, String status);

    // Finds debts that were settled today to add to the daily revenue
    List<PendingPayment> findByCafeIdAndStatusAndClearedAtBetween(String cafeId, String status, LocalDateTime start, LocalDateTime end);
}