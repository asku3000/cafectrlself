package com.progameflixx.cafectrl.audit;

import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.membership.TestClockConfig;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import com.progameflixx.cafectrl.service.MembershipCardService;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit purge. "Now" is 15 Mar 2027 03:00 IST (= 14 Mar 21:30 UTC); with 5-day retention the cutoff is
 * 10 Mar 2027 00:00 IST = 09 Mar 2027 18:30 UTC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AuditPurgeTest {

    static final Instant NOW = Instant.parse("2027-03-14T21:30:00Z");
    static final LocalDateTime CUTOFF_UTC = LocalDateTime.of(2027, 3, 9, 18, 30);
    static final String ADMIN = "purge.admin@test.local";
    static final String OPERATOR = "purge.op@test.local";

    @Autowired AuditPurgeService purge;
    @Autowired AuditService audit;
    @Autowired AuditEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClockConfig.MutableClock clock;
    @Autowired UserRepository users;
    @Autowired CustomerSessionRepository sessions;
    @Autowired MembershipPlanService plans;
    @Autowired MembershipCardService cards;
    @Autowired MockMvc mvc;

    String cafeA;
    String cafeB;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        cafeA = "purge-a-" + UUID.randomUUID();
        cafeB = "purge-b-" + UUID.randomUUID();
        jdbc.update("UPDATE job_locks SET locked_until = NULL, locked_by = NULL WHERE name = 'audit_purge'");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE job_locks SET locked_until = NULL, locked_by = NULL WHERE name = 'audit_purge'");
        clock.setDate(LocalDate.now());
    }

    /** Inserts an audit row at an exact UTC time; returns the marker (entity id) to find it again. */
    String row(String cafe, LocalDateTime occurredUtc) {
        String marker = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO audit_events (occurred_at, cafe_id, actor_type, actor_name, action, entity_type, entity_id, outcome) "
                + "VALUES (?, ?, 'USER', 'Seeder', 'TEST_ACTION', 'TEST', ?, 'SUCCESS')", occurredUtc, cafe, marker);
        return marker;
    }

    boolean exists(String marker) {
        return !events.findByEntityTypeAndEntityIdOrderByIdDesc("TEST", marker).isEmpty();
    }

    AuditEvent lastSystemEntry(String action, String cafe) {
        return events.findByActionOrderByIdDesc(action).stream()
                .filter(e -> Objects.equals(e.getCafeId(), cafe)).findFirst().orElseThrow();
    }

    // ---------------- cutoff ----------------

    @Test
    void cutoffKeepsWholeIstDays() {
        assertEquals(LocalDateTime.of(2027, 3, 10, 0, 0), purge.cutoffIst());
        assertEquals(CUTOFF_UTC, AuditPurgeService.toUtc(purge.cutoffIst()));

        // 02:00 IST on the 15th is still the 15th in India although it is the 14th in UTC
        clock.setInstant(Instant.parse("2027-03-14T20:30:00Z"));
        assertEquals(LocalDateTime.of(2027, 3, 10, 0, 0), purge.cutoffIst());
        // 23:59 IST on the 14th: one day earlier
        clock.setInstant(Instant.parse("2027-03-14T18:29:00Z"));
        assertEquals(LocalDateTime.of(2027, 3, 9, 0, 0), purge.cutoffIst());
    }

    @Test
    void deletesOnlyRowsOlderThanTheCutoffForTheCafe() {
        String veryOld = row(cafeA, LocalDateTime.of(2027, 3, 1, 10, 0));
        String justBefore = row(cafeA, CUTOFF_UTC.minusSeconds(1));   // 09 Mar 23:59:59 IST
        String atCutoff = row(cafeA, CUTOFF_UTC);                     // 10 Mar 00:00:00 IST: kept
        String recent = row(cafeA, LocalDateTime.of(2027, 3, 14, 10, 0));
        String otherCafeOld = row(cafeB, LocalDateTime.of(2027, 3, 1, 10, 0));

        AuditPurgeService.Result r = purge.purge(cafeA, AuditPurgeService.Trigger.MANUAL, null);

        assertTrue(r.success());
        assertEquals(2, r.deleted());
        assertFalse(exists(veryOld));
        assertFalse(exists(justBefore));
        assertTrue(exists(atCutoff));
        assertTrue(exists(recent));
        assertTrue(exists(otherCafeOld), "a cafe-scoped purge never touches another cafe's entries");

        AuditEvent sys = lastSystemEntry(AuditActions.AUDIT_PURGE_RUN, cafeA);
        assertEquals(AuditEvent.ActorType.SYSTEM, sys.getActorType());
        assertEquals(AuditEvent.Outcome.SUCCESS, sys.getOutcome());
        assertEquals(2, ((Number) sys.getDetails().get("deleted")).intValue());
        assertEquals("2027-03-10T00:00", sys.getDetails().get("cutoff_ist"));
        assertEquals("2027-03-09T18:30", sys.getDetails().get("cutoff_utc"));
        assertEquals(5, ((Number) sys.getDetails().get("retention_days")).intValue());
        assertNotNull(sys.getDetails().get("duration_ms"));
        assertTrue(sys.getDescription().startsWith("Deleted 2 audit entries older than 10 Mar 2027, 00:00 IST"), sys.getDescription());

        // running again is harmless
        assertEquals(0, purge.purge(cafeA, AuditPurgeService.Trigger.MANUAL, null).deleted());
        assertTrue(exists(atCutoff));
    }

    @Test
    void fullPurgeCoversEveryCafe() {
        String a = row(cafeA, LocalDateTime.of(2027, 3, 2, 0, 0));
        String b = row(cafeB, LocalDateTime.of(2027, 3, 2, 0, 0));
        String keep = row(cafeB, LocalDateTime.of(2027, 3, 13, 0, 0));

        AuditPurgeService.Result r = purge.purge(null, AuditPurgeService.Trigger.SCHEDULED, null);

        assertTrue(r.success());
        assertFalse(exists(a));
        assertFalse(exists(b));
        assertTrue(exists(keep));
        AuditEvent sys = lastSystemEntry(AuditActions.AUDIT_PURGE_RUN, null);
        assertEquals("SCHEDULED", sys.getDetails().get("trigger"));
        assertEquals("ALL_CAFES", sys.getDetails().get("scope"));
        assertNotNull(purge.lastSuccessfulFullPurgeUtc());
    }

    @Test
    void dryRunCountsButDeletesNothing() {
        String old1 = row(cafeA, LocalDateTime.of(2027, 3, 1, 0, 0));
        String old2 = row(cafeA, LocalDateTime.of(2027, 3, 5, 0, 0));
        row(cafeA, LocalDateTime.of(2027, 3, 12, 0, 0));
        User admin = new User();
        admin.setId("u-1");
        admin.setName("Owner");

        AuditPurgeService.Result r = purge.dryRun(cafeA, AuditPurgeService.Trigger.MANUAL, admin);

        assertTrue(r.dryRun());
        assertEquals(2, r.wouldDelete());
        assertEquals(0, r.deleted());
        assertTrue(exists(old1));
        assertTrue(exists(old2));
        AuditEvent sys = lastSystemEntry(AuditActions.AUDIT_PURGE_DRY_RUN, cafeA);
        assertEquals(AuditEvent.ActorType.SYSTEM, sys.getActorType());
        assertEquals(2, ((Number) sys.getDetails().get("would_delete")).intValue());
        assertEquals("Owner", sys.getDetails().get("requested_by"));
    }

    // ---------------- never touches other data ----------------

    static final List<String> OTHER_TABLES = List.of("customer_sessions", "game_sessions", "game_session_items",
            "pending_payments", "membership_plans", "membership_cards", "membership_ledger", "membership_settings",
            "users", "rate_cards", "resources", "audit_logs", "job_locks");

    Map<String, Long> counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : OTHER_TABLES) m.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class));
        return m;
    }

    @Test
    void purgeNeverTouchesBillsSessionsPaymentsCardsOrTheLedger() {
        // old business data that a careless purge could hit: dated weeks before the cutoff
        clock.setInstant(Instant.parse("2027-02-01T06:30:00Z"));
        User admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setCafeId(cafeA);
        admin.setName("Owner");
        MembershipPlanRequest p = new MembershipPlanRequest();
        p.setName("Old plan");
        p.setLoadAmountPaise(10_000L);
        p.setValidityDays(10);
        MembershipCardRequests.Issue issue = new MembershipCardRequests.Issue();
        issue.setCustomerName("Old customer");
        issue.setCustomerPhone("9000000099");
        issue.setPlanId(plans.create(admin, p).getId());
        issue.setPaymentMode("cash");
        cards.issue(admin, issue);
        CustomerSession s = new CustomerSession();
        s.setCafeId(cafeA);
        s.setCustomerName("Old visit");
        s.setStatus("billed");
        s.setCreatedAt(LocalDateTime.of(2027, 2, 1, 12, 0));
        s.setBilledAt(LocalDateTime.of(2027, 2, 1, 13, 0));
        sessions.save(s);
        jdbc.update("INSERT INTO audit_logs (id, cafe_id, action, created_at) VALUES (?, ?, 'OLD_LEGACY', ?)",
                UUID.randomUUID().toString(), cafeA, LocalDateTime.of(2020, 1, 1, 0, 0));
        clock.setInstant(NOW);
        row(cafeA, LocalDateTime.of(2027, 2, 1, 0, 0));

        Map<String, Long> before = counts();
        purge.purge(null, AuditPurgeService.Trigger.MANUAL, null);
        assertEquals(before, counts(), "only audit_events may change");
        assertTrue(cards.list(cafeA, (String) null).stream().anyMatch(c -> c.customerName().equals("Old customer")));
    }

    // ---------------- batching, lock, overlap ----------------

    @Test
    void deletesInBatches() {
        AuditPurgeService small = new AuditPurgeService(jdbc, audit, clock, 5, 3, 0);
        List<String> markers = new ArrayList<>();
        for (int i = 0; i < 10; i++) markers.add(row(cafeA, LocalDateTime.of(2027, 3, 1, 0, i)));

        AuditPurgeService.Result r = small.purge(cafeA, AuditPurgeService.Trigger.MANUAL, null);

        assertEquals(10, r.deleted());
        assertEquals(4, r.batches());
        assertTrue(markers.stream().noneMatch(this::exists));
    }

    @Test
    void aHeldLockMakesARunSkipAndAnExpiredLockIsTakenOver() {
        String old = row(cafeA, LocalDateTime.of(2027, 3, 1, 0, 0));
        jdbc.update("UPDATE job_locks SET locked_until = ?, locked_by = 'other-run' WHERE name = 'audit_purge'",
                LocalDateTime.of(2027, 3, 14, 22, 0)); // still valid at NOW

        AuditPurgeService.Result skipped = purge.purge(cafeA, AuditPurgeService.Trigger.MANUAL, null);
        assertTrue(skipped.skipped());
        assertFalse(skipped.success());
        assertTrue(exists(old));
        AuditEvent sys = lastSystemEntry(AuditActions.AUDIT_PURGE_RUN, cafeA);
        assertEquals(AuditEvent.Outcome.FAILURE, sys.getOutcome());
        assertEquals("Another purge is already running", sys.getReason());

        // a crashed run's lock expires
        jdbc.update("UPDATE job_locks SET locked_until = ? WHERE name = 'audit_purge'", LocalDateTime.of(2027, 3, 14, 20, 0));
        AuditPurgeService.Result ok = purge.purge(cafeA, AuditPurgeService.Trigger.MANUAL, null);
        assertTrue(ok.success());
        assertFalse(exists(old));
        assertNull(jdbc.queryForObject("SELECT locked_by FROM job_locks WHERE name = 'audit_purge'", String.class), "lock released");
    }

    @Test
    void overlappingRunsNeverDoubleProcess() throws Exception {
        AuditPurgeService slow = new AuditPurgeService(jdbc, audit, clock, 5, 2, 30);
        for (int i = 0; i < 12; i++) row(cafeA, LocalDateTime.of(2027, 3, 1, 1, i));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<AuditPurgeService.Result>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> { go.await(); return slow.purge(cafeA, AuditPurgeService.Trigger.MANUAL, null); }));
        go.countDown();
        List<AuditPurgeService.Result> rs = new ArrayList<>();
        for (Future<AuditPurgeService.Result> f : fs) rs.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(12, rs.stream().mapToLong(AuditPurgeService.Result::deleted).sum());
        assertTrue(rs.stream().allMatch(r -> r.success() || r.skipped()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE cafe_id = ? AND action = 'TEST_ACTION'", Long.class, cafeA));
    }

    // ---------------- HTTP ----------------

    void user(String email, String role) {
        User u = users.findByEmail(email).orElseGet(User::new);
        u.setEmail(email);
        u.setName(email);
        u.setRole(role);
        u.setCafeId(cafeA);
        u.setActive(true);
        u.setPermissions(new ArrayList<>(List.of("sessions", "reports")));
        users.save(u);
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void adminCanDryRunAndPurgeOnlyTheirOwnCafe() throws Exception {
        user(ADMIN, "CAFE_ADMIN");
        String mine = row(cafeA, LocalDateTime.of(2027, 3, 1, 0, 0));
        String theirs = row(cafeB, LocalDateTime.of(2027, 3, 1, 0, 0));

        mvc.perform(get("/api/audit-log/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retention_days").value(5))
                .andExpect(jsonPath("$.cutoff_ist").value("2027-03-10T00:00"))
                .andExpect(jsonPath("$.scope").value("CAFE"));
        mvc.perform(post("/api/audit-log/purge").param("dry_run", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.would_delete").value(1))
                .andExpect(jsonPath("$.deleted").value(0));
        assertTrue(exists(mine));

        mvc.perform(post("/api/audit-log/purge").param("dry_run", "false").param("cafe_id", cafeB)) // cafe_id ignored for cafe admins
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
        assertFalse(exists(mine));
        assertTrue(exists(theirs));
    }

    @Test
    @WithMockUser(username = OPERATOR, roles = "OPERATOR")
    void operatorsCannotPurge() throws Exception {
        user(OPERATOR, "OPERATOR");
        mvc.perform(post("/api/audit-log/purge").param("dry_run", "false")).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-log/purge")).andExpect(status().isForbidden());
    }
}
