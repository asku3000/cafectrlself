package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    // For Cafe Admins: Only see their own cafe's logs
    List<AuditLog> findByCafeIdOrderByCreatedAtDesc(String cafeId, Pageable pageable);

    // For Super Admins: See everything across all cafes
    List<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // Safely filters by Cafe ID and exact Date boundaries, sorting newest first
    @Query("SELECT a FROM AuditLog a WHERE a.cafeId = :cafeId AND a.createdAt >= :start AND a.createdAt <= :end ORDER BY a.createdAt DESC")
    List<AuditLog> findAuditsByCafeAndDate(
            @Param("cafeId") String cafeId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable
    );
}