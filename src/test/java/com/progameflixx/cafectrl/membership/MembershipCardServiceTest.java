package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipCardView;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry.Type;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.service.MembershipCardService;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Card lifecycle and ledger. Not @Transactional: the expiry job and the concurrency test use their own
 * transactions, as in production. Each test uses its own cafe id so data never collides.
 */
@SpringBootTest
@Import(TestClockConfig.class)
class MembershipCardServiceTest {

    static final LocalDate DAY_1 = LocalDate.of(2026, 12, 1);

    @Autowired MembershipCardService cards;
    @Autowired MembershipPlanService plans;
    @Autowired TestClockConfig.MutableClock clock;

    User admin;
    MembershipPlan silver; // pay 1000, +10% = 1100, 30 days
    MembershipPlan gold;   // pay 2000, +500 fixed = 2500, 60 days

    @BeforeEach
    void setUp() {
        clock.setDate(DAY_1);
        admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setCafeId("cafe-" + UUID.randomUUID());
        admin.setName("Owner");
        admin.setRole("CAFE_ADMIN");
        silver = plans.create(admin, plan("Silver", 100_000L, "PERCENT", 1_000L, 30));
        gold = plans.create(admin, plan("Gold", 200_000L, "FIXED", 50_000L, 60));
    }

    static MembershipPlanRequest plan(String name, long load, String type, long bonus, int days) {
        MembershipPlanRequest r = new MembershipPlanRequest();
        r.setName(name);
        r.setLoadAmountPaise(load);
        r.setBonusType(type);
        r.setBonusValue(bonus);
        r.setValidityDays(days);
        return r;
    }

    MembershipCardRequests.Issue issueReq(MembershipPlan plan) {
        MembershipCardRequests.Issue r = new MembershipCardRequests.Issue();
        r.setCustomerName("Ravi Kumar");
        r.setCustomerPhone("98765 43210");
        r.setPlanId(plan.getId());
        r.setPaymentMode("UPI");
        return r;
    }

    MembershipCardRequests.Recharge rechargeReq(MembershipPlan plan) {
        MembershipCardRequests.Recharge r = new MembershipCardRequests.Recharge();
        r.setPlanId(plan.getId());
        r.setPaymentMode("cash");
        return r;
    }

    List<Type> types(MembershipCard c) {
        return cards.ledger(admin.getCafeId(), c.getId()).stream().map(MembershipLedgerEntry::getEntryType).toList().reversed();
    }

    MembershipCard reload(MembershipCard c) {
        return cards.getCard(admin.getCafeId(), c.getId());
    }

    // ---------------- issue ----------------

