package com.progameflixx.cafectrl.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import com.progameflixx.cafectrl.repository.RateCardRepository;
import com.progameflixx.cafectrl.repository.ResourceRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import com.progameflixx.cafectrl.testsupport.TestIdempotencyKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Setup screens (resources, rate cards, staff, inventory, plans): keys + row locks make every write happen once. */
@SpringBootTest
@AutoConfigureMockMvc
class SetupIdempotencyTest {

    static final String CAFE = "cafe-setup-idem";
    static final String ADMIN = "setup.idem@test.local";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RateCardRepository rateCards;
    @Autowired ResourceRepository resources;
    @Autowired AuditEventRepository audit;

    final RequestPostProcessor asAdmin = user(ADMIN).roles("CAFE_ADMIN");

    @BeforeEach
    void admin() {
        User u = users.findByEmail(ADMIN).orElseGet(User::new);
        u.setEmail(ADMIN);
        u.setName("Sia Admin");
        u.setRole("CAFE_ADMIN");
        u.setCafeId(CAFE);
        u.setActive(true);
        u.setPermissions(new ArrayList<>());
        users.save(u);
    }

    MvcResult send(MockHttpServletRequestBuilder req, String key, String json) throws Exception {
        req.with(asAdmin);
        if (key != null) req.header("Idempotency-Key", key);
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    static String key() { return "key-" + UUID.randomUUID(); }

    String newResource() throws Exception {
        MvcResult r = send(post("/api/resources"), key(), "{\"name\":\"PS5-" + UUID.randomUUID().toString().substring(0, 5) + "\"}");
        assertEquals(200, r.getResponse().getStatus());
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    /** Successful entries only: a rejected attempt (e.g. the second delete's 404) is rightly audited as a FAILURE. */
    long audits(String action, String entityType, String id) {
        return audit.findByEntityTypeAndEntityIdOrderByIdDesc(entityType, id).stream()
                .filter(e -> e.getOutcome() == AuditEvent.Outcome.SUCCESS)
                .map(AuditEvent::getAction).filter(action::equals).count();
    }

    long failedAudits(String action, String entityType, String id) {
        return audit.findByEntityTypeAndEntityIdOrderByIdDesc(entityType, id).stream()
                .filter(e -> e.getOutcome() == AuditEvent.Outcome.FAILURE)
                .map(AuditEvent::getAction).filter(action::equals).count();
    }

    @Test
    void aRepeatedDeleteWithTheSameKeyIsReplayedNotRedone() throws Exception {
        String res = newResource();
        String k = key();
        MvcResult first = send(delete("/api/resources/" + res), k, null);
        MvcResult again = send(delete("/api/resources/" + res), k, null);
        assertEquals(200, first.getResponse().getStatus());
        assertEquals(200, again.getResponse().getStatus());
        assertEquals("true", again.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(0, failedAudits(AuditActions.RESOURCE_DELETED, AuditActions.ENTITY_RESOURCE, res)); // a replay writes nothing
        assertFalse(resources.existsById(res));
        assertEquals(1, audits(AuditActions.RESOURCE_DELETED, AuditActions.ENTITY_RESOURCE, res));
    }

    @Test
    void deletingTwiceWithDifferentKeysGives404AndOneAuditEntry() throws Exception {
        String res = newResource();
        assertEquals(200, send(delete("/api/resources/" + res), key(), null).getResponse().getStatus());
        assertEquals(404, send(delete("/api/resources/" + res), key(), null).getResponse().getStatus());
        assertEquals(1, audits(AuditActions.RESOURCE_DELETED, AuditActions.ENTITY_RESOURCE, res));
        assertEquals(1, failedAudits(AuditActions.RESOURCE_DELETED, AuditActions.ENTITY_RESOURCE, res)); // the refused repeat
    }

    @Test
    void twoDevicesDeletingAtTheSameMomentDeleteOnce() throws Exception {
        String res = newResource();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(pool.submit(() -> { go.await(); return send(delete("/api/resources/" + res), key(), null).getResponse().getStatus(); }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : calls) codes.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        Collections.sort(codes);
        assertEquals(List.of(200, 404), codes);
        assertEquals(1, audits(AuditActions.RESOURCE_DELETED, AuditActions.ENTITY_RESOURCE, res));
    }

    @Test
    void aDoubleSubmittedCreateMakesOneRecord() throws Exception {
        String name = "Weekend " + UUID.randomUUID();
        String k = key();
        String body = "{\"name\":\"" + name + "\",\"billing_interval_minutes\":30}";
        String id1 = JsonPath.read(send(post("/api/rate-cards"), k, body).getResponse().getContentAsString(), "$.id");
        String id2 = JsonPath.read(send(post("/api/rate-cards"), k, body).getResponse().getContentAsString(), "$.id");
        assertEquals(id1, id2);
        assertEquals(1, rateCards.findByCafeId(CAFE).stream().filter(rc -> name.equals(rc.getName())).count());
        assertEquals(1, audits(AuditActions.RATE_CARD_CREATED, AuditActions.ENTITY_RATE_CARD, id1));
    }

    @Test
    void anEditReplayedWithTheSameKeyIsAuditedOnce() throws Exception {
        String res = newResource();
        String k = key();
        assertEquals(200, send(put("/api/resources/" + res), k, "{\"name\":\"Renamed\"}").getResponse().getStatus());
        assertEquals(200, send(put("/api/resources/" + res), k, "{\"name\":\"Renamed\"}").getResponse().getStatus());
        assertEquals(1, audits(AuditActions.RESOURCE_UPDATED, AuditActions.ENTITY_RESOURCE, res));
    }

    @Test
    void setupWritesWithoutAKeyAreRefused() throws Exception {
        String res = newResource();
        for (MockHttpServletRequestBuilder req : List.of(
                delete("/api/resources/" + res),
                post("/api/staff").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@y.z\",\"password\":\"Abcdef#1\",\"name\":\"X\"}"),
                post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Coke\",\"price\":40}"),
                patch("/api/membership/plans/nope/active").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))) {
            int code = mvc.perform(req.with(asAdmin).header(TestIdempotencyKeys.NO_KEY_HEADER, "1")).andReturn().getResponse().getStatus();
            assertEquals(400, code, "no key should be refused");
        }
        assertTrue(resources.existsById(res));
    }
}
