package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    // For Cafe Admins: Only see their own cafe's logs
    List<AuditLog> findByCafeIdOrderByCreatedAtDesc(String cafeId, Pageable pageable);

    // For Super Admins: See everything across all cafes
    List<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}