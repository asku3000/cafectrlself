package com.progameflixx.cafectrl.audit;

import com.jayway.jsonpath.JsonPath;
import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.membership.TestClockConfig;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Audit log viewer API: admin-only, cafe-scoped, IST filters, paging, detail, CSV export (itself audited). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AuditLogViewerTest {

    static final String ADMIN = "viewer.admin@test.local";
    static final String OPERATOR = "viewer.op@test.local";
    static final String SEEDED_ACTIONS = "SESSION_STARTED,BILL_DISCOUNT_APPLIED,BILL_CREATED,RESOURCE_UPDATED";

    @Autowired MockMvc mvc;
    @Autowired AuditService audit;
    @Autowired AuditEventRepository events;
    @Autowired UserRepository users;
    @Autowired TestClockConfig.MutableClock clock;

    static String cafe;      // this test run's cafe
    static String entity;    // a session id used by the seeded entries
    static boolean seeded;
    static Long otherCafeEntryId;

    @BeforeEach
    void seed() {
        if (seeded) return;
        seeded = true;
        cafe = "cafe-viewer-" + UUID.randomUUID();
        entity = UUID.randomUUID().toString();
        user(ADMIN, "Vik Admin", "CAFE_ADMIN");
        user(OPERATOR, "Vik Operator", "OPERATOR");

        // 10 Mar 2027 00:30 IST == 9 Mar 2027 19:00 UTC: tests the IST -> UTC boundary
        clock.setInstant(Instant.parse("2027-03-09T19:00:00Z"));
        audit.record(AuditRecord.of(AuditActions.SESSION_STARTED).cafe(cafe).anonymous("Seeder")
                .entity(AuditActions.ENTITY_SESSION, entity).description("Started session for Meera on PS5-1"));
        clock.setInstant(Instant.parse("2027-03-10T06:30:00Z")); // 12:00 IST
        audit.record(AuditRecord.of(AuditActions.BILL_DISCOUNT_APPLIED).cafe(cafe).anonymous("Seeder")
                .entity(AuditActions.ENTITY_SESSION, entity).description("Applied ₹50.00 discount on Bill #ABC (PS5-1)")
                .diff(java.util.Map.of("total", 200), java.util.Map.of("total", 150)));
        audit.recordFailure(AuditRecord.of(AuditActions.BILL_CREATED).cafe(cafe).anonymous("Seeder")
                .entity(AuditActions.ENTITY_SESSION, entity).description("=HYPERLINK(\"http://evil\")"), "Card has only ₹10.00");
        for (int i = 0; i < 5; i++) {
            audit.record(AuditRecord.of(AuditActions.RESOURCE_UPDATED).cafe(cafe).anonymous("Seeder")
                    .entity(AuditActions.ENTITY_RESOURCE, "res-" + i).description("Edited resource 'PS5-" + i + "'"));
        }
        // another cafe's entry must never be visible
        audit.record(AuditRecord.of(AuditActions.SESSION_STARTED).cafe("someone-else").anonymous("Intruder")
                .entity(AuditActions.ENTITY_SESSION, entity).description("Other cafe"));
        otherCafeEntryId = events.findByEntityTypeAndEntityIdOrderByIdDesc(AuditActions.ENTITY_SESSION, entity).stream()
                .filter(e -> "someone-else".equals(e.getCafeId())).findFirst().orElseThrow().getId();
        clock.setDate(LocalDate.now());
    }

    void user(String email, String name, String role) {
        User u = users.findByEmail(email).orElseGet(User::new);
        u.setEmail(email);
        u.setName(name);
        u.setRole(role);
        u.setCafeId(cafe);
        u.setActive(true);
        u.setPermissions(new ArrayList<>(List.of("sessions", "reports", "memberships")));
        users.save(u);
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void listIsNewestFirstScopedToTheCafeAndPaged() throws Exception {
        // only the rows seeded above (other tests in this class add entries of their own, e.g. exports)
        mvc.perform(get("/api/audit-log").param("size", "3").param("action", SEEDED_ACTIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.total_pages").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].action").value(AuditActions.RESOURCE_UPDATED))
                .andExpect(jsonPath("$.items[*].cafe_id", everyItem(is(cafe))))
                .andExpect(jsonPath("$.items[0].occurred_at", endsWith("Z")));
        mvc.perform(get("/api/audit-log").param("size", "3").param("page", "2").param("action", SEEDED_ACTIONS))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].action").value(AuditActions.SESSION_STARTED));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void filtersByEntityActionOutcomeAndText() throws Exception {
        mvc.perform(get("/api/audit-log").param("entity_type", "SESSION").param("entity_id", entity))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/audit-log").param("action", "BILL_DISCOUNT_APPLIED,SESSION_STARTED"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/audit-log").param("outcome", "failure"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("Card has only ₹10.00"));
        mvc.perform(get("/api/audit-log").param("q", "discount"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/audit-log").param("q", "100%_"))
                .andExpect(jsonPath("$.total").value(0)); // wildcards are matched literally
        mvc.perform(get("/api/audit-log").param("outcome", "maybe")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void timeFiltersAreIstAndConvertedToUtc() throws Exception {
        // The 00:30 IST entry is on 10 March in India even though it is 9 March in UTC
        mvc.perform(get("/api/audit-log").param("from", "2027-03-10T00:00").param("to", "2027-03-11T00:00").param("action", "SESSION_STARTED"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/audit-log").param("from", "2027-03-10T00:31").param("to", "2027-03-11T00:00").param("action", "SESSION_STARTED"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/audit-log").param("from", "2027-03-09T00:00").param("to", "2027-03-10T00:00").param("action", "SESSION_STARTED"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/audit-log").param("from", "2027-03-11T00:00").param("to", "2027-03-10T00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void detailShowsTheDiffAndOtherCafesAreHidden() throws Exception {
        String list = mvc.perform(get("/api/audit-log").param("action", "BILL_DISCOUNT_APPLIED"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$.items[0].id");
        mvc.perform(get("/api/audit-log/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.total.before").value(200))
                .andExpect(jsonPath("$.changes.total.after").value(150));
        mvc.perform(get("/api/audit-log/" + otherCafeEntryId)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void csvExportIsSafeAndIsItselfAudited() throws Exception {
        int before = events.findByActionOrderByIdDesc(AuditActions.AUDIT_EXPORTED).size();
        String csv = mvc.perform(get("/api/audit-log/export").param("entity_type", "SESSION").param("entity_id", entity))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        String[] lines = csv.replace("﻿", "").split("\r\n");
        assertEquals(4, lines.length); // header + 3 rows
        assertTrue(lines[0].startsWith("id,time_ist,actor"));
        assertTrue(csv.contains("2027-03-10 00:30:00"), "times are exported in IST");
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""), "formula-looking cells are neutralised");
        assertTrue(csv.contains("Applied ₹50.00 discount"));
        assertFalse(csv.contains("Other cafe"));

        List<AuditEvent> exports = events.findByActionOrderByIdDesc(AuditActions.AUDIT_EXPORTED);
        assertEquals(before + 1, exports.size());
        AuditEvent e = exports.getFirst();
        assertEquals("Vik Admin", e.getActorName());
        assertEquals(3, ((Number) e.getDetails().get("rows")).intValue());
        assertEquals("SESSION", ((java.util.Map<?, ?>) e.getDetails().get("filters")).get("entity_type"));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void facetsListActionsTypesAndActors() throws Exception {
        mvc.perform(get("/api/audit-log/facets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions", hasItems("BILL_CREATED", "RESOURCE_UPDATED", "SESSION_STARTED")))
                .andExpect(jsonPath("$.entity_types", hasItems("RESOURCE", "SESSION")));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "CAFE_ADMIN")
    void entriesCannotBeChangedOrDeletedThroughTheApi() throws Exception {
        long count = events.count();
        String list = mvc.perform(get("/api/audit-log").param("size", "1")).andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$.items[0].id");
        mvc.perform(delete("/api/audit-log/" + id)).andExpect(status().is4xxClientError());
        mvc.perform(put("/api/audit-log/" + id).contentType("application/json").content("{\"description\":\"x\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(patch("/api/audit-log/" + id).contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
        assertTrue(events.count() >= count);
        assertTrue(events.findById(id.longValue()).isPresent());
    }

    @Test
    @WithMockUser(username = OPERATOR, roles = "OPERATOR")
    void operatorsCannotSeeTheAuditLog() throws Exception {
        mvc.perform(get("/api/audit-log")).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-log/export")).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit")).andExpect(status().isNotFound()); // the old open endpoint is gone
    }
}
