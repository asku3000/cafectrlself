package com.progameflixx.cafectrl.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure money helpers for the membership feature. All amounts are integer paise; no floating point
 * is used for stored values. The only double conversion is at the boundary with the existing bill
 * engine (which still works in rupees as Double) — see {@link #rupeesToPaise(Double)}.
 */
public final class MembershipMath {

    /** 100% expressed in basis points. */
    public static final long FULL_PERCENT_BP = 10_000L;

    private MembershipMath() {}

    /** Bonus for a load: FIXED returns bonusValue paise; PERCENT returns load * bp / 10000, rounded half-up. */
    public static long bonusFor(Long loadPaise, MembershipPlan.BonusType type, Long bonusValue) {
        if (loadPaise == null || type == null || bonusValue == null || bonusValue <= 0) return 0L;
        if (type == MembershipPlan.BonusType.FIXED) return bonusValue;
        return BigDecimal.valueOf(loadPaise)
                .multiply(BigDecimal.valueOf(bonusValue))
                .divide(BigDecimal.valueOf(FULL_PERCENT_BP), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Converts a rupee Double (as produced by BillingService) to paise, rounding half-up on the decimal string. */
    public static long rupeesToPaise(Double rupees) {
        if (rupees == null) return 0L;
        return new BigDecimal(Double.toString(rupees))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Converts paise to a rupee Double for the existing Double-based bill/payment fields. */
    public static double paiseToRupees(long paise) {
        return BigDecimal.valueOf(paise).movePointLeft(2).doubleValue();
    }
}
