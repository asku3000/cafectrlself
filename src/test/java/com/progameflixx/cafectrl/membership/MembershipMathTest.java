package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.entity.MembershipMath;
import com.progameflixx.cafectrl.entity.MembershipPlan.BonusType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MembershipMathTest {

    @Test
    void fixedBonusIsTheConfiguredPaiseRegardlessOfLoad() {
        assertEquals(20_000L, MembershipMath.bonusFor(100_000L, BonusType.FIXED, 20_000L)); // ₹1000 load, ₹200 bonus
        assertEquals(20_000L, MembershipMath.bonusFor(50_000L, BonusType.FIXED, 20_000L));
    }

    @Test
    void percentBonusUsesBasisPoints() {
        assertEquals(10_000L, MembershipMath.bonusFor(100_000L, BonusType.PERCENT, 1_000L)); // 10% of ₹1000 = ₹100
        assertEquals(37_500L, MembershipMath.bonusFor(150_000L, BonusType.PERCENT, 2_500L)); // 25% of ₹1500 = ₹375
        assertEquals(100_000L, MembershipMath.bonusFor(100_000L, BonusType.PERCENT, 10_000L)); // 100%
    }

    @Test
    void percentBonusRoundsHalfUpToWholePaise() {
        // 12.5% of ₹9.99 (999 paise) = 124.875 paise -> 125
        assertEquals(125L, MembershipMath.bonusFor(999L, BonusType.PERCENT, 1_250L));
        // 33.33% of 100 paise = 33.33 -> 33
        assertEquals(33L, MembershipMath.bonusFor(100L, BonusType.PERCENT, 3_333L));
        // 50% of 1 paisa = 0.5 -> 1
        assertEquals(1L, MembershipMath.bonusFor(1L, BonusType.PERCENT, 5_000L));
    }

    @Test
    void zeroOrMissingBonusIsZero() {
        assertEquals(0L, MembershipMath.bonusFor(100_000L, BonusType.PERCENT, 0L));
        assertEquals(0L, MembershipMath.bonusFor(100_000L, BonusType.FIXED, null));
        assertEquals(0L, MembershipMath.bonusFor(null, BonusType.FIXED, 5_000L));
    }

    @Test
    void rupeeDoublesConvertToExactPaise() {
        assertEquals(30L, MembershipMath.rupeesToPaise(0.1 + 0.2)); // 0.30000000000000004
        assertEquals(19_999L, MembershipMath.rupeesToPaise(199.99));
        assertEquals(-5_050L, MembershipMath.rupeesToPaise(-50.5));
        assertEquals(0L, MembershipMath.rupeesToPaise(null));
        assertEquals(199.99, MembershipMath.paiseToRupees(19_999L));
    }
}
