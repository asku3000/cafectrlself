package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    @JsonManagedReference
    @OneToMany(mappedBy = "customerSession", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<GameSession> games = new LinkedHashSet<>();

    // 3. Update the Setter (This is where your error likely is!)
    // Change List to Set
    // 2. Update the Getter
    // 1. Change the field type to Set
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payments", columnDefinition = "json")
    private List<PaymentSplit> payments = new java.util.ArrayList<>();

    private String status = "active"; // "active" | "billed" | "cancelled" | "voided"

    @Column(name = "bill_total")
    @JsonProperty("bill_total")
    private Double billTotal = 0.0;

    private Double adjustment = 0.0;

/*    // Payments mapped cleanly as JSON using your new PaymentSplit class
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<PaymentSplit> payments = new ArrayList<>();*/

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

    @Transient
    @JsonProperty("game_charges")
    private Double gameCharges;

    @Transient
    @JsonProperty("item_charges")
    private Double itemCharges;

    @Transient
    private Double subtotal;

    @Transient
    @JsonProperty("bill_breakdown")
    private java.util.Map<String, Object> billBreakdown;

    private String notes;

    // Optional prepaid membership card tagged to this session (null = non-member flow, unchanged).
    // Column is added by ddl-auto like the rest of this table; it is nullable so existing rows are untouched.
    @Column(name = "membership_card_id", length = 36)
    @JsonProperty("membership_card_id")
    private String membershipCardId;

    // Incremented on every checkout; a bill that is reopened and billed again gets a new number, which keys
    // its card debit (BILL:<session>:<seq>) so retries never double-deduct. Null on older rows = 0.
    @Column(name = "bill_seq")
    @JsonProperty("bill_seq")
    private Integer billSeq;

    // Live view of the tagged card (holder, balance, expiry, warnings) for the UI; never stored.
    @Transient
    @JsonProperty("membership_card")
    private com.progameflixx.cafectrl.dto.MembershipCardView membershipCard;

    // Helper method to keep JPA synchronization perfect
    public void addGame(GameSession game) {
        games.add(game);
        game.setCustomerSession(this);
    }
}