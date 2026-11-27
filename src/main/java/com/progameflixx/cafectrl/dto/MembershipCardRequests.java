package com.progameflixx.cafectrl.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;

/** Request bodies for membership card operations. Money in paise. */
public final class MembershipCardRequests {

    private MembershipCardRequests() {}

    /** Sell a new card. request_id (client-generated UUID) makes retries / double-clicks safe. */
    @Data
    public static class Issue {
        @JsonProperty("customer_name")
        private String customerName;
        @JsonProperty("customer_phone")
        private String customerPhone;
        @JsonProperty("plan_id")
        private String planId;
        /** cash | upi | card */
        @JsonProperty("payment_mode")
        private String paymentMode;
        /** First day the card can be used; defaults to today, may not be before today. */
        @JsonProperty("start_date")
        private LocalDate startDate;
        @JsonProperty("request_id")
        private String requestId;
        private String note;
    }

    /** Load a new period onto an existing card. The remaining balance is forfeited (no carry-forward). */
    @Data
    public static class Recharge {
        @JsonProperty("plan_id")
        private String planId;
        @JsonProperty("payment_mode")
        private String paymentMode;
        @JsonProperty("start_date")
        private LocalDate startDate;
        @JsonProperty("request_id")
        private String requestId;
        /** Balance the operator saw and agreed to forfeit; rejected with 409 if the card changed since. */
        @JsonProperty("expected_forfeit_paise")
        private Long expectedForfeitPaise;
        private String note;
    }

    /** Admin manual credit / debit. */
    @Data
    public static class Adjust {
        /** CREDIT | DEBIT */
        private String direction;
        @JsonProperty("paid_paise")
        private Long paidPaise;
        @JsonProperty("bonus_paise")
        private Long bonusPaise;
        private String reason;
    }

    /** Admin: move the expiry date later. */
    @Data
    public static class Extend {
        @JsonProperty("new_expiry_date")
        private LocalDate newExpiryDate;
        private String reason;
    }

    /** Admin: SUSPEND | REACTIVATE | CLOSE. */
    @Data
    public static class StatusChange {
        private String action;
        private String reason;
    }
}
