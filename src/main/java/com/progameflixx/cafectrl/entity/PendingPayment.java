package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "pending_payments")
public class PendingPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionId;
    private String cafeId;
    private String customerName;
    private String customerPhone;
    private Double amount;

    private String status; // "PENDING", "CLEARED"
    private LocalDateTime createdAt;

    private LocalDateTime clearedAt;
    private String settlementMode; // "CASH", "UPI", "CARD"

}