package com.progameflixx.cafectrl.audit;

import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.GameType;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.logging.LogContext;
import com.progameflixx.cafectrl.membership.TestClockConfig;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import com.progameflixx.cafectrl.repository.GameTypeRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The central audit service: transactional semantics, actor / request capture, UTC time, masking, diffs. */
@SpringBootTest
@Import(TestClockConfig.class)
class AuditServiceTest {

    @Autowired AuditService audit;
    @Autowired AuditEventRepository events;
    @Autowired GameTypeRepository gameTypes;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;
    @Autowired TestClockConfig.MutableClock clock;

    String cafeId;
    String marker; // unique entity id per test, so we only look at our own rows

    @BeforeEach
    void setUp() {
        clock.setDate(LocalDate.of(2027, 3, 10)); // 12:00 IST = 06:30 UTC
        cafeId = "cafe-" + UUID.randomUUID();
        marker = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    List<AuditEvent> mine() {
        return events.findByEntityTypeAndEntityIdOrderByIdDesc("TEST", marker);
    }

    AuditRecord rec(String action) {
        return AuditRecord.of(action).entity("TEST", marker).cafe(cafeId);
    }

    GameType gameType(String name) {
        GameType g = new GameType();
        g.setCafeId(cafeId);
        g.setName(name);
        return g;
    }

    // ---------------- money actions: same transaction ----------------

    @Test
    void inTransactionEntryCommitsWithTheChange() {
        tx.executeWithoutResult(t -> {
            gameTypes.save(gameType("Pool"));
            audit.recordInTransaction(rec("TEST_CHANGE").description("added pool"));
        });
        assertEquals(1, mine().size());
        assertTrue(gameTypes.findByCafeId(cafeId).stream().anyMatch(g -> g.getName().equals("Pool")));
    }

    @Test
    void inTransactionEntryRollsBackWithTheChange() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(t -> {
            gameTypes.save(gameType("Snooker"));
            audit.recordInTransaction(rec("TEST_CHANGE"));
            throw new IllegalStateException("payment failed");
        }));
        assertTrue(mine().isEmpty(), "audit entry must roll back with the change");
        assertTrue(gameTypes.findByCafeId(cafeId).isEmpty(), "change must roll back too");
    }

    @Test
    void changeRollsBackWhenItsAuditEntryCannotBeWritten() {
        // action is NOT NULL in the table: the audit insert fails, so the money change must not survive either
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(t -> {
            gameTypes.save(gameType("Air hockey"));
            audit.recordInTransaction(AuditRecord.of(null).entity("TEST", marker));
            gameTypes.flush();
        }));
        assertTrue(gameTypes.findByCafeId(cafeId).isEmpty());
    }

    @Test
    void inTransactionRequiresATransaction() {
        assertThrows(IllegalStateException.class, () -> audit.recordInTransaction(rec("TEST_CHANGE")));
    }

    // ---------------- best effort ----------------

    @Test
    void bestEffortIsWrittenOnlyAfterCommit() {
        tx.executeWithoutResult(t -> {
            audit.record(rec("TEST_NOTE"));
            assertTrue(mine().isEmpty(), "not visible before the caller commits");
        });
        assertEquals(1, mine().size());

        String other = marker;
        marker = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(t -> {
            audit.record(rec("TEST_NOTE"));
            throw new IllegalStateException("boom");
        }));
        assertTrue(mine().isEmpty(), "rolled-back work is not logged as a success");
        marker = other;
    }

    @Test
    void bestEffortWithoutTransactionIsWrittenImmediately() {
        audit.record(rec("TEST_NOTE"));
        assertEquals(1, mine().size());
    }

    @Test
    void bestEffortErrorsAreSwallowedAndTheMainActionCompletes() {
        tx.executeWithoutResult(t -> {
            gameTypes.save(gameType("Foosball"));
            audit.record(AuditRecord.of(null).entity("TEST", marker)); // will fail to insert
        });
        assertTrue(gameTypes.findByCafeId(cafeId).stream().anyMatch(g -> g.getName().equals("Foosball")));
        assertTrue(mine().isEmpty());
    }

    @Test
    void failureEntrySurvivesTheRollback() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(t -> {
            gameTypes.save(gameType("Darts"));
            audit.recordFailure(rec("TEST_CHANGE"), "Insufficient card balance");
            throw new IllegalStateException("rejected");
        }));
        List<AuditEvent> got = mine();
        assertEquals(1, got.size());
        assertEquals(AuditEvent.Outcome.FAILURE, got.getFirst().getOutcome());
        assertEquals("Insufficient card balance", got.getFirst().getReason());
        assertTrue(gameTypes.findByCafeId(cafeId).isEmpty());
    }

    // ---------------- time, actor, request ----------------

    @Test
    void timestampIsStoredInUtcAndSerialisedWithZ() {
        audit.record(rec("TEST_NOTE"));
        AuditEvent e = mine().getFirst();
        assertEquals(java.time.Instant.parse("2027-03-10T06:30:00Z"), e.getOccurredAt());
        assertEquals("2027-03-10T06:30:00Z", e.getOccurredAtUtc());
    }

    @Test
    void actorIsSnapshotFromTheLoggedInUserAndRequestInfoIsCaptured() {
        User u = new User();
        u.setEmail("auditor-" + marker + "@test.local");
        u.setName("Priya");
        u.setRole("OPERATOR");
        u.setCafeId(cafeId);
        u.setActive(true);
        u.setPermissions(new ArrayList<>());
        users.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                u.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        req.addHeader("User-Agent", "Mozilla/5.0 (Android) Chrome");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        MDC.put(LogContext.REQUEST_ID, "req-abc123");

        audit.record(AuditRecord.of("TEST_NOTE").entity("TEST", marker));

        AuditEvent e = mine().getFirst();
        assertEquals(AuditEvent.ActorType.USER, e.getActorType());
        assertEquals(u.getId(), e.getActorUserId());
        assertEquals("Priya", e.getActorName());
        assertEquals("OPERATOR", e.getActorRole());
        assertEquals(cafeId, e.getCafeId());
        assertEquals("203.0.113.7", e.getIpAddress());
        assertEquals("Mozilla/5.0 (Android) Chrome", e.getUserAgent());
        assertEquals("req-abc123", e.getRequestId());

        // The snapshot stays even if the user is renamed later
        u.setName("Priya S.");
        users.save(u);
        assertEquals("Priya", mine().getFirst().getActorName());
    }

    @Test
    void systemAndAnonymousActors() {
        audit.record(rec("TEST_JOB").system());
        AuditEvent sys = mine().getFirst();
        assertEquals(AuditEvent.ActorType.SYSTEM, sys.getActorType());
        assertEquals("SYSTEM", sys.getActorName());
        assertNull(sys.getActorUserId());

        marker = UUID.randomUUID().toString();
        audit.recordFailure(rec("LOGIN_FAILED").anonymous("someone@test.local"), "Invalid email or password");
        AuditEvent anon = mine().getFirst();
        assertEquals("someone@test.local", anon.getActorName());
        assertEquals(AuditEvent.Outcome.FAILURE, anon.getOutcome());
    }

    // ---------------- secrets and diffs ----------------

    @Test
    void secretsAreMaskedEverywhere() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("apiKey", "k-123");
        nested.put("note", "fine");
        audit.record(rec("TEST_NOTE")
                .detail("password", "hunter2")
                .detail("new_password", "hunter3")
                .detail("access_token", "abc")
                .detail("otp", "4321")
                .detail("nested", nested)
                .detail("raw", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig")
                .detail("customer", "Ravi")
                .diff(Map.of("password", "old-hash", "name", "A"), Map.of("password", "new-hash", "name", "B")));

        AuditEvent e = mine().getFirst();
        Map<String, Object> d = e.getDetails();
        assertEquals("***", d.get("password"));
        assertEquals("***", d.get("new_password"));
        assertEquals("***", d.get("access_token"));
        assertEquals("***", d.get("otp"));
        assertEquals("***", ((Map<?, ?>) d.get("nested")).get("apiKey"));
        assertEquals("fine", ((Map<?, ?>) d.get("nested")).get("note"));
        assertEquals("***", d.get("raw"));
        assertEquals("Ravi", d.get("customer"));
        assertEquals("***", e.getChanges().get("password"));       // recorded as changed, value hidden
        assertEquals(Map.of("before", "A", "after", "B"), e.getChanges().get("name"));
        String stored = String.valueOf(e.getDetails()) + e.getChanges();
        assertFalse(stored.contains("hunter2") || stored.contains("old-hash") || stored.contains("k-123"));
    }

    @Test
    void diffKeepsOnlyChangedFields() {
        Map<String, Object> before = AuditDiff.snapshot("name", "PS5 #1", "price", 100, "rate_card", "rc-1", "active", true);
        Map<String, Object> after = AuditDiff.snapshot("name", "PS5 #1", "price", 100.0, "rate_card", "rc-2", "active", false);
        Map<String, Object> diff = AuditDiff.between(before, after);
        assertEquals(Set.of("rate_card", "active"), diff.keySet());
        assertEquals(Map.of("before", "rc-1", "after", "rc-2"), diff.get("rate_card"));
        assertTrue(AuditDiff.between(before, before).isEmpty());

        Map<String, Object> added = AuditDiff.between(Map.of(), Map.of("name", "Gold"));
        Map<?, ?> pair = (Map<?, ?>) added.get("name");
        assertNull(pair.get("before"));
        assertEquals("Gold", pair.get("after"));
    }

    @Test
    void objectsInDetailsAreStoredAsJson() {
        audit.record(rec("TEST_NOTE").detail("when", LocalDate.of(2027, 3, 10))
                .detail("payments", List.of(Map.of("mode", "upi", "amount_paise", 60000))));
        Map<String, Object> d = mine().getFirst().getDetails();
        assertEquals("2027-03-10", d.get("when"));
        assertEquals(60000, ((Number) ((Map<?, ?>) ((List<?>) d.get("payments")).getFirst()).get("amount_paise")).intValue());
    }

    // ---------------- append-only ----------------

    @Test
    void entriesCannotBeUpdatedThroughTheApp() {
        audit.record(rec("TEST_NOTE").description("original"));
        AuditEvent e = mine().getFirst();
        tx.executeWithoutResult(t -> {
            AuditEvent managed = events.findById(e.getId()).orElseThrow();
            managed.setDescription("tampered");
            events.save(managed);
        });
        assertEquals("original", events.findById(e.getId()).orElseThrow().getDescription());
    }
}
