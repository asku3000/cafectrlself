package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An admin-configured membership plan (e.g. "Silver: load ₹1000, +10% bonus, 90 days").
 * Plan values are copied onto a card when it is sold, so editing a plan never changes issued cards.
 * Money is integer paise.
 */
@Data
@Entity
@Table(name = "membership_plans")
public class MembershipPlan {

    public enum BonusType { FIXED, PERCENT }

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "cafe_id", nullable = false)
    @JsonProperty("cafe_id")
    private String cafeId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "load_amount_paise", nullable = false)
    @JsonProperty("load_amount_paise")
    private Long loadAmountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "bonus_type", nullable = false, length = 10)
    @JsonProperty("bonus_type")
    private BonusType bonusType = BonusType.FIXED;

    /** FIXED: paise. PERCENT: basis points (1000 = 10%). */
    @Column(name = "bonus_value", nullable = false)
    @JsonProperty("bonus_value")
    private Long bonusValue = 0L;

    @Column(name = "validity_days", nullable = false)
    @JsonProperty("validity_days")
    private Integer validityDays;

    @Column(name = "is_active", nullable = false)
    @JsonProperty("is_active")
    private Boolean isActive = true;

    @Column(length = 20)
    private String color;

    @Column(length = 40)
    private String badge;

    @Column(name = "sort_order", nullable = false)
    @JsonProperty("sort_order")
    private Integer sortOrder = 0;

    @Column(length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by_name")
    @JsonProperty("updated_by_name")
    private String updatedByName;

    /** Bonus this plan grants on its load amount (derived, for display). */
    @JsonProperty("bonus_amount_paise")
    public long getBonusAmountPaise() {
        return MembershipMath.bonusFor(loadAmountPaise, bonusType, bonusValue);
    }

    /** Total value credited to a card: load + bonus (derived, for display). */
    @JsonProperty("total_value_paise")
    public long getTotalValuePaise() {
        return (loadAmountPaise != null ? loadAmountPaise : 0L) + getBonusAmountPaise();
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
