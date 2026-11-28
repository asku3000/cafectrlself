package com.progameflixx.cafectrl.audit;

import com.jayway.jsonpath.JsonPath;
import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.PendingPayment;
import com.progameflixx.cafectrl.entity.RateCard;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.membership.TestClockConfig;
import com.progameflixx.cafectrl.repository.*;
import com.progameflixx.cafectrl.service.MembershipCardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every important action leaves the right audit entry, through the real HTTP endpoints (so actor, IP and
 * request id are captured as in production). Not @Transactional: best-effort entries are written after commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AuditInstrumentationTest {

    static final String CAFE = "cafe-audit-instrumentation";
    static final String ADMIN = "audit.admin@test.local";
    static final String OPERATOR = "audit.operator@test.local";

    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository events;
    @Autowired UserRepository users;
    @Autowired RateCardRepository rateCards;
    @Autowired PendingPaymentRepository pending;
    @Autowired PasswordEncoder encoder;
    @Autowired MembershipCardService cardService;
    @Autowired TestClockConfig.MutableClock clock;

    String rateCardId;

    @BeforeEach
    void setUp() {
        clock.setDate(LocalDate.now());
        user(ADMIN, "Asha Owner", "CAFE_ADMIN", List.of());
        user(OPERATOR, "Omar Operator", "OPERATOR", List.of("sessions", "billing"));
        rateCardId = rateCard();
    }

    @AfterEach
    void resetClock() {
        clock.setDate(LocalDate.now());
    }

    void user(String email, String name, String role, List<String> perms) {
        User u = users.findByEmail(email).orElseGet(User::new);
        u.setEmail(email);
        u.setName(name);
        u.setRole(role);
        u.setCafeId(CAFE);
        u.setActive(true);
        u.setPassword(encoder.encode("Correct#Horse1"));
        u.setPermissions(new ArrayList<>(perms));
        users.save(u);
    }

    String rateCard() {
        Map<String, Object> prices = new HashMap<>();
        for (String d : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            prices.put(d, List.of(Map.of("duration", 30, "prices", Map.of("1", 60)), Map.of("duration", 60, "prices", Map.of("1", 100))));
        }
        RateCard rc = new RateCard();
        rc.setCafeId(CAFE);
        rc.setName("Standard " + UUID.randomUUID());
        rc.setWeekdayPrices(prices);
        rc.setBillingIntervalMinutes(30);
        rc.setGraceMinutes(0);
        rc.setPlayerCounts(List.of(1));
        return rateCards.save(rc).getId();
    }

    // ---------------- helpers ----------------

    String call(MockHttpServletRequestBuilder req, String json, int expectStatus) throws Exception {
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req.header("User-Agent", "JUnit-Browser").header("X-Request-Id", "req" + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().is(expectStatus))
                .andReturn().getResponse().getContentAsString();
    }

    List<AuditEvent> trail(String type, String id) {
        return events.findByEntityTypeAndEntityIdOrderByIdDesc(type, id);
    }

    AuditEvent only(String type, String id, String action) {
        List<AuditEvent> found = trail(type, id).stream().filter(e -> e.getAction().equals(action)).toList();
        assertEquals(1, found.size(), () -> "expected one " + action + " on " + type + " " + id + ", got " + trail(type, id).stream().map(AuditEvent::getAction).toList());
        return found.getFirst();
    }

    String plan(long loadPaise) throws Exception {
        String body = call(post("/api/membership/plans"), """
                {"name":"Plan %s","load_amount_paise":%d,"bonus_type":"FIXED","bonus_value":0,"validity_days":30}
                """.formatted(UUID.randomUUID(), loadPaise), 200);
        return JsonPath.read(body, "$.id");
    }

    String card(String planId) throws Exception {
        String body = call(post("/api/membership/cards"), """
                {"customer_name":"Ravi","customer_phone":"9876543210","plan_id":"%s","payment_mode":"cash"}
                """.formatted(planId), 200);
        return JsonPath.read(body, "$.id");
    }

    String resource() throws Exception {
        String body = call(post("/api/resources"), """
                {"name":"PS5-%s","rate_card_id":"%s"}
                """.formatted(UUID.randomUUID().toString().substring(0, 4), rateCardId), 200);
        return JsonPath.read(body, "$.id");
    }

    // ---------------- sessions + billing ----------------

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void sessionAndBillingLifecycleIsFullyAudited() throws Exception {
        String cardId = card(plan(100_000L));
        String resId = resource();
        String accBody = call(post("/api/accessories"), "{\"name\":\"Headset\",\"price\":50}", 200);
        String accId = JsonPath.read(accBody, "$.id");

        String started = call(post("/api/sessions"), """
                {"customer_name":"Ravi","customer_phone":"9876543210","resource_id":"%s","player_count":1,
                 "start_time":"2026-12-02T10:00:00","membership_card_id":"%s"}
                """.formatted(resId, cardId), 200);
        String sid = JsonPath.read(started, "$.id");
        String gid = JsonPath.read(started, "$.games[0].id");
        AuditEvent start = only(AuditActions.ENTITY_SESSION, sid, AuditActions.SESSION_STARTED);
        assertTrue(start.getDescription().startsWith("Started session for Ravi on PS5-"));
        assertEquals("Asha Owner", start.getActorName());
        assertEquals("CAFE_ADMIN", start.getActorRole());
        assertEquals(CAFE, start.getCafeId());
        assertEquals("127.0.0.1", start.getIpAddress());
        assertEquals("JUnit-Browser", start.getUserAgent());
        assertNotNull(start.getRequestId());

        call(post("/api/sessions/" + sid + "/games/" + gid + "/items"), "{\"type\":\"accessory\",\"ref_id\":\"" + accId + "\",\"qty\":1}", 200);
        assertTrue(only(AuditActions.ENTITY_SESSION, sid, AuditActions.SESSION_ITEM_ADDED).getDescription().startsWith("Added Headset x1 (₹50.00)"));
        call(post("/api/sessions/" + sid + "/games/" + gid + "/end"), "{\"end_time\":\"2026-12-02T11:00:00\"}", 200);
        only(AuditActions.ENTITY_SESSION, sid, AuditActions.SESSION_GAME_ENDED);

        // ₹150 bill, ₹30 discount -> ₹120 = ₹100 card + ₹20 cash
        call(post("/api/sessions/" + sid + "/checkout"), """
                {"adjustment":-30,"payments":[{"mode":"cash","amount":20}],"membership_amount_paise":10000,
                 "manual_billed_at":"2026-12-02T11:00:00"}
                """, 200);
        String billNo = AuditFormat.billNo(sid);
        AuditEvent created = only(AuditActions.ENTITY_SESSION, sid, AuditActions.BILL_CREATED);
        assertTrue(created.getDescription().contains("Closed Bill " + billNo + " for Ravi"));
        assertEquals(120.0, ((Number) created.getDetails().get("total")).doubleValue());
        AuditEvent discount = only(AuditActions.ENTITY_SESSION, sid, AuditActions.BILL_DISCOUNT_APPLIED);
        assertTrue(discount.getDescription().startsWith("Applied ₹30.00 discount on Bill " + billNo + " (PS5-"),
                discount.getDescription());
        AuditEvent paid = only(AuditActions.ENTITY_SESSION, sid, AuditActions.PAYMENT_RECORDED);
        assertTrue(paid.getDescription().contains("CARD MC-") && paid.getDescription().contains("₹100.00 + CASH ₹20.00"),
                paid.getDescription());
        AuditEvent debit = only(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId, AuditActions.CARD_DEBITED);
        assertTrue(debit.getDescription().startsWith("Deducted ₹100.00 from card MC-"));
        assertEquals(sid, debit.getDetails().get("session_id"));
        assertEquals("Asha Owner", debit.getActorName());

        // reopen -> card refunded, then close again with a different total -> BILL_EDITED with before/after
        call(post("/api/sessions/" + sid + "/reopen"), "{\"reason\":\"Forgot loyalty discount\"}", 200);
        AuditEvent reopened = only(AuditActions.ENTITY_SESSION, sid, AuditActions.BILL_REOPENED);
        assertEquals("Forgot loyalty discount", reopened.getReason());
        assertEquals(Map.of("before", "billed", "after", "active"), reopened.getChanges().get("status"));
        only(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId, AuditActions.CARD_DEBIT_REVERSED);

        call(post("/api/sessions/" + sid + "/checkout"), """
                {"adjustment":-50,"payments":[{"mode":"upi","amount":100}],"manual_billed_at":"2026-12-02T11:00:00"}
                """, 200);
        AuditEvent edited = only(AuditActions.ENTITY_SESSION, sid, AuditActions.BILL_EDITED);
        Map<?, ?> total = (Map<?, ?>) edited.getChanges().get("total");
        assertEquals(120.0, ((Number) total.get("before")).doubleValue());
        assertEquals(100.0, ((Number) total.get("after")).doubleValue());
        assertTrue(edited.getChanges().containsKey("payments"));
        assertTrue(edited.getChanges().containsKey("adjustment"));

        call(post("/api/sessions/" + sid + "/void"), "{\"reason\":\"Customer disputed\"}", 200);
        AuditEvent voided = only(AuditActions.ENTITY_SESSION, sid, AuditActions.BILL_VOIDED);
        assertEquals("Customer disputed", voided.getReason());
        assertTrue(voided.getDescription().startsWith("Voided Bill " + billNo));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void failedCardPaymentRollsBackTogetherWithItsAuditEntries() throws Exception {
        String cardId = card(plan(5_000L)); // only ₹50 on the card
        String started = call(post("/api/sessions"), """
                {"customer_name":"Ravi","resource_id":"%s","player_count":1,"start_time":"2026-12-02T10:00:00","membership_card_id":"%s"}
                """.formatted(resource(), cardId), 200);
        String sid = JsonPath.read(started, "$.id");

        call(post("/api/sessions/" + sid + "/checkout"), """
                {"adjustment":0,"payments":[],"membership_amount_paise":10000,"manual_billed_at":"2026-12-02T11:00:00"}
                """, 409);

        List<AuditEvent> bill = trail(AuditActions.ENTITY_SESSION, sid);
        assertTrue(bill.stream().noneMatch(e -> e.getOutcome() == AuditEvent.Outcome.SUCCESS
                && (e.getAction().startsWith("BILL_") || e.getAction().equals(AuditActions.PAYMENT_RECORDED))),
                "no success entries may survive a rolled-back checkout");
        assertTrue(trail(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId).stream().noneMatch(e -> e.getAction().equals(AuditActions.CARD_DEBITED)));
        AuditEvent failure = bill.stream().filter(e -> e.getOutcome() == AuditEvent.Outcome.FAILURE).findFirst().orElseThrow();
        assertEquals(AuditActions.BILL_CREATED, failure.getAction());
        assertTrue(failure.getReason().contains("has only ₹50"), failure.getReason());
    }

    // ---------------- setup: only changed fields ----------------

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void editsRecordOnlyTheChangedFields() throws Exception {
        String resId = resource();
        only(AuditActions.ENTITY_RESOURCE, resId, AuditActions.RESOURCE_CREATED);
        String otherCard = rateCard();
        String body = call(put("/api/resources/" + resId), """
                {"rate_card_id":"%s"}
                """.formatted(otherCard), 200);
        assertNotNull(body);
        AuditEvent updated = only(AuditActions.ENTITY_RESOURCE, resId, AuditActions.RESOURCE_UPDATED);
        assertEquals(Set.of("rate_card_id"), updated.getChanges().keySet());
        assertEquals(Map.of("before", rateCardId, "after", otherCard), updated.getChanges().get("rate_card_id"));

        String planId = plan(100_000L);
        call(put("/api/membership/plans/" + planId), "{\"validity_days\":60}", 200);
        AuditEvent planEdit = only(AuditActions.ENTITY_MEMBERSHIP_PLAN, planId, AuditActions.MEMBERSHIP_PLAN_UPDATED);
        assertEquals(Set.of("validity_days"), planEdit.getChanges().keySet());
        assertEquals(Map.of("before", 30, "after", 60), planEdit.getChanges().get("validity_days"));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void staffChangesAreAuditedWithoutEverStoringPasswords() throws Exception {
        String email = "new.staff." + UUID.randomUUID() + "@test.local";
        String created = call(post("/api/staff"), """
                {"email":"%s","password":"S3cret!Pass","name":"Neha","permissions":["sessions"]}
                """.formatted(email), 200);
        String uid = JsonPath.read(created, "$.id");
        only(AuditActions.ENTITY_USER, uid, AuditActions.STAFF_CREATED);

        call(put("/api/staff/" + uid), """
                {"name":"Neha","email":"%s","password":"An0ther!Secret","permissions":["sessions","billing"],"is_active":true}
                """.formatted(email), 200);
        AuditEvent upd = only(AuditActions.ENTITY_USER, uid, AuditActions.STAFF_UPDATED);
        assertEquals(Set.of("permissions"), upd.getChanges().keySet());
        assertEquals(Boolean.TRUE, upd.getDetails().get("login_reset"));

        call(put("/api/staff/" + uid), """
                {"name":"Neha","email":"%s","permissions":["sessions","billing"],"is_active":false}
                """.formatted(email), 200);
        only(AuditActions.ENTITY_USER, uid, AuditActions.STAFF_DEACTIVATED);

        String everything = trail(AuditActions.ENTITY_USER, uid).toString()
                + trail(AuditActions.ENTITY_USER, uid).stream().map(e -> String.valueOf(e.getChanges()) + e.getDetails() + e.getDescription()).toList();
        assertFalse(everything.contains("S3cret!Pass") || everything.contains("An0ther!Secret") || everything.contains("$2a$"),
                "no password or password hash may be stored");
    }

    // ---------------- auth + security ----------------

    @Test
    void loginsFailuresAndLogoutAreAudited() throws Exception {
        String uid = users.findByEmail(OPERATOR).orElseThrow().getId();
        call(post("/api/auth/login"), "{\"email\":\"" + OPERATOR + "\",\"password\":\"wrong\"}", 401);
        call(post("/api/auth/login"), "{\"email\":\"nobody@test.local\",\"password\":\"Correct#Horse1\"}", 401);
        call(post("/api/auth/login"), "{\"email\":\"" + OPERATOR + "\",\"password\":\"Correct#Horse1\"}", 200);

        List<AuditEvent> mine = trail(AuditActions.ENTITY_USER, uid);
        AuditEvent wrong = mine.stream().filter(e -> e.getAction().equals(AuditActions.LOGIN_FAILED)).findFirst().orElseThrow();
        assertEquals(AuditEvent.Outcome.FAILURE, wrong.getOutcome());
        assertEquals("Wrong password", wrong.getReason());
        assertEquals("Omar Operator", wrong.getActorName());
        AuditEvent ok = mine.stream().filter(e -> e.getAction().equals(AuditActions.LOGIN_SUCCEEDED)).findFirst().orElseThrow();
        assertEquals(CAFE, ok.getCafeId());
        assertEquals("127.0.0.1", ok.getIpAddress());

        assertTrue(events.findByActionOrderByIdDesc(AuditActions.LOGIN_FAILED).stream()
                .anyMatch(e -> "nobody@test.local".equals(e.getActorName()) && "Unknown email".equals(e.getReason())));
        String stored = mine.stream().map(e -> e.getDetails() + "" + e.getDescription()).toList().toString();
        assertFalse(stored.contains("Correct#Horse1") || stored.contains("wrong"), "the typed password is never stored");
    }

    @Test
    @WithMockUser(username = OPERATOR, roles = "OPERATOR")
    void logoutAndAccessDeniedAreAudited() throws Exception {
        call(post("/api/auth/logout"), null, 200);
        String uid = users.findByEmail(OPERATOR).orElseThrow().getId();
        assertTrue(trail(AuditActions.ENTITY_USER, uid).stream().anyMatch(e -> e.getAction().equals(AuditActions.LOGOUT)));

        call(post("/api/membership/plans"), "{\"name\":\"X\",\"load_amount_paise\":100,\"validity_days\":1}", 403);
        AuditEvent denied = events.findByActionOrderByIdDesc(AuditActions.ACCESS_DENIED).getFirst();
        assertEquals("Omar Operator", denied.getActorName());
        assertEquals(AuditEvent.Outcome.FAILURE, denied.getOutcome());
        assertEquals("/api/membership/plans", denied.getDetails().get("path"));
        assertTrue(denied.getOccurredAtUtc().endsWith("Z"));
    }

    // ---------------- memberships ----------------

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void cardActionsAreAuditedAndExpiryIsSystem() throws Exception {
        String cardId = card(plan(100_000L));
        AuditEvent issued = only(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId, AuditActions.CARD_ISSUED);
        assertTrue(issued.getDescription().contains("paid ₹1,000.00 by CASH ₹1,000.00"), issued.getDescription());

        call(post("/api/membership/cards/" + cardId + "/adjust"), "{\"direction\":\"CREDIT\",\"bonus_paise\":5000,\"reason\":\"Console fault\"}", 200);
        AuditEvent adj = only(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId, AuditActions.CARD_ADJUSTED);
        assertEquals("Console fault", adj.getReason());
        assertEquals(Map.of("before", 0, "after", 5000), adj.getChanges().get("bonus_balance_paise"));

        clock.plusDays(40);
        cardService.expireDueCards();
        AuditEvent expired = only(AuditActions.ENTITY_MEMBERSHIP_CARD, cardId, AuditActions.CARD_EXPIRED);
        assertEquals(AuditEvent.ActorType.SYSTEM, expired.getActorType());
        assertEquals("SYSTEM", expired.getActorName());
        assertEquals(CAFE, expired.getCafeId());
        assertEquals(105_000, ((Number) expired.getDetails().get("forfeited_paise")).intValue());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void settlingAPendingDebtIsAudited() throws Exception {
        String started = call(post("/api/sessions"), """
                {"customer_name":"Kabir","resource_id":"%s","player_count":1,"start_time":"2026-12-02T10:00:00"}
                """.formatted(resource()), 200);
        String sid = JsonPath.read(started, "$.id");
        call(post("/api/sessions/" + sid + "/checkout"), """
                {"adjustment":0,"payments":[{"mode":"cash","amount":60},{"mode":"pending","amount":40}],"manual_billed_at":"2026-12-02T11:00:00"}
                """, 200);
        PendingPayment debt = pending.findBySessionId(sid).getFirst();

        call(post("/api/pending-payments/" + debt.getId() + "/clear"), "{\"payments\":[{\"mode\":\"upi\",\"amount\":40}]}", 200);
        AuditEvent settled = only(AuditActions.ENTITY_PENDING_PAYMENT, String.valueOf(debt.getId()), AuditActions.PENDING_PAYMENT_SETTLED);
        assertTrue(settled.getDescription().startsWith("Collected ₹40.00 of ₹40.00 pending from Kabir"), settled.getDescription());
        assertEquals(sid, settled.getDetails().get("session_id"));
    }
}
