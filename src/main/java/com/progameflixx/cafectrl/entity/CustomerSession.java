package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "customer_sessions")
public class CustomerSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "cafe_id")
    @JsonProperty("cafe_id")
    private String cafeId;

    @Column(name = "customer_name")
    @JsonProperty("customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    @JsonProperty("customer_phone")
    private String customerPhone;

    // THE LINK TO GAMES
    // CascadeType.ALL means if you save the CustomerSession, it saves the Games automatically!
    @OneToMany(mappedBy = "customerSession", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GameSession> games = new ArrayList<>();

    private String status = "active"; // "active" | "billed"

    @Column(name = "bill_total")
    @JsonProperty("bill_total")
    private Double billTotal = 0.0;

    private Double adjustment = 0.0;

    // Payments mapped cleanly as JSON using your new PaymentSplit class
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<PaymentSplit> payments = new ArrayList<>();

    @Column(name = "operator_id")
    @JsonProperty("operator_id")
    private String operatorId;

    @Column(name = "operator_name")
    @JsonProperty("operator_name")
    private String operatorName;

    @Column(name = "created_at")
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Column(name = "billed_at")
    @JsonProperty("billed_at")
    private LocalDateTime billedAt;

    // Helper method to keep JPA synchronization perfect
    public void addGame(GameSession game) {
        games.add(game);
        game.setCustomerSession(this);
    }
}