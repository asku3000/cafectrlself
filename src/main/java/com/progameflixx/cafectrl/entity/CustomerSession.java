package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "customer_sessions")
public class CustomerSession {
    @Id
    private String id = UUID.randomUUID().toString();

    private String cafeId;
    private String customerName;
    private String customerPhone;

    // Links to the GameSession table
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "customerSession")
    private List<GameSession> games = new ArrayList<>();

    private String status = "active";
    private Double billTotal = 0.0;
    private Double adjustment = 0.0;

    // Links to a sub-table for split payments
    @ElementCollection
    @CollectionTable(name = "session_payments", joinColumns = @JoinColumn(name = "session_id"))
    private List<PaymentSplit> payments = new ArrayList<>();

    private String operatorId;
    private String operatorName;
    private Instant createdAt = Instant.now();
    private Instant billedAt;

    @Data
    @Embeddable
    public static class PaymentSplit {
        private String mode; // cash | upi | card
        private Double amount;
    }
}