    @Test
    void issueSnapshotsPlanCreditsPaidAndBonusSeparatelyAndReconciles() {
        MembershipCard c = cards.issue(admin, issueReq(silver));

        assertEquals("MC-000001", c.getCardNumber());
        assertEquals("9876543210", c.getCustomerPhone());
        assertEquals("Silver", c.getPlanName());
        assertEquals(100_000L, c.getPaidBalancePaise());
        assertEquals(10_000L, c.getBonusBalancePaise());
        assertEquals(DAY_1, c.getStartDate());
        assertEquals(DAY_1.plusDays(29), c.getExpiryDate()); // 30 days inclusive
        assertEquals(List.of(Type.LOAD, Type.BONUS), types(c));
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));

        MembershipLedgerEntry load = cards.ledger(admin.getCafeId(), c.getId()).getLast();
        assertEquals("upi", load.getPaymentMode());
        assertEquals("Silver", load.getPlanName());
        assertEquals("Owner", load.getUserName());
    }

    @Test
    void cardNumbersAreSequentialPerCafeAndPhonesMayRepeat() {
        MembershipCard a = cards.issue(admin, issueReq(silver));
        MembershipCard b = cards.issue(admin, issueReq(gold)); // same phone again: allowed
        assertEquals("MC-000001", a.getCardNumber());
        assertEquals("MC-000002", b.getCardNumber());
        assertEquals(2, cards.list(admin.getCafeId(), "98765").size());
        assertEquals(1, cards.list(admin.getCafeId(), "mc-000002").size());
        assertEquals(2, cards.list(admin.getCafeId(), "ravi").size());
    }

    @Test
    void editingPlanAfterSaleDoesNotChangeIssuedCard() {
        MembershipCard c = cards.issue(admin, issueReq(silver));

        MembershipPlanRequest edit = new MembershipPlanRequest();
        edit.setName("Silver Plus");
        edit.setLoadAmountPaise(150_000L);
        edit.setBonusValue(5_000L);
        edit.setValidityDays(365);
        plans.update(admin, silver.getId(), edit);

        MembershipCard after = reload(c);
        assertEquals("Silver", after.getPlanName());
        assertEquals(100_000L, after.getPlanLoadAmountPaise());
        assertEquals(1_000L, after.getPlanBonusValue());
        assertEquals(30, after.getPlanValidityDays());
        assertEquals(DAY_1.plusDays(29), after.getExpiryDate());
        assertEquals(110_000L, after.totalBalancePaise());
    }

    @Test
    void deactivatedPlanCannotBeSoldButExistingCardsKeepWorking() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        plans.setActive(admin, silver.getId(), false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> cards.issue(admin, issueReq(silver)));
        assertTrue(ex.getReason().contains("not on sale"));
        assertTrue(cards.view(admin.getCafeId(), c.getId()).usable());
    }

    @Test
    void futureStartDateIsScheduledAndValidityCountsFromStart() {
        MembershipCardRequests.Issue r = issueReq(silver);
        r.setStartDate(DAY_1.plusDays(10));
        MembershipCard c = cards.issue(admin, r);

        assertEquals(DAY_1.plusDays(39), c.getExpiryDate());
        MembershipCardView v = cards.view(admin.getCafeId(), c.getId());
        assertEquals("SCHEDULED", v.status());
        assertFalse(v.usable());
        assertEquals(30, v.daysRemaining());

        clock.setDate(DAY_1.plusDays(10));
        v = cards.view(admin.getCafeId(), c.getId());
        assertEquals("ACTIVE", v.status());
        assertTrue(v.usable());
    }

    @Test
    void startDateBeforeTodayIsRejected() {
        MembershipCardRequests.Issue r = issueReq(silver);
        r.setStartDate(DAY_1.minusDays(1));
        assertThrows(ResponseStatusException.class, () -> cards.issue(admin, r));
    }

    @Test
    void rejectsBadIssueInput() {
        MembershipCardRequests.Issue noName = issueReq(silver);
        noName.setCustomerName(" ");
        assertThrows(ResponseStatusException.class, () -> cards.issue(admin, noName));

        MembershipCardRequests.Issue badPhone = issueReq(silver);
        badPhone.setCustomerPhone("12");
        assertThrows(ResponseStatusException.class, () -> cards.issue(admin, badPhone));

        MembershipCardRequests.Issue pending = issueReq(silver);
        pending.setPaymentMode("pending");
        assertThrows(ResponseStatusException.class, () -> cards.issue(admin, pending));
    }

    @Test
    void sameRequestIdIssuesOnlyOneCard() {
        MembershipCardRequests.Issue r = issueReq(silver);
        r.setRequestId(UUID.randomUUID().toString());
        MembershipCard first = cards.issue(admin, r);
        MembershipCard second = cards.issue(admin, r);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, cards.list(admin.getCafeId(), (String) null).size());
        assertEquals(List.of(Type.LOAD, Type.BONUS), types(first));
    }

    // ---------------- recharge (no carry-forward) ----------------

    @Test
    void rechargeBeforeExpiryForfeitsRemainingBalanceAndStartsFreshPeriod() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        clock.setDate(DAY_1.plusDays(5));

        MembershipCardRequests.Recharge r = rechargeReq(gold);
        r.setExpectedForfeitPaise(110_000L);
        MembershipCard after = cards.recharge(admin, c.getId(), r);

        assertEquals("Gold", after.getPlanName());
        assertEquals(200_000L, after.getPaidBalancePaise());
        assertEquals(50_000L, after.getBonusBalancePaise());
        assertEquals(DAY_1.plusDays(5), after.getStartDate());
        assertEquals(DAY_1.plusDays(5 + 59), after.getExpiryDate());
        assertEquals(List.of(Type.LOAD, Type.BONUS, Type.RECHARGE_FORFEIT, Type.RECHARGE, Type.BONUS), types(after));

        MembershipLedgerEntry forfeit = cards.ledger(admin.getCafeId(), c.getId()).get(2);
        assertEquals(-100_000L, forfeit.getPaidPortionPaise());
        assertEquals(-10_000L, forfeit.getBonusPortionPaise());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void rechargeOfLapsedCardRecordsExpiryForfeitNotRechargeForfeit() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        clock.setDate(DAY_1.plusDays(40));

        MembershipCard after = cards.recharge(admin, c.getId(), rechargeReq(silver));
        assertEquals(List.of(Type.LOAD, Type.BONUS, Type.EXPIRY_FORFEIT, Type.RECHARGE, Type.BONUS), types(after));
        assertEquals("ACTIVE", cards.view(admin.getCafeId(), c.getId()).status());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void rechargeRejectsStaleForfeitConfirmation() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        MembershipCardRequests.Recharge r = rechargeReq(gold);
        r.setExpectedForfeitPaise(5_000L);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> cards.recharge(admin, c.getId(), r));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(110_000L, reload(c).totalBalancePaise());
    }

    @Test
    void rechargeWithFutureStartIsScheduled() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        MembershipCardRequests.Recharge r = rechargeReq(silver);
        r.setStartDate(DAY_1.plusDays(3));
        cards.recharge(admin, c.getId(), r);
        assertEquals("SCHEDULED", cards.view(admin.getCafeId(), c.getId()).status());
    }

    // ---------------- expiry ----------------

    @Test
    void expiredCardIsDetectedOnReadThenForfeitedByJobExactlyOnce() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        clock.setDate(DAY_1.plusDays(29)); // last valid day
        assertEquals("ACTIVE", cards.view(admin.getCafeId(), c.getId()).status());
        assertEquals(1, cards.view(admin.getCafeId(), c.getId()).daysRemaining());

        clock.setDate(DAY_1.plusDays(30));
        MembershipCardView v = cards.view(admin.getCafeId(), c.getId());
        assertEquals("EXPIRED", v.status());
        assertFalse(v.usable());
        assertEquals(110_000L, v.pendingForfeitPaise());

        cards.expireDueCards();
        cards.expireDueCards(); // second run must not forfeit twice

        MembershipCard after = reload(c);
        assertEquals(MembershipCard.Status.EXPIRED, after.getStatus());
        assertEquals(0L, after.totalBalancePaise());
        assertEquals(List.of(Type.LOAD, Type.BONUS, Type.EXPIRY_FORFEIT), types(after));
        MembershipLedgerEntry forfeit = cards.ledger(admin.getCafeId(), c.getId()).getFirst();
        assertEquals(-110_000L, forfeit.getAmountPaise());
        assertEquals("System", forfeit.getUserName());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void extendingBeforeForfeitKeepsBalance() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        clock.setDate(DAY_1.plusDays(31)); // lapsed, job not run yet

        MembershipCardRequests.Extend e = new MembershipCardRequests.Extend();
        e.setNewExpiryDate(DAY_1.plusDays(60));
        e.setReason("Customer was travelling");
        cards.extendExpiry(admin, c.getId(), e);

        MembershipCardView v = cards.view(admin.getCafeId(), c.getId());
        assertEquals("ACTIVE", v.status());
        assertEquals(110_000L, v.totalBalancePaise());
        assertEquals(Type.EXPIRY_EXTENDED, types(c).getLast());
    }

    @Test
    void extendingAfterForfeitReactivatesWithZeroBalance() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        clock.setDate(DAY_1.plusDays(31));
        cards.expireDueCards();

        MembershipCardRequests.Extend e = new MembershipCardRequests.Extend();
        e.setNewExpiryDate(DAY_1.plusDays(60));
        e.setReason("Goodwill");
        cards.extendExpiry(admin, c.getId(), e);

        MembershipCardView v = cards.view(admin.getCafeId(), c.getId());
        assertEquals("ACTIVE", v.status());
        assertEquals(0L, v.totalBalancePaise());
    }

    @Test
    void extendRequiresLaterDateAndReason() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        MembershipCardRequests.Extend e = new MembershipCardRequests.Extend();
        e.setNewExpiryDate(DAY_1.plusDays(10));
        e.setReason("x");
        assertThrows(ResponseStatusException.class, () -> cards.extendExpiry(admin, c.getId(), e));
        e.setNewExpiryDate(DAY_1.plusDays(90));
        e.setReason(" ");
        assertThrows(ResponseStatusException.class, () -> cards.extendExpiry(admin, c.getId(), e));
    }

    // ---------------- admin adjust / status ----------------

    @Test
    void manualCreditAndDebitPerBalanceWithReason() {
        MembershipCard c = cards.issue(admin, issueReq(silver));

        MembershipCardRequests.Adjust credit = new MembershipCardRequests.Adjust();
        credit.setDirection("credit");
        credit.setBonusPaise(5_000L);
        credit.setReason("Compensation for console fault");
        cards.adjust(admin, c.getId(), credit);

        MembershipCardRequests.Adjust debit = new MembershipCardRequests.Adjust();
        debit.setDirection("DEBIT");
        debit.setPaidPaise(20_000L);
        debit.setReason("Wrong recharge amount");
        cards.adjust(admin, c.getId(), debit);

        MembershipCard after = reload(c);
        assertEquals(80_000L, after.getPaidBalancePaise());
        assertEquals(15_000L, after.getBonusBalancePaise());
        assertEquals("Wrong recharge amount", cards.ledger(admin.getCafeId(), c.getId()).getFirst().getNote());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void adjustmentGuards() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        MembershipCardRequests.Adjust r = new MembershipCardRequests.Adjust();
        r.setDirection("DEBIT");
        r.setBonusPaise(10_001L);
        r.setReason("too much");
        assertThrows(ResponseStatusException.class, () -> cards.adjust(admin, c.getId(), r)); // > bonus balance

        r.setBonusPaise(100L);
        r.setReason(null);
        assertThrows(ResponseStatusException.class, () -> cards.adjust(admin, c.getId(), r)); // no reason

        r.setReason("x");
        r.setBonusPaise(-5L);
        assertThrows(ResponseStatusException.class, () -> cards.adjust(admin, c.getId(), r)); // negative
        assertEquals(110_000L, reload(c).totalBalancePaise());
    }

    @Test
    void suspendReactivateAndCloseWithForfeit() {
        MembershipCard c = cards.issue(admin, issueReq(silver));

        cards.changeStatus(admin, c.getId(), status("SUSPEND", "Card misuse reported"));
        MembershipCardView v = cards.view(admin.getCafeId(), c.getId());
        assertEquals("SUSPENDED", v.status());
        assertFalse(v.usable());
        assertThrows(ResponseStatusException.class, () -> cards.recharge(admin, c.getId(), rechargeReq(silver)));

        cards.changeStatus(admin, c.getId(), status("REACTIVATE", "Resolved"));
        assertTrue(cards.view(admin.getCafeId(), c.getId()).usable());

        cards.changeStatus(admin, c.getId(), status("CLOSE", "Customer moved away"));
        MembershipCard closed = reload(c);
        assertEquals(MembershipCard.Status.CLOSED, closed.getStatus());
        assertEquals(0L, closed.totalBalancePaise());
        assertEquals(List.of(Type.LOAD, Type.BONUS, Type.SUSPENDED, Type.REACTIVATED, Type.CLOSE_FORFEIT, Type.CLOSED), types(c));
        assertThrows(ResponseStatusException.class, () -> cards.recharge(admin, c.getId(), rechargeReq(silver)));
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    static MembershipCardRequests.StatusChange status(String action, String reason) {
        MembershipCardRequests.StatusChange s = new MembershipCardRequests.StatusChange();
        s.setAction(action);
        s.setReason(reason);
        return s;
    }

    @Test
    void otherCafeCannotTouchCard() {
        MembershipCard c = cards.issue(admin, issueReq(silver));
        User intruder = new User();
        intruder.setCafeId("someone-else");
        intruder.setName("x");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> cards.changeStatus(intruder, c.getId(), status("CLOSE", "x")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------------- concurrency ----------------

    @Test
    void concurrentDebitsNeverOverspendAndLedgerReconciles() throws Exception {
        MembershipCard c = cards.issue(admin, issueReq(silver)); // 1000 paid + 100 bonus
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                MembershipCardRequests.Adjust d = new MembershipCardRequests.Adjust();
                d.setDirection("DEBIT");
                d.setPaidPaise(20_000L); // ₹200 each; only 5 fit in ₹1000
                d.setReason("concurrency test");
                go.await();
                try {
                    cards.adjust(admin, c.getId(), d);
                    ok.incrementAndGet();
                } catch (ResponseStatusException e) {
                    refused.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(5, ok.get());
        assertEquals(threads - 5, refused.get());
        MembershipCard after = reload(c);
        assertEquals(0L, after.getPaidBalancePaise());
        assertEquals(10_000L, after.getBonusBalancePaise());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }
}
