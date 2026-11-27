package com.progameflixx.cafectrl.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.progameflixx.cafectrl.entity.MembershipCard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** What the UI sees for a card: snapshot values plus status and flags computed for "today". */
public record MembershipCardView(
        String id,
        @JsonProperty("card_number") String cardNumber,
        @JsonProperty("customer_name") String customerName,
        @JsonProperty("customer_phone") String customerPhone,
        @JsonProperty("plan_id") String planId,
        @JsonProperty("plan_name") String planName,
        @JsonProperty("plan_color") String planColor,
        @JsonProperty("plan_badge") String planBadge,
        @JsonProperty("plan_load_amount_paise") long planLoadAmountPaise,
        @JsonProperty("plan_bonus_type") String planBonusType,
        @JsonProperty("plan_bonus_value") long planBonusValue,
        @JsonProperty("plan_bonus_amount_paise") long planBonusAmountPaise,
        @JsonProperty("plan_validity_days") int planValidityDays,
        @JsonProperty("paid_balance_paise") long paidBalancePaise,
        @JsonProperty("bonus_balance_paise") long bonusBalancePaise,
        @JsonProperty("total_balance_paise") long totalBalancePaise,
        /** Effective status: ACTIVE | SCHEDULED | EXPIRED | SUSPENDED | CLOSED */
        String status,
        @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("expiry_date") LocalDate expiryDate,
        /** Usable days left including today (0 once expired); for SCHEDULED, the full period length. */
        @JsonProperty("days_remaining") long daysRemaining,
        /** True only when the card can pay a bill right now. */
        boolean usable,
        @JsonProperty("low_balance") boolean lowBalance,
        /** Balance on an expired card that the nightly job has not forfeited yet. */
        @JsonProperty("pending_forfeit_paise") long pendingForfeitPaise,
        @JsonProperty("issued_at") LocalDateTime issuedAt,
        @JsonProperty("period_purchased_at") LocalDateTime periodPurchasedAt,
        @JsonProperty("last_used_at") LocalDateTime lastUsedAt,
        @JsonProperty("issued_by_name") String issuedByName
) {
    public static MembershipCardView of(MembershipCard c, LocalDate today, long lowBalanceThresholdPaise) {
        MembershipCard.Status eff = c.effectiveStatus(today);
        long total = c.totalBalancePaise();
        long days = switch (eff) {
            case ACTIVE, SUSPENDED -> ChronoUnit.DAYS.between(today, c.getExpiryDate()) + 1;
            case SCHEDULED -> ChronoUnit.DAYS.between(c.getStartDate(), c.getExpiryDate()) + 1;
            default -> 0;
        };
        boolean pendingForfeit = eff == MembershipCard.Status.EXPIRED && c.getStatus() != MembershipCard.Status.EXPIRED;
        return new MembershipCardView(
                c.getId(), c.getCardNumber(), c.getCustomerName(), c.getCustomerPhone(),
                c.getPlanId(), c.getPlanName(), c.getPlanColor(), c.getPlanBadge(),
                c.getPlanLoadAmountPaise(), c.getPlanBonusType().name(), c.getPlanBonusValue(),
                c.getPlanBonusAmountPaise(), c.getPlanValidityDays(),
                c.getPaidBalancePaise(), c.getBonusBalancePaise(), total,
                eff.name(), c.getStartDate(), c.getExpiryDate(), Math.max(days, 0),
                eff == MembershipCard.Status.ACTIVE,
                eff == MembershipCard.Status.ACTIVE && total <= lowBalanceThresholdPaise,
                pendingForfeit ? total : 0L,
                c.getIssuedAt(), c.getPeriodPurchasedAt(), c.getLastUsedAt(), c.getIssuedByName());
    }
}
