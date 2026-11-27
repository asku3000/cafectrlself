package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.CheckoutRequest;
import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.dto.MembershipSettingsRequest;
import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry.Type;
import com.progameflixx.cafectrl.repository.*;
import com.progameflixx.cafectrl.service.MembershipCardService;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import com.progameflixx.cafectrl.service.SessionBillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Checkout with membership cards. Not @Transactional: every call commits like production, so atomicity
 * (nothing saved when a step fails) and concurrency are tested for real. Each test uses its own cafe.
 *
 * Standard bill used below: 60 min on a rate card at ₹100/hour = ₹100, plus a ₹50 snack = ₹150 subtotal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class MembershipBillingTest {

    static final LocalDateTime START = LocalDateTime.of(2026, 12, 2, 10, 0);
    static final LocalDateTime END = START.plusMinutes(60);

    @Autowired SessionBillingService billing;
    @Autowired MembershipCardService cards;
    @Autowired MembershipPlanService plans;
    @Autowired CustomerSessionRepository sessions;
    @Autowired RateCardRepository rateCards;
    @Autowired PendingPaymentRepository pending;
    @Autowired UserRepository users;
    @Autowired TestClockConfig.MutableClock clock;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

    User admin;
    String rateCardId;

    @BeforeEach
    void setUp() {
        clock.setDate(START.toLocalDate());
        admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setCafeId("cafe-" + UUID.randomUUID());
        admin.setName("Owner");
        admin.setRole("CAFE_ADMIN");
        rateCardId = rateCard(admin.getCafeId());
    }

    String rateCard(String cafeId) {
        Map<String, Object> prices = new HashMap<>();
        for (String d : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            prices.put(d, List.of(
                    Map.of("duration", 30, "prices", Map.of("1", 60)),
                    Map.of("duration", 60, "prices", Map.of("1", 100))));
        }
        RateCard rc = new RateCard();
        rc.setCafeId(cafeId);
        rc.setName("Standard");
        rc.setWeekdayPrices(prices);
        rc.setBillingIntervalMinutes(30);
        rc.setGraceMinutes(0);
        rc.setPlayerCounts(List.of(1));
        return rateCards.save(rc).getId();
    }

    /** Running session: 1 game from START (bills ₹100 at END) + a ₹50 snack. */
    CustomerSession session(MembershipCard tagged) {
        CustomerSession s = new CustomerSession();
        s.setCafeId(admin.getCafeId());
        s.setCustomerName("Kabir");
        s.setCustomerPhone("9333333333");
        s.setCreatedAt(START);
        s.setStatus("active");
        s.setMembershipCardId(tagged != null ? tagged.getId() : null);
        GameSession g = new GameSession();
        g.setResourceId("res-1");
        g.setResourceName("PS5 #1");
        g.setRateCardId(rateCardId);
        g.setStartTime(START);
        g.setStatus("active");
        g.setPlayerCount(1);
        GameSessionItem it = new GameSessionItem();
        it.setType("inventory");
        it.setRefId("coke");
        it.setName("Coke");
        it.setUnitPrice(50.0);
        it.setQty(1);
        it.setTotal(50.0);
        it.setAddedAt(START);
        g.addItem(it);
        s.addGame(g);
        return sessions.save(s);
    }

    MembershipCard card(long loadPaise, String bonusType, long bonusValue) {
        MembershipPlanRequest p = new MembershipPlanRequest();
        p.setName("Plan " + UUID.randomUUID());
        p.setLoadAmountPaise(loadPaise);
        p.setBonusType(bonusType);
        p.setBonusValue(bonusValue);
        p.setValidityDays(30);
        MembershipCardRequests.Issue r = new MembershipCardRequests.Issue();
        r.setCustomerName("Kabir");
        r.setCustomerPhone("9333333333");
        r.setPlanId(plans.create(admin, p).getId());
        r.setPaymentMode("cash");
        return cards.issue(admin, r);
    }

    static CheckoutRequest checkout(double adjustment, Long cardPaise, PaymentSplit... others) {
        CheckoutRequest r = new CheckoutRequest();
        r.setAdjustment(adjustment);
        r.setManualBilledAt(END);
        r.setMembershipAmountPaise(cardPaise);
        r.setPayments(new ArrayList<>(List.of(others)));
        return r;
    }

    static PaymentSplit pay(String mode, double amount) {
        return new PaymentSplit(mode, amount);
    }

    MembershipCard reload(MembershipCard c) {
        return cards.getCard(admin.getCafeId(), c.getId());
    }

    List<Type> ledgerTypes(MembershipCard c) {
        return cards.ledger(admin.getCafeId(), c.getId()).stream().map(MembershipLedgerEntry::getEntryType).toList().reversed();
    }

    CustomerSession reloadSession(CustomerSession s) {
        return tx.execute(t -> {
            CustomerSession x = sessions.findById(s.getId()).orElseThrow();
            x.getPayments().size();
            x.getGames().forEach(g -> g.getItems().size());
            return x;
        });
    }

    // ---------------- deduction after discounts / adjustments ----------------

    @Test
    void deductsTheFinalAmountAfterAdjustmentNotTheGross() {
        MembershipCard c = card(100_000L, "FIXED", 0L); // ₹1000
        CustomerSession s = session(c);

        // ₹150 subtotal with a ₹30 discount = ₹120 payable; all from the card
        SessionBillingService.CheckoutResult r = billing.checkout(admin, s.getId(), checkout(-30, 12_000L));

        assertEquals(120.0, r.grandTotal());
        assertEquals(88_000L, reload(c).getPaidBalancePaise());
        CustomerSession billed = reloadSession(s);
        assertEquals("billed", billed.getStatus());
        assertEquals(120.0, billed.getBillTotal());
        PaymentSplit cardPay = billed.getPayments().getFirst();
        assertEquals("membership", cardPay.getMode());
        assertEquals(120.0, cardPay.getAmount());
        assertEquals(c.getCardNumber(), cardPay.getCardNumber());
        assertEquals(88_000L, cardPay.getCardBalanceAfterPaise());
        assertNotNull(reload(c).getLastUsedAt());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void positiveAdjustmentIsAlsoChargedToTheCard() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        billing.checkout(admin, s.getId(), checkout(25.5, 17_550L)); // 150 + 25.50
        assertEquals(100_000L - 17_550L, reload(c).getPaidBalancePaise());
    }

    // ---------------- split payment / partial / none ----------------

    @Test
    void splitPaymentCardThenCash() {
        MembershipCard c = card(10_000L, "FIXED", 0L); // ₹100 on card
        CustomerSession s = session(c);

        billing.checkout(admin, s.getId(), checkout(-30, 10_000L, pay("cash", 20.0))); // 120 = 100 card + 20 cash

        assertEquals(0L, reload(c).totalBalancePaise());
        List<PaymentSplit> pays = reloadSession(s).getPayments();
        assertEquals(List.of("membership", "cash"), pays.stream().map(PaymentSplit::getMode).toList());
        assertEquals(List.of(100.0, 20.0), pays.stream().map(PaymentSplit::getAmount).toList());
    }

    @Test
    void staffMayPayPartlyOrNotAtAllFromTaggedCard() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession partly = session(c);
        billing.checkout(admin, partly.getId(), checkout(0, 5_000L, pay("upi", 100.0))); // 50 card + 100 upi
        assertEquals(95_000L, reload(c).getPaidBalancePaise());

        CustomerSession none = session(c);
        billing.checkout(admin, none.getId(), checkout(0, null, pay("cash", 150.0)));
        assertEquals(95_000L, reload(c).getPaidBalancePaise());
        assertEquals(List.of("cash"), reloadSession(none).getPayments().stream().map(PaymentSplit::getMode).toList());
    }

    @Test
    void paymentsThatDontAddUpAreRejectedAndNothingIsSaved() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> billing.checkout(admin, s.getId(), checkout(0, 5_000L, pay("cash", 50.0))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("active", reloadSession(s).getStatus());
        assertEquals(100_000L, reload(c).getPaidBalancePaise());
    }

    @Test
    void cardAmountAboveTheBillIsRejected() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        assertThrows(ResponseStatusException.class, () -> billing.checkout(admin, s.getId(), checkout(0, 15_001L)));
    }

    @Test
    void clientCannotInjectAMembershipSplit() {
        CustomerSession s = session(null);
        assertThrows(ResponseStatusException.class,
                () -> billing.checkout(admin, s.getId(), checkout(0, null, pay("membership", 150.0))));
    }

    @Test
    void cardPaymentNeedsATaggedCard() {
        CustomerSession s = session(null);
        assertThrows(ResponseStatusException.class, () -> billing.checkout(admin, s.getId(), checkout(0, 15_000L)));
    }

    // ---------------- insufficient / expired ----------------

    @Test
    void insufficientBalanceIsRefusedAtomically() {
        MembershipCard c = card(10_000L, "FIXED", 0L); // ₹100
        CustomerSession s = session(c);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> billing.checkout(admin, s.getId(), checkout(0, 15_000L)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("has only ₹100"));

        CustomerSession after = reloadSession(s);
        assertEquals("active", after.getStatus());
        assertEquals("active", after.getGames().iterator().next().getStatus()); // game end not committed either
        assertEquals(10_000L, reload(c).totalBalancePaise());
        assertEquals(List.of(Type.LOAD), ledgerTypes(c));
    }

    @Test
    void expiredCardCannotPay() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        clock.setDate(START.toLocalDate().plusDays(30)); // validity was 30 days from START date

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> billing.checkout(admin, s.getId(), checkout(0, 15_000L)));
        assertTrue(ex.getReason().contains("expired"));
        assertEquals("active", reloadSession(s).getStatus());
    }

    @Test
    void suspendedCardCannotPay() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        MembershipCardRequests.StatusChange sc = new MembershipCardRequests.StatusChange();
        sc.setAction("SUSPEND");
        sc.setReason("lost card");
        cards.changeStatus(admin, c.getId(), sc);
        assertThrows(ResponseStatusException.class, () -> billing.checkout(admin, s.getId(), checkout(0, 15_000L)));
    }

    // ---------------- deduction order ----------------

    @Test
    void paidFirstIsTheDefaultAndSpillsIntoBonus() {
        MembershipCard c = card(10_000L, "FIXED", 5_000L); // paid 100, bonus 50
        CustomerSession s = session(c);
        billing.checkout(admin, s.getId(), checkout(-30, 12_000L)); // 120
        MembershipCard after = reload(c);
        assertEquals(0L, after.getPaidBalancePaise());
        assertEquals(3_000L, after.getBonusBalancePaise());
        MembershipLedgerEntry debit = cards.ledger(admin.getCafeId(), c.getId()).getFirst();
        assertEquals(-10_000L, debit.getPaidPortionPaise());
        assertEquals(-2_000L, debit.getBonusPortionPaise());
    }

    @Test
    void bonusFirstWhenConfigured() {
        MembershipSettingsRequest set = new MembershipSettingsRequest();
        set.setDeductionOrder("BONUS_FIRST");
        plans.updateSettings(admin, set);
        MembershipCard c = card(100_000L, "PERCENT", 1_000L); // paid 1000, bonus 100
        CustomerSession s = session(c);
        billing.checkout(admin, s.getId(), checkout(-30, 12_000L)); // 120: 100 bonus + 20 paid
        MembershipCard after = reload(c);
        assertEquals(0L, after.getBonusBalancePaise());
        assertEquals(98_000L, after.getPaidBalancePaise());
    }

    // ---------------- idempotency / concurrency ----------------

    @Test
    void secondCheckoutOfSameSessionIsRefusedAndDeductsOnce() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        billing.checkout(admin, s.getId(), checkout(0, 15_000L));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> billing.checkout(admin, s.getId(), checkout(0, 15_000L)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(85_000L, reload(c).getPaidBalancePaise());
        assertEquals(List.of(Type.LOAD, Type.BILL_DEBIT), ledgerTypes(c));
    }

    @Test
    void doubleClickRacingOnOneSessionDeductsOnce() throws Exception {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        List<Object> outcomes = race(2, i -> billing.checkout(admin, s.getId(), checkout(0, 15_000L)));
        assertEquals(1, outcomes.stream().filter(o -> o instanceof SessionBillingService.CheckoutResult).count());
        assertEquals(85_000L, reload(c).getPaidBalancePaise());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void twoBillsHittingOneCardAtOnceNeverOverspend() throws Exception {
        MembershipCard c = card(20_000L, "FIXED", 0L); // ₹200: only one ₹150 bill fits
        CustomerSession a = session(c);
        CustomerSession b = session(c);
        List<CustomerSession> both = List.of(a, b);

        List<Object> outcomes = race(2, i -> billing.checkout(admin, both.get(i).getId(), checkout(0, 15_000L)));

        assertEquals(1, outcomes.stream().filter(o -> o instanceof SessionBillingService.CheckoutResult).count());
        assertEquals(1, outcomes.stream().filter(o -> o instanceof ResponseStatusException e
                && e.getStatusCode() == HttpStatus.CONFLICT).count());
        assertEquals(5_000L, reload(c).totalBalancePaise());
        assertEquals(1, both.stream().filter(x -> "billed".equals(reloadSession(x).getStatus())).count());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    /** Runs n calls at the same instant; returns each result or thrown exception. */
    List<Object> race(int n, java.util.function.IntFunction<Object> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            fs.add(pool.submit(() -> {
                go.await();
                try { return call.apply(idx); } catch (RuntimeException e) { return e; }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    // ---------------- void / reopen reversals ----------------

    @Test
    void voidReversesTheExactPaidAndBonusPortions() {
        MembershipCard c = card(10_000L, "FIXED", 5_000L); // paid 100, bonus 50
        CustomerSession s = session(c);
        billing.checkout(admin, s.getId(), checkout(-30, 12_000L)); // takes paid 100 + bonus 20

        SessionBillingService.UndoResult r = billing.voidBill(admin, s.getId(), "Customer disputed the bill");

        assertEquals("voided", reloadSession(s).getStatus());
        assertEquals(1, r.reversals().size());
        assertEquals(12_000L, r.reversals().getFirst().creditedPaise());
        MembershipCard after = reload(c);
        assertEquals(10_000L, after.getPaidBalancePaise());
        assertEquals(5_000L, after.getBonusBalancePaise());
        MembershipLedgerEntry rev = cards.ledger(admin.getCafeId(), c.getId()).getFirst();
        assertEquals(Type.REVERSAL, rev.getEntryType());
        assertEquals(s.getId(), rev.getSessionId());
        assertNotNull(rev.getReversesEntryId());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));

        assertThrows(ResponseStatusException.class, () -> billing.voidBill(admin, s.getId(), "again"));
    }

    @Test
    void reopenReversesThenRebillUsesNewAmountOnce() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        billing.checkout(admin, s.getId(), checkout(0, 15_000L));

        billing.reopen(admin, s.getId(), "Forgot the loyalty discount");
        CustomerSession reopened = reloadSession(s);
        assertEquals("active", reopened.getStatus());
        assertTrue(reopened.getPayments().isEmpty());
        assertEquals(100_000L, reload(c).getPaidBalancePaise());

        billing.checkout(admin, s.getId(), checkout(-50, 10_000L)); // corrected: 100
        assertEquals(90_000L, reload(c).getPaidBalancePaise());
        assertEquals(List.of(Type.LOAD, Type.BILL_DEBIT, Type.REVERSAL, Type.BILL_DEBIT), ledgerTypes(c));
        assertEquals(2, reloadSession(s).getBillSeq());
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    @Test
    void reopenVoidsUnpaidPendingDebtButIsBlockedOnceDebtWasSettled() {
        CustomerSession s = session(null);
        billing.checkout(admin, s.getId(), checkout(0, null, pay("cash", 100.0), pay("pending", 50.0)));
        PendingPayment debt = pending.findBySessionId(s.getId()).getFirst();
        assertEquals("PENDING", debt.getStatus());

        billing.reopen(admin, s.getId(), "wrong split");
        assertEquals("VOIDED", pending.findById(debt.getId()).orElseThrow().getStatus());

        billing.checkout(admin, s.getId(), checkout(0, null, pay("cash", 100.0), pay("pending", 50.0)));
        PendingPayment debt2 = pending.findBySessionId(s.getId()).stream()
                .filter(d -> "PENDING".equals(d.getStatus())).findFirst().orElseThrow();
        debt2.setStatus("CLEARED");
        pending.save(debt2);
        assertThrows(ResponseStatusException.class, () -> billing.voidBill(admin, s.getId(), "too late"));
    }

    @Test
    void reversalOntoAnExpiredCardIsRecordedThenForfeited() {
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession s = session(c);
        billing.checkout(admin, s.getId(), checkout(0, 15_000L));
        clock.setDate(START.toLocalDate().plusDays(31));
        cards.expireDueCards();

        SessionBillingService.UndoResult r = billing.voidBill(admin, s.getId(), "late dispute");
        assertEquals(15_000L, r.reversals().getFirst().reForfeitedPaise());
        assertEquals(0L, reload(c).totalBalancePaise());
        assertEquals(List.of(Type.LOAD, Type.BILL_DEBIT, Type.EXPIRY_FORFEIT, Type.REVERSAL, Type.EXPIRY_FORFEIT), ledgerTypes(c));
        assertTrue(cards.reconcile(admin.getCafeId(), c.getId()));
    }

    // ---------------- non-member flow unchanged ----------------

    @Test
    void nonMemberCheckoutWorksAsBeforeIncludingPendingDebt() {
        CustomerSession s = session(null);
        SessionBillingService.CheckoutResult r = billing.checkout(admin, s.getId(), checkout(-10, null,
                pay("cash", 100.0), pay("pending", 40.0)));
        assertEquals(140.0, r.grandTotal());
        CustomerSession billed = reloadSession(s);
        assertEquals("billed", billed.getStatus());
        assertEquals(List.of("cash", "pending"), billed.getPayments().stream().map(PaymentSplit::getMode).toList());
        assertNull(billed.getPayments().getFirst().getCardId());
        assertEquals(1, pending.findBySessionId(s.getId()).size());
    }

    // ---------------- HTTP: roles + backdated preview ----------------

    void saveHttpUser(String email, String role) {
        if (users.findByEmail(email).isPresent()) {
            User u = users.findByEmail(email).get();
            u.setCafeId(admin.getCafeId());
            users.save(u);
            return;
        }
        User u = new User();
        u.setEmail(email);
        u.setName(email);
        u.setRole(role);
        u.setCafeId(admin.getCafeId());
        u.setActive(true);
        u.setPermissions(new ArrayList<>(List.of("sessions", "billing")));
        users.save(u);
    }

    @Test
    @WithMockUser(username = "billop@test.local", roles = "OPERATOR")
    void operatorCannotReopenOrVoid() throws Exception {
        saveHttpUser("billop@test.local", "OPERATOR");
        CustomerSession s = session(null);
        billing.checkout(admin, s.getId(), checkout(0, null, pay("cash", 150.0)));
        mvc.perform(post("/api/sessions/" + s.getId() + "/void").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/sessions/" + s.getId() + "/reopen").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/sessions/" + s.getId() + "/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adjustment\":0,\"payments\":[{\"mode\":\"cash\",\"amount\":150}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "billadmin@test.local", roles = "CAFE_ADMIN")
    void cardDetailListsLinkedBillsWithNetAmountPaidFromCard() throws Exception {
        saveHttpUser("billadmin@test.local", "CAFE_ADMIN");
        MembershipCard c = card(100_000L, "FIXED", 0L);
        CustomerSession paid = session(c);
        billing.checkout(admin, paid.getId(), checkout(0, 15_000L));
        CustomerSession voided = session(c);
        billing.checkout(admin, voided.getId(), checkout(0, 10_000L, pay("cash", 50.0)));
        billing.voidBill(admin, voided.getId(), "dispute");
        CustomerSession running = session(c);

        mvc.perform(get("/api/membership/cards/" + c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(3))
                .andExpect(jsonPath("$.sessions[?(@.id == '" + paid.getId() + "')].card_paid_paise").value(15000))
                .andExpect(jsonPath("$.sessions[?(@.id == '" + voided.getId() + "')].card_paid_paise").value(0))
                .andExpect(jsonPath("$.sessions[?(@.id == '" + voided.getId() + "')].status").value("voided"))
                .andExpect(jsonPath("$.sessions[?(@.id == '" + running.getId() + "')].currently_tagged").value(true));
        mvc.perform(get("/api/membership/cards/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_cards").value(1))
                .andExpect(jsonPath("$.outstanding_paid_paise").value(85000));
    }

    @Test
    @WithMockUser(username = "billop@test.local", roles = "OPERATOR")
    void backdatedPreviewUsesTheCustomTime() throws Exception {
        saveHttpUser("billop@test.local", "OPERATOR");
        CustomerSession s = session(null);
        // 30 minutes after start: one 30-minute block = ₹60 + ₹50 snack
        mvc.perform(get("/api/sessions/" + s.getId() + "/bill").param("custom_time", START.plusMinutes(30).toString() + ":00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grand_total").value(110.0))
                .andExpect(jsonPath("$.priced_until").value("2026-12-02T10:30"));
        assertEquals("active", reloadSession(s).getGames().iterator().next().getStatus());
    }
}
