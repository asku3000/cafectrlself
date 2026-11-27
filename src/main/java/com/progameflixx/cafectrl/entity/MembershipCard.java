package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A prepaid membership card. Balances are a cache of the ledger ({@link MembershipLedgerEntry}) and are only
 * changed by MembershipCardService while the row is locked. Plan values are a snapshot of the plan sold for
 * the current validity period, so later plan edits never affect this card.
 * Serialised to the UI via MembershipCardView, never directly.
 */
@Data
@Entity
@Table(name = "membership_cards")
public class MembershipCard {

    /** Stored status. SCHEDULED is never stored; it and date-based expiry are derived by {@link #effectiveStatus}. */
    public enum Status { ACTIVE, SCHEDULED, EXPIRED, SUSPENDED, CLOSED }

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "cafe_id", nullable = false)
    private String cafeId;

    @Column(name = "card_number", nullable = false, length = 30)
    private String cardNumber;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 20)
    private String customerPhone;

    // ---- plan snapshot (current period) ----
    @Column(name = "plan_id", nullable = false)
    private String planId;
    @Column(name = "plan_name", nullable = false, length = 60)
    private String planName;
    @Column(name = "plan_color", length = 20)
    private String planColor;
    @Column(name = "plan_badge", length = 40)
    private String planBadge;
    @Column(name = "plan_load_amount_paise", nullable = false)
    private Long planLoadAmountPaise;
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_bonus_type", nullable = false, length = 10)
    private MembershipPlan.BonusType planBonusType;
    @Column(name = "plan_bonus_value", nullable = false)
    private Long planBonusValue;
    @Column(name = "plan_bonus_amount_paise", nullable = false)
    private Long planBonusAmountPaise;
    @Column(name = "plan_validity_days", nullable = false)
    private Integer planValidityDays;

    // ---- balances (cache of ledger) ----
    @Column(name = "paid_balance_paise", nullable = false)
    private Long paidBalancePaise = 0L;
    @Column(name = "bonus_balance_paise", nullable = false)
    private Long bonusBalancePaise = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Last day the card can be used (inclusive). */
    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;
    @Column(name = "period_purchased_at", nullable = false)
    private LocalDateTime periodPurchasedAt;
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
    @Column(name = "issued_by_user_id")
    private String issuedByUserId;
    @Column(name = "issued_by_name")
    private String issuedByName;

    @Version
    @JsonIgnore
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public long totalBalancePaise() {
        return paidBalancePaise + bonusBalancePaise;
    }

    /**
     * Status as of {@code today}: a stored ACTIVE/SUSPENDED card past its expiry date is EXPIRED (even before the
     * nightly job records the forfeit), and an ACTIVE card before its start date is SCHEDULED.
     */
    public Status effectiveStatus(LocalDate today) {
        if (status == Status.CLOSED || status == Status.EXPIRED) return status;
        if (today.isAfter(expiryDate)) return Status.EXPIRED;
        if (status == Status.SUSPENDED) return Status.SUSPENDED;
        if (today.isBefore(startDate)) return Status.SCHEDULED;
        return Status.ACTIVE;
    }

    /** Copies plan values onto the card for a new validity period. */
    public void applyPlanSnapshot(MembershipPlan plan) {
        planId = plan.getId();
        planName = plan.getName();
        planColor = plan.getColor();
        planBadge = plan.getBadge();
        planLoadAmountPaise = plan.getLoadAmountPaise();
        planBonusType = plan.getBonusType();
        planBonusValue = plan.getBonusValue();
        planBonusAmountPaise = plan.getBonusAmountPaise();
        planValidityDays = plan.getValidityDays();
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
