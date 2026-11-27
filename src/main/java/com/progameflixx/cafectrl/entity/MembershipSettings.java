package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Per-cafe membership configuration. One row per cafe, created with defaults on first read. */
@Data
@Entity
@Table(name = "membership_settings")
public class MembershipSettings {

    /** Which balance a card payment consumes first. */
    public enum DeductionOrder { PAID_FIRST, BONUS_FIRST }

    @Id
    @Column(name = "cafe_id")
    @JsonProperty("cafe_id")
    private String cafeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "deduction_order", nullable = false, length = 20)
    @JsonProperty("deduction_order")
    private DeductionOrder deductionOrder = DeductionOrder.PAID_FIRST;

    /** Cards whose total balance is at or below this are flagged "low balance". */
    @Column(name = "low_balance_threshold_paise", nullable = false)
    @JsonProperty("low_balance_threshold_paise")
    private Long lowBalanceThresholdPaise = 10_000L;

    /** Prefix for generated card numbers, e.g. "MC" -> MC-000123. */
    @Column(name = "card_number_prefix", nullable = false, length = 10)
    @JsonProperty("card_number_prefix")
    private String cardNumberPrefix = "MC";

    /** Next sequence number for generated card numbers; advanced under a row lock. */
    @Column(name = "next_card_seq", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long nextCardSeq = 1L;

    @Column(name = "updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by_name")
    @JsonProperty("updated_by_name")
    private String updatedByName;
}
