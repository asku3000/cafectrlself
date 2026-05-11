package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "cafe_id")
    private String cafeId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "user_role")
    private String userRole;

    private String action;
    private String target;

    // Stores the dynamic JSON details (what changed, amounts, etc.)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> meta;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}