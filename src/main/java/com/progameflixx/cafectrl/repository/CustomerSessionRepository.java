package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.CustomerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CustomerSessionRepository extends JpaRepository<CustomerSession, String> {
    List<CustomerSession> findByCafeIdAndStatus(String cafeId, String status);

    // Find all billed sessions for a cafe within a time range
    @Query("SELECT s FROM CustomerSession s WHERE s.cafeId = :cafeId " +
            "AND s.status = 'billed' " +
            "AND s.billedAt >= :start AND s.billedAt < :end")
    List<CustomerSession> findBilledSessionsInRange(
            @Param("cafeId") String cafeId,
            @Param("start") Instant start,
            @Param("end") Instant end
    );

    // For Super Admin Stats
    long countByStatus(String status);

    @Query("SELECT SUM(s.billTotal) FROM CustomerSession s WHERE s.status = 'billed'")
    Double sumAllRevenue();

    List<CustomerSession> findByCafeIdAndStatusAndBilledAtBetween(
            String cafeId, String status, LocalDateTime start, LocalDateTime end);

    long countByCafeIdAndStatus(String cafeId, String status);

    @Query("SELECT DISTINCT s FROM CustomerSession s " +
            "LEFT JOIN FETCH s.games g " +
            "LEFT JOIN FETCH g.items i " +
            "WHERE s.cafeId = :cafeId AND s.status = :status " +
            "AND s.billedAt BETWEEN :start AND :end")
    List<CustomerSession> findMonthlyReportData(
            @Param("cafeId") String cafeId,
            @Param("status") String status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
}