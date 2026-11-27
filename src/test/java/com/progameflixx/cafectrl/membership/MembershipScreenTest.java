package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipCardView;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.service.MembershipCardService;
import com.progameflixx.cafectrl.service.MembershipCardService.CardFilter;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Filters and summary figures for the Memberships screen. */
@SpringBootTest
@Import(TestClockConfig.class)
class MembershipScreenTest {

    static final LocalDate DAY_1 = LocalDate.of(2026, 12, 1);

    @Autowired MembershipCardService cards;
    @Autowired MembershipPlanService plans;
    @Autowired TestClockConfig.MutableClock clock;

    User admin;
    MembershipPlan shortPlan; // 5 days, ₹100 + ₹20 bonus
    MembershipPlan longPlan;  // 90 days, ₹1000 + ₹100 bonus
    MembershipCard aShort, bLong, cLowLong, dScheduled, eSuspended, fExpired, gClosed;

    @BeforeEach
    void setUp() {
        clock.setDate(DAY_1);
        admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setCafeId("cafe-" + UUID.randomUUID());
        admin.setName("Owner");
        admin.setRole("CAFE_ADMIN");
        shortPlan = plans.create(admin, plan("Short", 10_000L, 2_000L, 5));
        longPlan = plans.create(admin, plan("Long", 100_000L, 10_000L, 90));

        fExpired = issue("Farah", "9000000006", shortPlan, null);   // will lapse
        clock.setDate(DAY_1.plusDays(10));
        aShort = issue("Asha", "9000000001", shortPlan, null);      // expires in 5 days (counting today)
        bLong = issue("Bala", "9000000002", longPlan, null);
        cLowLong = issue("Chitra", "9000000002", longPlan, null);   // same phone as Bala
        debit(cLowLong, 100_000L, 5_000L);                          // leaves ₹50 bonus: low balance
        dScheduled = issue("Dev", "9000000004", longPlan, DAY_1.plusDays(20));
        eSuspended = issue("Esha", "9000000005", longPlan, null);
        cards.changeStatus(admin, eSuspended.getId(), status("SUSPEND"));
        gClosed = issue("Gopal", "9000000007", longPlan, null);
        cards.changeStatus(admin, gClosed.getId(), status("CLOSE"));
    }

    static MembershipPlanRequest plan(String name, long load, long bonus, int days) {
        MembershipPlanRequest r = new MembershipPlanRequest();
        r.setName(name);
        r.setLoadAmountPaise(load);
        r.setBonusType("FIXED");
        r.setBonusValue(bonus);
        r.setValidityDays(days);
        return r;
    }

    MembershipCard issue(String name, String phone, MembershipPlan plan, LocalDate start) {
        MembershipCardRequests.Issue r = new MembershipCardRequests.Issue();
        r.setCustomerName(name);
        r.setCustomerPhone(phone);
        r.setPlanId(plan.getId());
        r.setPaymentMode("cash");
        r.setStartDate(start);
        return cards.issue(admin, r);
    }

    void debit(MembershipCard c, long paid, long bonus) {
        MembershipCardRequests.Adjust a = new MembershipCardRequests.Adjust();
        a.setDirection("DEBIT");
        a.setPaidPaise(paid);
        a.setBonusPaise(bonus);
        a.setReason("test usage");
        cards.adjust(admin, c.getId(), a);
    }

    static MembershipCardRequests.StatusChange status(String action) {
        MembershipCardRequests.StatusChange s = new MembershipCardRequests.StatusChange();
        s.setAction(action);
        s.setReason("test");
        return s;
    }

    Set<String> names(List<MembershipCardView> vs) {
        return vs.stream().map(MembershipCardView::customerName).collect(Collectors.toSet());
    }

    List<MembershipCardView> list(String status, String planId, Integer expiring, boolean low) {
        return cards.list(admin.getCafeId(), new CardFilter(null, status, planId, expiring, low));
    }

    @Test
    void statusFilterUsesEffectiveStatus() {
        assertEquals(Set.of("Asha", "Bala", "Chitra"), names(list("ACTIVE", null, null, false)));
        assertEquals(Set.of("Dev"), names(list("scheduled", null, null, false)));
        assertEquals(Set.of("Esha"), names(list("SUSPENDED", null, null, false)));
        assertEquals(Set.of("Farah"), names(list("EXPIRED", null, null, false))); // lapsed, job not run yet
        assertEquals(Set.of("Gopal"), names(list("CLOSED", null, null, false)));
        assertEquals(7, list("ALL", null, null, false).size());
        assertThrows(ResponseStatusException.class, () -> list("BOGUS", null, null, false));
    }

    @Test
    void planExpiringAndLowBalanceFilters() {
        assertEquals(Set.of("Asha", "Farah"), names(list("ALL", shortPlan.getId(), null, false)));
        assertEquals(Set.of("Asha"), names(list("ALL", null, 7, false)));
        assertEquals(Set.of("Chitra"), names(list("ALL", null, null, true)));
        assertEquals(Set.of("Bala", "Chitra"), names(cards.list(admin.getCafeId(),
                new CardFilter("9000000002", "ACTIVE", longPlan.getId(), null, false))));
    }

    @Test
    void summaryCountsActiveMembersAndSplitsLiability() {
        MembershipCardService.Summary s = cards.summary(admin.getCafeId());

        assertEquals(3, s.activeCards());
        assertEquals(2, s.activeCustomers()); // Bala and Chitra share a phone
        assertEquals(1, s.scheduledCards());
        assertEquals(1, s.suspendedCards());
        assertEquals(1, s.expiring7d());
        assertEquals(1, s.lowBalance());
        // Owed = active (Asha 100+20, Bala 1000+100, Chitra 0+50) + scheduled Dev 1000+100 + suspended Esha 1000+100
        assertEquals(10_000L + 100_000L + 0L + 100_000L + 100_000L, s.outstandingPaidPaise());
        assertEquals(2_000L + 10_000L + 5_000L + 10_000L + 10_000L, s.outstandingBonusPaise());
        assertEquals(s.outstandingPaidPaise() + s.outstandingBonusPaise(), s.outstandingTotalPaise());
        assertEquals(12_000L, s.pendingForfeitPaise()); // Farah, until the nightly job runs

        cards.expireDueCards();
        assertEquals(0L, cards.summary(admin.getCafeId()).pendingForfeitPaise());
    }
}
