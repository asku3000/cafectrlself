package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.UserRepository;
import com.progameflixx.cafectrl.service.MembershipCardService;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import com.progameflixx.cafectrl.service.MembershipReportService;
import com.progameflixx.cafectrl.service.MembershipReportService.Report;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Reports from the ledger. Scenario (one cafe per test run of setUp):
 *  Day 1: sell Silver (₹1000 +10%) by cash, Silver by UPI, Gold (₹2000 +₹500) by card, Short (₹100, 2 days) by cash
 *  Day 2: card A pays a ₹300 bill; card B's ₹200 bill is paid then reversed; admin credits ₹50 bonus to C
 *  Day 3: card B recharged to Gold by cash (₹1100 forfeited); card C closed (₹2550 forfeited)
 *  Day 4: Short card expires (₹100 forfeited)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class MembershipReportTest {

    static final String CAFE = "cafe-report-test";
    static final LocalDate DAY_1 = LocalDate.of(2027, 1, 4);

    @Autowired MembershipReportService reports;
    @Autowired MembershipCardService cards;
    @Autowired MembershipPlanService plans;
    @Autowired UserRepository users;
    @Autowired TestClockConfig.MutableClock clock;
    @Autowired MockMvc mvc;

    static String cafeId;
    static boolean seeded;
    static MembershipPlan silver, gold;

    @BeforeEach
    void seedOnce() {
        if (seeded) return;
        seeded = true;
        cafeId = CAFE + "-" + UUID.randomUUID();
        User admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setCafeId(cafeId);
        admin.setName("Owner");
        admin.setRole("CAFE_ADMIN");
        saveUser("reportadmin@test.local", "CAFE_ADMIN", List.of());
        saveUser("reportop@test.local", "OPERATOR", List.of("sessions", "memberships"));

        clock.setDate(DAY_1);
        silver = plans.create(admin, plan("Silver", 100_000L, "PERCENT", 1_000L, 30));
        gold = plans.create(admin, plan("Gold", 200_000L, "FIXED", 50_000L, 60));
        MembershipPlan shortPlan = plans.create(admin, plan("Short", 10_000L, "FIXED", 0L, 2));
        MembershipCard a = issue(admin, silver, "cash");
        MembershipCard b = issue(admin, silver, "upi");
        MembershipCard c = issue(admin, gold, "card");
        issue(admin, shortPlan, "cash");

        clock.setDate(DAY_1.plusDays(1));
        cards.debitForBill(admin, cafeId, a.getId(), "session-a", 1, 30_000L, "A");
        cards.debitForBill(admin, cafeId, b.getId(), "session-b", 1, 20_000L, "B");
        cards.reverseBillDebits(admin, cafeId, "session-b", "voided");
        MembershipCardRequests.Adjust credit = new MembershipCardRequests.Adjust();
        credit.setDirection("CREDIT");
        credit.setBonusPaise(5_000L);
        credit.setReason("goodwill");
        cards.adjust(admin, c.getId(), credit);

        clock.setDate(DAY_1.plusDays(2));
        MembershipCardRequests.Recharge r = new MembershipCardRequests.Recharge();
        r.setPlanId(gold.getId());
        r.setPaymentMode("cash");
        cards.recharge(admin, b.getId(), r);
        MembershipCardRequests.StatusChange close = new MembershipCardRequests.StatusChange();
        close.setAction("CLOSE");
        close.setReason("moved away");
        cards.changeStatus(admin, c.getId(), close);

        clock.setDate(DAY_1.plusDays(3));
        cards.expireDueCards();
    }

    void saveUser(String email, String role, List<String> perms) {
        User u = users.findByEmail(email).orElseGet(User::new);
        u.setEmail(email);
        u.setName(email);
        u.setRole(role);
        u.setCafeId(cafeId);
        u.setActive(true);
        u.setPermissions(new ArrayList<>(perms));
        users.save(u);
    }

    static MembershipPlanRequest plan(String name, long load, String type, long bonus, int days) {
        MembershipPlanRequest p = new MembershipPlanRequest();
        p.setName(name);
        p.setLoadAmountPaise(load);
        p.setBonusType(type);
        p.setBonusValue(bonus);
        p.setValidityDays(days);
        return p;
    }

    MembershipCard issue(User admin, MembershipPlan plan, String mode) {
        MembershipCardRequests.Issue r = new MembershipCardRequests.Issue();
        r.setCustomerName("Customer " + mode);
        r.setCustomerPhone("9444444444");
        r.setPlanId(plan.getId());
        r.setPaymentMode(mode);
        return cards.issue(admin, r);
    }

    @Test
    void wholePeriodSalesConsumptionAndForfeits() {
        Report r = reports.report(cafeId, DAY_1, DAY_1.plusDays(3));

        assertEquals(4, r.newCards());
        assertEquals(1, r.recharges());
        assertEquals(100_000L + 100_000L + 200_000L + 10_000L + 200_000L, r.collectedPaise());
        assertEquals(Map.of("cash", 310_000L, "upi", 100_000L, "card", 200_000L), r.collectedByMode());
        assertEquals(10_000L + 10_000L + 50_000L + 50_000L, r.bonusGivenPaise());

        MembershipReportService.PlanSales goldSales = r.byPlan().stream().filter(p -> p.planName().equals("Gold")).findFirst().orElseThrow();
        assertEquals(1, goldSales.newCards());
        assertEquals(1, goldSales.recharges());
        assertEquals(400_000L, goldSales.collectedPaise());
        assertEquals(100_000L, goldSales.bonusGivenPaise());
        assertEquals(Map.of("card", 200_000L, "cash", 200_000L), goldSales.byMode());
        assertEquals("Gold", r.byPlan().getFirst().planName()); // sorted by money collected

        // Only card A's ₹300 bill counts; B's bill was reversed
        assertEquals(30_000L, r.consumed().totalPaise());
        assertEquals(30_000L, r.consumed().paidPaise());
        assertEquals(1, r.billsPaid());

        assertEquals(10_000L, r.expiredForfeit().totalPaise());                         // Short card
        assertEquals(110_000L, r.rechargeForfeit().totalPaise());                       // B: 1000 + 100
        assertEquals(100_000L, r.rechargeForfeit().paidPaise());
        assertEquals(255_000L, r.closeForfeit().totalPaise());                          // C: 2000 + 500 + 50
        assertEquals(55_000L, r.closeForfeit().bonusPaise());
        assertEquals(10_000L + 110_000L + 255_000L, r.forfeitedTotal().totalPaise());
        assertEquals(5_000L, r.adjustmentsNet().bonusPaise());
    }

    @Test
    void rangeOnlyCountsEntriesInsideIt() {
        Report day1 = reports.report(cafeId, DAY_1, DAY_1);
        assertEquals(4, day1.newCards());
        assertEquals(0, day1.recharges());
        assertEquals(0L, day1.consumed().totalPaise());

        Report day3 = reports.report(cafeId, DAY_1.plusDays(2), DAY_1.plusDays(2));
        assertEquals(0, day3.newCards());
        assertEquals(1, day3.recharges());
        assertEquals(200_000L, day3.collectedPaise());
        assertEquals(0L, day3.expiredForfeit().totalPaise());
    }

    @Test
    void invalidRangesAreRejected() {
        assertThrows(ResponseStatusException.class, () -> reports.report(cafeId, DAY_1, DAY_1.minusDays(1)));
        assertThrows(ResponseStatusException.class, () -> reports.report(cafeId, DAY_1, DAY_1.plusDays(400)));
    }

    @Test
    @WithMockUser(username = "reportadmin@test.local", roles = "CAFE_ADMIN")
    void dailyReportCountsCardSalesAsCollectedByTheirMode() throws Exception {
        mvc.perform(get("/api/reports/daily").param("date", DAY_1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4100.0))
                .andExpect(jsonPath("$.membership_sales_total").value(4100.0))
                .andExpect(jsonPath("$.membership_sales.length()").value(4))
                .andExpect(jsonPath("$.by_mode.cash").value(1100.0))
                .andExpect(jsonPath("$.by_mode.upi").value(1000.0))
                .andExpect(jsonPath("$.by_mode.card").value(2000.0));

        mvc.perform(get("/api/reports/monthly").param("year", "2027").param("month", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.membership_sales_total").value(6100.0))
                .andExpect(jsonPath("$.membership_cards_sold").value(4))
                .andExpect(jsonPath("$.membership_recharges").value(1));

        mvc.perform(get("/api/membership/reports").param("from", DAY_1.toString()).param("to", DAY_1.plusDays(3).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected_paise").value(610000))
                .andExpect(jsonPath("$.forfeited_total.total_paise").value(375000));
    }

    @Test
    @WithMockUser(username = "reportop@test.local", roles = "OPERATOR")
    void operatorWithoutReportsPermissionIsRefused() throws Exception {
        mvc.perform(get("/api/membership/reports").param("from", DAY_1.toString()).param("to", DAY_1.toString()))
                .andExpect(status().isForbidden());
    }
}
