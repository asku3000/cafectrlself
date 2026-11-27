package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSplit {
    private String mode;   // "cash", "upi", "card", "pending", "membership" (server-generated only)
    private Double amount;

    // ---- only set on the server-generated "membership" split; omitted from JSON otherwise ----
    @JsonProperty("card_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cardId;

    @JsonProperty("card_number")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cardNumber;

    @JsonProperty("ledger_entry_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long ledgerEntryId;

    @JsonProperty("amount_paise")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long amountPaise;

    @JsonProperty("card_balance_after_paise")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long cardBalanceAfterPaise;

    public PaymentSplit(String mode, Double amount) {
        this.mode = mode;
        this.amount = amount;
    }

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CustomerSession customerSession;
}