package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry;
import com.progameflixx.cafectrl.entity.MembershipPaymentPart;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.UserRepository;
import com.progameflixx.cafectrl.service.MembershipCardService;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import com.progameflixx.cafectrl.service.MembershipReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Card sales paid with several modes, and the admin "change start date" action. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class MembershipSplitPaymentAndStartDateTest {

    static final LocalDate DAY_1 = LocalDate.of(2027, 2, 1);

    @Autowired MembershipCardService cards;
    @Autowired MembershipPlanService plans;
    @Autowired MembershipReportService reports;
    @Autowired UserRepository users;
    @Autowired TestClockConfig.MutableClock clock;
    @Autowired MockMvc mvc;

    User admin;
    MembershipPlan silver; // ₹1000 + 10%, 30 days

    @BeforeEach
    void setUp() {
        clock.setDate(DAY_1);
        admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setCafeId("cafe-" + UUID.randomUUID());
        admin.setName("Owner");
        admin.setRole("CAFE_ADMIN");
        MembershipPlanRequest p = new MembershipPlanRequest();
        p.setName("Silver");
        p.setLoadAmountPaise(100_000L);
        p.setBonusType("PERCENT");
        p.setBonusValue(1_000L);
        p.setValidityDays(30);
        silver = plans.create(admin, p);
    }

    static MembershipPaymentPart part(String mode, long paise) {
        return new MembershipPaymentPart(mode, paise);
    }

    MembershipCardRequests.Issue sale(List<MembershipPaymentPart> payments) {
        MembershipCardRequests.Issue r = new MembershipCardRequests.Issue();
        r.setCustomerName("Rhea");
        r.setCustomerPhone("9555555555");
        r.setPlanId(silver.getId());
        r.setPayments(payments);
        return r;
    }

    MembershipLedgerEntry loadEntry(MembershipCard c) {
        return cards.ledger(admin.getCafeId(), c.getId()).getLast();
    }

    // ---------------- split payment ----------------

    @Test
    void saleCanBeSplitAcrossModesAndStaysOneLedgerEntry() {
        MembershipCard c = cards.issue(admin, sale(List.of(part("UPI", 60_000L), part("cash", 40_000L))));

        assertEquals(100_000L, c.getPaidBalancePaise());
        assertEquals(10_000L, c.getBonusBalancePaise());
        MembershipLedgerEntry load = loadEntry(c);
        assertEquals(MembershipLedgerEntry.Type.LOAD, load.getEntryType());
        assertEquals("split", load.getPaymentMode());
        assertEquals(List.of(part("upi", 60_000L), part("cash", 40_000L)), load.getPaymentBreakdown());
        assertEquals(2, cards.ledger(admin.getCafeId(), c.getId()).size()); // LOAD + BONUS only
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void linesOfTheSameModeAreMergedAndSingleModeShortcutStillWorks() {
        MembershipCard merged = cards.issue(admin, sale(List.of(part("cash", 30_000L), part("cash", 70_000L))));
        assertEquals("cash", loadEntry(merged).getPaymentMode());
        assertEquals(List.of(part("cash", 100_000L)), loadEntry(merged).getPaymentBreakdown());

        MembershipCardRequests.Issue old = sale(null);
        old.setPaymentMode("card");
        MembershipCard single = cards.issue(admin, old);
        assertEquals("card", loadEntry(single).getPaymentMode());
        assertEquals(List.of(part("card", 100_000L)), loadEntry(single).getPaymentBreakdown());
    }

    @Test
    void paymentsMustAddUpExactlyAndUseRealModes() {
        ResponseStatusException under = assertThrows(ResponseStatusException.class,
                () -> cards.issue(admin, sale(List.of(part("upi", 60_000L), part("cash", 30_000L)))));
        assertEquals("Payments add up to ₹900.00 but the plan price is ₹1000.00", under.getReason());
        assertThrows(ResponseStatusException.class,
                () -> cards.issue(admin, sale(List.of(part("upi", 60_000L), part("cash", 50_000L)))));
        assertThrows(ResponseStatusException.class,
                () -> cards.issue(admin, sale(List.of(part("upi", 100_000L), part("pending", 0L)))));
        assertThrows(ResponseStatusException.class,
                () -> cards.issue(admin, sale(List.of(part("pending", 50_000L), part("cash", 50_000L)))));
        assertThrows(ResponseStatusException.class,
                () -> cards.issue(admin, sale(List.of(part("cash", -1L), part("upi", 100_001L)))));
        assertTrue(cards.list(admin.getCafeId(), (String) null).isEmpty()); // nothing was sold
    }

    @Test
    void rechargeCanBeSplitToo() {
        MembershipCardRequests.Issue first = sale(null);
        first.setPaymentMode("cash");
        MembershipCard c = cards.issue(admin, first);
        MembershipCardRequests.Recharge r = new MembershipCardRequests.Recharge();
        r.setPlanId(silver.getId());
        r.setPayments(List.of(part("card", 25_000L), part("upi", 75_000L)));
        cards.recharge(admin, c.getId(), r);
        MembershipLedgerEntry recharge = cards.ledger(admin.getCafeId(), c.getId()).get(1);
        assertEquals(MembershipLedgerEntry.Type.RECHARGE, recharge.getEntryType());
        assertEquals("split", recharge.getPaymentMode());
    }

    @Test
    void reportCountsOneSaleButCreditsEachMode() {
        cards.issue(admin, sale(List.of(part("upi", 60_000L), part("cash", 40_000L))));
        MembershipReportService.Report r = reports.report(admin.getCafeId(), DAY_1, DAY_1);
        assertEquals(1, r.newCards());
        assertEquals(100_000L, r.collectedPaise());
        assertEquals(Map.of("upi", 60_000L, "cash", 40_000L), r.collectedByMode());
        assertEquals(Map.of("upi", 60_000L, "cash", 40_000L), r.byPlan().getFirst().byMode());

        List<MembershipReportService.Sale> day = reports.salesOn(admin.getCafeId(), DAY_1);
        assertEquals(1, day.size());
        assertEquals(2, day.getFirst().payments().size());
    }

    @Test
    @WithMockUser(username = "splitadmin@test.local", roles = "CAFE_ADMIN")
    void dailyReportPutsEachPartUnderItsMode() throws Exception {
        User u = users.findByEmail("splitadmin@test.local").orElseGet(User::new);
        u.setEmail("splitadmin@test.local");
        u.setName("Split Admin");
        u.setRole("CAFE_ADMIN");
        u.setCafeId(admin.getCafeId());
        u.setActive(true);
        u.setPermissions(new ArrayList<>());
        users.save(u);

        mvc.perform(post("/api/membership/cards").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customer_name":"Rhea","customer_phone":"9555555555","plan_id":"%s",
                         "payments":[{"mode":"upi","amount_paise":60000},{"mode":"cash","amount_paise":40000}]}
                        """.formatted(silver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_balance_paise").value(100000));

        mvc.perform(get("/api/reports/daily").param("date", DAY_1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1000.0))
                .andExpect(jsonPath("$.by_mode.upi").value(600.0))
                .andExpect(jsonPath("$.by_mode.cash").value(400.0))
                .andExpect(jsonPath("$.membership_sales[0].payments.length()").value(2));
    }

    // ---------------- change start date ----------------

    MembershipCard soldToday() {
        MembershipCardRequests.Issue r = sale(null);
        r.setPaymentMode("cash");
        return cards.issue(admin, r);
    }

    MembershipCardRequests.ChangeStart start(LocalDate d, String reason) {
        MembershipCardRequests.ChangeStart c = new MembershipCardRequests.ChangeStart();
        c.setNewStartDate(d);
        c.setReason(reason);
        return c;
    }

    @Test
    void movingStartLaterShiftsExpiryAndMakesCardScheduled() {
        MembershipCard c = soldToday(); // valid DAY_1 .. DAY_1+29
        MembershipCard moved = cards.changeStartDate(admin, c.getId(), start(DAY_1.plusDays(5), "Customer starts after holidays"));

        assertEquals(DAY_1.plusDays(5), moved.getStartDate());
        assertEquals(DAY_1.plusDays(34), moved.getExpiryDate());
        assertEquals("SCHEDULED", cards.view(admin.getCafeId(), c.getId()).status());
        MembershipLedgerEntry e = cards.ledger(admin.getCafeId(), c.getId()).getFirst();
        assertEquals(MembershipLedgerEntry.Type.START_DATE_CHANGED, e.getEntryType());
        assertEquals(0L, e.getAmountPaise());
        assertTrue(e.getNote().contains("Customer starts after holidays"));
        assertEquals(110_000L, cards.getCard(admin.getCafeId(), c.getId()).totalBalancePaise());
    }

    @Test
    void anEarlierExtensionIsKeptWhenStartMoves() {
        MembershipCard c = soldToday();
        MembershipCardRequests.Extend ext = new MembershipCardRequests.Extend();
        ext.setNewExpiryDate(DAY_1.plusDays(39)); // +10 days
        ext.setReason("goodwill");
        cards.extendExpiry(admin, c.getId(), ext);

        MembershipCard moved = cards.changeStartDate(admin, c.getId(), start(DAY_1.plusDays(2), "late start"));
        assertEquals(DAY_1.plusDays(41), moved.getExpiryDate()); // still 40 days long
    }

    @Test
    void startCanBeMovedBackButNotBeforeThePurchaseDay() {
        MembershipCardRequests.Issue r = sale(null);
        r.setPaymentMode("cash");
        r.setStartDate(DAY_1.plusDays(10));
        MembershipCard c = cards.issue(admin, r);

        MembershipCard back = cards.changeStartDate(admin, c.getId(), start(DAY_1, "start now"));
        assertEquals(DAY_1, back.getStartDate());
        assertEquals(DAY_1.plusDays(29), back.getExpiryDate());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> cards.changeStartDate(admin, c.getId(), start(DAY_1.minusDays(1), "x")));
        assertTrue(ex.getReason().contains("before the day the card was bought"));
    }

    @Test
    void refusedAfterUseOrWhenTheCardWouldAlreadyBeExpired() {
        MembershipCard used = soldToday();
        cards.debitForBill(admin, admin.getCafeId(), used.getId(), "s-1", 1, 5_000L, "Rhea");
        ResponseStatusException afterUse = assertThrows(ResponseStatusException.class,
                () -> cards.changeStartDate(admin, used.getId(), start(DAY_1.plusDays(3), "x")));
        assertTrue(afterUse.getReason().contains("already used"));

        MembershipCard c = soldToday();
        clock.setDate(DAY_1.plusDays(20));
        // still allowed: moving later keeps it valid
        cards.changeStartDate(admin, c.getId(), start(DAY_1.plusDays(25), "re-plan"));
        // moving back to day 1 would end it on day 30, still >= today (day 20): allowed
        cards.changeStartDate(admin, c.getId(), start(DAY_1, "undo"));

        MembershipCard other = soldToday(); // bought on day 20, valid day 20..49
        clock.setDate(DAY_1.plusDays(45));
        // start can't go before purchase day, so expiry can only move later here; check the "already expired" guard
        // directly with a card whose start is moved back to its purchase day after time passed
        MembershipCardRequests.Issue late = sale(null);
        late.setPaymentMode("cash");
        late.setStartDate(DAY_1.plusDays(60));
        MembershipCard scheduled = cards.issue(admin, late); // bought day 45, starts day 60, valid till day 89
        clock.setDate(DAY_1.plusDays(80));
        ResponseStatusException expired = assertThrows(ResponseStatusException.class,
                () -> cards.changeStartDate(admin, scheduled.getId(), start(DAY_1.plusDays(45), "x"))); // would end day 74
        assertTrue(expired.getReason().contains("would already have expired"));
        assertNotNull(other);
    }

    @Test
    void refusedOnClosedOrExpiredCardsAndNeedsAReason() {
        MembershipCard c = soldToday();
        assertThrows(ResponseStatusException.class, () -> cards.changeStartDate(admin, c.getId(), start(DAY_1.plusDays(1), " ")));
        assertThrows(ResponseStatusException.class, () -> cards.changeStartDate(admin, c.getId(), start(DAY_1, "same day")));

        clock.setDate(DAY_1.plusDays(31));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> cards.changeStartDate(admin, c.getId(), start(DAY_1.plusDays(5), "x")));
        assertTrue(ex.getReason().contains("expired"));
    }

    @Test
    @WithMockUser(username = "splitop@test.local", roles = "OPERATOR")
    void operatorCannotChangeStartDate() throws Exception {
        User u = users.findByEmail("splitop@test.local").orElseGet(User::new);
        u.setEmail("splitop@test.local");
        u.setName("Op");
        u.setRole("OPERATOR");
        u.setCafeId(admin.getCafeId());
        u.setActive(true);
        u.setPermissions(new ArrayList<>(List.of("memberships")));
        users.save(u);
        MembershipCard c = soldToday();
        mvc.perform(post("/api/membership/cards/" + c.getId() + "/start-date").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"new_start_date\":\"" + DAY_1.plusDays(2) + "\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
