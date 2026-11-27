package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * One immutable balance change (or status event) on a membership card. Rows are only ever inserted:
 * Hibernate is told the entity is {@link Immutable}, so updates are ignored and there is no delete path.
 * The card's cached balances always equal the sums of paid_portion / bonus_portion over its entries.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "membership_ledger")
public class MembershipLedgerEntry {

    public enum Type {
        LOAD, RECHARGE, BONUS, BILL_DEBIT, REVERSAL, ADJUSTMENT,
        EXPIRY_FORFEIT, RECHARGE_FORFEIT, CLOSE_FORFEIT,
        // zero-amount status events, kept so the card history is complete
        EXPIRY_EXTENDED, SUSPENDED, REACTIVATED, CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cafe_id", nullable = false)
    @JsonProperty("cafe_id")
    private String cafeId;

    @Column(name = "card_id", nullable = false)
    @JsonProperty("card_id")
    private String cardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    @JsonProperty("entry_type")
    private Type entryType;

    @Column(name = "amount_paise", nullable = false)
    @JsonProperty("amount_paise")
    private Long amountPaise;

    @Column(name = "paid_portion_paise", nullable = false)
    @JsonProperty("paid_portion_paise")
    private Long paidPortionPaise;

    @Column(name = "bonus_portion_paise", nullable = false)
    @JsonProperty("bonus_portion_paise")
    private Long bonusPortionPaise;

    @Column(name = "paid_balance_after_paise", nullable = false)
    @JsonProperty("paid_balance_after_paise")
    private Long paidBalanceAfterPaise;

    @Column(name = "bonus_balance_after_paise", nullable = false)
    @JsonProperty("bonus_balance_after_paise")
    private Long bonusBalanceAfterPaise;

    @Column(name = "payment_mode", length = 20)
    @JsonProperty("payment_mode")
    private String paymentMode;

    @Column(name = "plan_id")
    @JsonProperty("plan_id")
    private String planId;

    @Column(name = "plan_name", length = 60)
    @JsonProperty("plan_name")
    private String planName;

    @Column(name = "session_id")
    @JsonProperty("session_id")
    private String sessionId;

    @Column(name = "reverses_entry_id")
    @JsonProperty("reverses_entry_id")
    private Long reversesEntryId;

    @Column(name = "idempotency_key", length = 150)
    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    @Column(name = "user_id")
    @JsonProperty("user_id")
    private String userId;

    @Column(name = "user_name")
    @JsonProperty("user_name")
    private String userName;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("balance_after_paise")
    public long getBalanceAfterPaise() {
        return paidBalanceAfterPaise + bonusBalanceAfterPaise;
    }
}
