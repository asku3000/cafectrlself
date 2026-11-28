package com.progameflixx.cafectrl.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry;
import com.progameflixx.cafectrl.entity.PendingPayment;
import com.progameflixx.cafectrl.entity.RateCard;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.membership.TestClockConfig;
import com.progameflixx.cafectrl.repository.*;
import com.progameflixx.cafectrl.testsupport.TestIdempotencyKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Server-side duplicate protection: idempotency keys (same key -> same result, nothing done twice) and the
 * business guards that hold even with different keys (two devices, two staff).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class IdempotencyTest {

    static final String CAFE = "cafe-idempotency";
    static final String ADMIN = "idem.admin@test.local";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RateCardRepository rateCards;
    @Autowired AuditEventRepository audit;
    @Autowired MembershipLedgerRepository ledger;
    @Autowired PendingPaymentRepository pending;
    @Autowired CustomerSessionRepository sessions;
    @Autowired IdempotencyStore store;
    @Autowired IdempotencyCleanupJob cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClockConfig.MutableClock clock;

    String rateCardId;
    final RequestPostProcessor asAdmin = user(ADMIN).roles("CAFE_ADMIN");

    @BeforeEach
    void setUp() {
        clock.setDate(LocalDate.now());
        User u = users.findByEmail(ADMIN).orElseGet(User::new);
        u.setEmail(ADMIN);
        u.setName("Ida Admin");
        u.setRole("CAFE_ADMIN");
        u.setCafeId(CAFE);
        u.setActive(true);
        u.setPermissions(new ArrayList<>());
        users.save(u);
        Map<String, Object> prices = new HashMap<>();
        for (String d : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            prices.put(d, List.of(Map.of("duration", 30, "prices", Map.of("1", 60)), Map.of("duration", 60, "prices", Map.of("1", 100))));
        }
        RateCard rc = new RateCard();
        rc.setCafeId(CAFE);
        rc.setName("RC " + UUID.randomUUID());
        rc.setWeekdayPrices(prices);
        rc.setBillingIntervalMinutes(30);
        rc.setGraceMinutes(0);
        rc.setPlayerCounts(List.of(1));
        rateCardId = rateCards.save(rc).getId();
    }

    // ---------------- helpers ----------------

    MvcResult send(MockHttpServletRequestBuilder req, String key, String json) throws Exception {
        req.with(asAdmin);
        if (key != null) req.header("Idempotency-Key", key);
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    String ok(MockHttpServletRequestBuilder req, String json) throws Exception {
        MvcResult r = send(req, null, json);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return r.getResponse().getContentAsString();
    }

    static String key() {
        return "key-" + UUID.randomUUID();
    }

    String resource() throws Exception {
        return JsonPath.read(ok(post("/api/resources"), "{\"name\":\"PS5-" + UUID.randomUUID().toString().substring(0, 4)
                + "\",\"rate_card_id\":\"" + rateCardId + "\"}"), "$.id");
    }

    String startJson(String resId, String cardId) {
        return "{\"customer_name\":\"Ravi\",\"resource_id\":\"" + resId + "\",\"player_count\":1,\"start_time\":\"2026-12-02T10:00:00\""
                + (cardId == null ? "" : ",\"membership_card_id\":\"" + cardId + "\"") + "}";
    }

    String card(long loadPaise) throws Exception {
        String plan = JsonPath.read(ok(post("/api/membership/plans"),
                "{\"name\":\"P " + UUID.randomUUID() + "\",\"load_amount_paise\":" + loadPaise + ",\"bonus_type\":\"FIXED\",\"bonus_value\":0,\"validity_days\":30}"), "$.id");
        return JsonPath.read(ok(post("/api/membership/cards"),
                "{\"customer_name\":\"Ravi\",\"customer_phone\":\"9876543210\",\"plan_id\":\"" + plan + "\",\"payment_mode\":\"cash\"}"), "$.id");
    }

    long runningGamesOn(String resourceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM game_sessions WHERE resource_id = ? AND status = 'active'", Long.class, resourceId);
    }

    long auditCount(String type, String id) {
        return audit.findByEntityTypeAndEntityIdOrderByIdDesc(type, id).size();
    }

    List<MembershipLedgerEntry> debits(String cardId) {
        return ledger.findByCardIdOrderByIdDesc(cardId).stream()
                .filter(e -> e.getEntryType() == MembershipLedgerEntry.Type.BILL_DEBIT).toList();
    }

    /** 60 minutes at ₹100/hour = a ₹100 bill, all paid from the card. */
    static final String CHECKOUT_CARD = "{\"adjustment\":0,\"payments\":[],\"membership_amount_paise\":10000,\"manual_billed_at\":\"2026-12-02T11:00:00\"}";

    // ---------------- idempotency keys ----------------

    @Test
    void repeatingACheckoutWithTheSameKeyReturnsTheOriginalResultAndChargesOnce() throws Exception {
        String cardId = card(100_000L);
        String sid = JsonPath.read(ok(post("/api/sessions"), startJson(resource(), cardId)), "$.id");
        String k = key();

        MvcResult first = send(post("/api/sessions/" + sid + "/checkout"), k, CHECKOUT_CARD);
        assertEquals(200, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        long auditAfterFirst = auditCount(AuditActions.ENTITY_SESSION, sid);
        long cardAuditAfterFirst = auditCount(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId);

        MvcResult again = send(post("/api/sessions/" + sid + "/checkout"), k, CHECKOUT_CARD);
        assertEquals(200, again.getResponse().getStatus(), "a replay is a success, not a 409");
        assertEquals("true", again.getResponse().getHeader("Idempotent-Replay"));
        assertEquals((Object) JsonPath.read(first.getResponse().getContentAsString(), "$.bill_total"),
                JsonPath.read(again.getResponse().getContentAsString(), "$.bill_total"));
        assertEquals("billed", JsonPath.read(again.getResponse().getContentAsString(), "$.status"));

        assertEquals(1, debits(cardId).size(), "the card is charged once");
        assertEquals(auditAfterFirst, auditCount(AuditActions.ENTITY_SESSION, sid), "no second audit entry");
        assertEquals(cardAuditAfterFirst, auditCount(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId));
    }

    @Test
    void criticalActionsWithoutAKeyAreRefused() throws Exception {
        String res = resource();
        MvcResult r = mvc.perform(post("/api/sessions").with(asAdmin).header(TestIdempotencyKeys.NO_KEY_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON).content(startJson(res, null))).andReturn();
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("Idempotency-Key"));
        assertEquals(0, runningGamesOn(res), "nothing was started");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() throws Exception {
        String k = key();
        assertEquals(200, send(post("/api/sessions"), k, startJson(resource(), null)).getResponse().getStatus());
        MvcResult other = send(post("/api/sessions"), k, startJson(resource(), null));
        assertEquals(422, other.getResponse().getStatus());
    }

    @Test
    void theSameKeySentTwiceAtOnceStartsExactlyOneSession() throws Exception {
        String res = resource();
        String k = key();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> { go.await(); return send(post("/api/sessions"), k, startJson(res, null)); }));
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Future<MvcResult> f : fs) {
            MvcResult r = f.get(60, TimeUnit.SECONDS);
            statuses.add(r.getResponse().getStatus());
            if (r.getResponse().getStatus() == 200) ids.add(JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
        }
        pool.shutdown();
        assertEquals(1, runningGamesOn(res), "exactly one session on the station, statuses " + statuses);
        assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 409), "statuses " + statuses);
        assertEquals(1, ids.size(), "both answers (if 200) describe the same session");
    }

    @Test
    void aFailedAttemptIsNotStoredSoTheSameKeyCanBeRetried() throws Exception {
        String cardId = card(5_000L); // ₹50: a ₹100 card payment fails
        String sid = JsonPath.read(ok(post("/api/sessions"), startJson(resource(), cardId)), "$.id");
        String k = key();
        assertEquals(409, send(post("/api/sessions/" + sid + "/checkout"), k, CHECKOUT_CARD).getResponse().getStatus());
        assertTrue(store.find(store.now().toString(), k).isEmpty());

        ok(post("/api/membership/cards/" + cardId + "/adjust"), "{\"direction\":\"CREDIT\",\"paid_paise\":5000,\"reason\":\"top-up for test\"}");
        MvcResult retry = send(post("/api/sessions/" + sid + "/checkout"), k, CHECKOUT_CARD);
        assertEquals(200, retry.getResponse().getStatus(), retry.getResponse().getContentAsString());
        assertNull(retry.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(1, debits(cardId).size());
    }

    @Test
    void aCardAdjustmentWithTheSameKeyIsAppliedOnceAndReplaysTheCard() throws Exception {
        String cardId = card(10_000L);
        String k = key();
        String body = "{\"direction\":\"CREDIT\",\"bonus_paise\":5000,\"reason\":\"goodwill\"}";
        MvcResult a = send(post("/api/membership/cards/" + cardId + "/adjust"), k, body);
        MvcResult b = send(post("/api/membership/cards/" + cardId + "/adjust"), k, body);
        assertEquals(200, a.getResponse().getStatus());
        assertEquals(200, b.getResponse().getStatus());
        assertEquals("true", b.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(5000, (int) JsonPath.read(b.getResponse().getContentAsString(), "$.bonus_balance_paise"));
        assertEquals(1, ledger.findByCardIdOrderByIdDesc(cardId).stream()
                .filter(e -> e.getEntryType() == MembershipLedgerEntry.Type.ADJUSTMENT).count());
        assertEquals(1, audit.findByEntityTypeAndEntityIdOrderByIdDesc(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId).stream()
                .filter(e -> e.getAction().equals(AuditActions.CARD_ADJUSTED)).count());
    }

    @Test
    void expiredKeysAreCleanedUpAndOnlyThose() {
        assertTrue(store.claim("scope-x", "old-key-123", "TEST", "h"));
        assertTrue(store.claim("scope-x", "new-key-123", "TEST", "h"));
        jdbc.update("UPDATE idempotency_keys SET expires_at = ? WHERE idem_key = 'old-key-123'", store.now().minusHours(1));
        long sessionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM customer_sessions", Long.class);
        cleanup.run();
        assertTrue(store.find("scope-x", "old-key-123").isEmpty());
        assertTrue(store.find("scope-x", "new-key-123").isPresent());
        assertEquals(sessionsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM customer_sessions", Long.class));
    }

    // ---------------- business guards (hold even with different keys) ----------------

    @Test
    void aStationCanHaveOnlyOneRunningGame() throws Exception {
        String res = resource();
        ok(post("/api/sessions"), startJson(res, null));
        MvcResult second = send(post("/api/sessions"), null, startJson(res, null));
        assertEquals(409, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().contains("already in use"));

        String other = JsonPath.read(ok(post("/api/sessions"), startJson(resource(), null)), "$.id");
        MvcResult addBusy = send(post("/api/sessions/" + other + "/games"), null, "{\"resource_id\":\"" + res + "\",\"player_count\":1}");
        assertEquals(409, addBusy.getResponse().getStatus());
    }

    @Test
    void endingAGameTwiceAndCancellingTwiceAreRefused() throws Exception {
        String started = ok(post("/api/sessions"), startJson(resource(), null));
        String sid = JsonPath.read(started, "$.id");
        String gid = JsonPath.read(started, "$.games[0].id");
        ok(post("/api/sessions/" + sid + "/games/" + gid + "/end"), "{\"end_time\":\"2026-12-02T11:00:00\"}");
        assertEquals(409, send(post("/api/sessions/" + sid + "/games/" + gid + "/end"), null, "{\"end_time\":\"2026-12-02T11:00:00\"}")
                .getResponse().getStatus());

        long before = audit.findByEntityTypeAndEntityIdOrderByIdDesc(AuditActions.ENTITY_SESSION, sid).stream()
                .filter(e -> e.getAction().equals(AuditActions.SESSION_CANCELLED)).count();
        ok(post("/api/sessions/" + sid + "/cancel"), null);
        assertEquals(409, send(post("/api/sessions/" + sid + "/cancel"), null, null).getResponse().getStatus());
        long after = audit.findByEntityTypeAndEntityIdOrderByIdDesc(AuditActions.ENTITY_SESSION, sid).stream()
                .filter(e -> e.getAction().equals(AuditActions.SESSION_CANCELLED) && e.getOutcome().name().equals("SUCCESS")).count();
        assertEquals(before + 1, after, "only one successful cancel is recorded");
        // items can't be added to a closed session
        assertEquals(409, send(post("/api/sessions/" + sid + "/games/" + gid + "/items"), null,
                "{\"type\":\"accessory\",\"ref_id\":\"x\",\"qty\":1}").getResponse().getStatus());
    }

    @Test
    void payingABillTwiceWithDifferentKeysChargesTheCardOnce() throws Exception {
        String cardId = card(100_000L);
        String sid = JsonPath.read(ok(post("/api/sessions"), startJson(resource(), cardId)), "$.id");
        assertEquals(200, send(post("/api/sessions/" + sid + "/checkout"), key(), CHECKOUT_CARD).getResponse().getStatus());
        assertEquals(409, send(post("/api/sessions/" + sid + "/checkout"), key(), CHECKOUT_CARD).getResponse().getStatus());
        assertEquals(1, debits(cardId).size());
    }

    @Test
    void aDebtCanBeSettledOnlyOnceEvenFromTwoDevicesAtOnce() throws Exception {
        String sid = JsonPath.read(ok(post("/api/sessions"), startJson(resource(), null)), "$.id");
        ok(post("/api/sessions/" + sid + "/checkout"),
                "{\"adjustment\":0,\"payments\":[{\"mode\":\"cash\",\"amount\":60},{\"mode\":\"pending\",\"amount\":40}],\"manual_billed_at\":\"2026-12-02T11:00:00\"}");
        PendingPayment debt = pending.findBySessionId(sid).getFirst();
        String url = "/api/pending-payments/" + debt.getId() + "/clear";
        String body = "{\"payments\":[{\"mode\":\"upi\",\"amount\":40}]}";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            fs.add(pool.submit(() -> { go.await(); return send(post(url), key(), body).getResponse().getStatus(); }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : fs) statuses.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses " + statuses);
        assertEquals(1, pending.findBySessionId(sid).size(), "no duplicate settlement rows");
        assertEquals("CLEARED", pending.findById(debt.getId()).orElseThrow().getStatus());
    }
}
