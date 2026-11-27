package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One part of how a card sale / recharge was paid, e.g. ₹600 by UPI. Amount in paise. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MembershipPaymentPart {
    /** cash | upi | card */
    private String mode;

    @JsonProperty("amount_paise")
    private Long amountPaise;
}
