package com.progameflixx.cafectrl;

import com.jayway.jsonpath.JsonPath;
import com.progameflixx.cafectrl.entity.RateCard;
import com.progameflixx.cafectrl.entity.Resource;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.RateCardRepository;
import com.progameflixx.cafectrl.repository.ResourceRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Edits from the setup dialogs: rate card / resource PUT (was missing), create-only POST, staff password reset. */
@SpringBootTest
@AutoConfigureMockMvc
class SetupEditFixesTest {

    static final String CAFE = "cafe-setup-edits";
    static final String ADMIN = "setup.admin@test.local";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RateCardRepository rateCards;
    @Autowired ResourceRepository resources;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void admin() {
        User u = users.findByEmail(ADMIN).orElseGet(User::new);
        u.setEmail(ADMIN);
        u.setName("Setup Admin");
        u.setRole("CAFE_ADMIN");
        u.setCafeId(CAFE);
        u.setActive(true);
        u.setPermissions(new ArrayList<>());
        users.save(u);
    }

    String json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body, int expect) throws Exception {
        return mvc.perform(req.with(user(ADMIN).roles("CAFE_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void rateCardAndResourceCanBeEditedWithPut() throws Exception {
        String rc = JsonPath.read(json(post("/api/rate-cards"), "{\"name\":\"Weekday\",\"billing_interval_minutes\":30,\"grace_minutes\":0}", 200), "$.id");
        json(put("/api/rate-cards/" + rc), "{\"name\":\"Weekday v2\",\"grace_minutes\":5}", 200);
        RateCard saved = rateCards.findById(rc).orElseThrow();
        assertEquals("Weekday v2", saved.getName());
        assertEquals(5, saved.getGraceMinutes());
        assertEquals(30, saved.getBillingIntervalMinutes()); // untouched fields kept

        String res = JsonPath.read(json(post("/api/resources"), "{\"name\":\"PS5-9\",\"rate_card_id\":\"" + rc + "\"}", 200), "$.id");
        json(put("/api/resources/" + res), "{\"name\":\"PS5-9 (window)\",\"is_active\":false}", 200);
        Resource r = resources.findById(res).orElseThrow();
        assertEquals("PS5-9 (window)", r.getName());
        assertEquals(rc, r.getRateCardId());
        assertFalse(r.getIsActive());
    }

    @Test
    void anotherCafesRecordsCanNeitherBeEditedNorOverwritten() throws Exception {
        RateCard theirs = new RateCard();
        theirs.setCafeId("someone-else");
        theirs.setName("Their card");
        theirs = rateCards.save(theirs);
        Resource theirRes = new Resource();
        theirRes.setCafeId("someone-else");
        theirRes.setName("Their PS5");
        theirRes = resources.save(theirRes);

        json(put("/api/rate-cards/" + theirs.getId()), "{\"name\":\"hijacked\"}", 404);
        json(put("/api/resources/" + theirRes.getId()), "{\"name\":\"hijacked\"}", 404);

        // POST used to save whatever id it was given (an overwrite); now it always creates a new record
        String created = JsonPath.read(json(post("/api/rate-cards"), "{\"id\":\"" + theirs.getId() + "\",\"name\":\"hijacked\"}", 200), "$.id");
        assertNotEquals(theirs.getId(), created);
        String createdRes = JsonPath.read(json(post("/api/resources"), "{\"id\":\"" + theirRes.getId() + "\",\"name\":\"hijacked\"}", 200), "$.id");
        assertNotEquals(theirRes.getId(), createdRes);
        assertEquals("Their card", rateCards.findById(theirs.getId()).orElseThrow().getName());
        assertEquals("someone-else", rateCards.findById(theirs.getId()).orElseThrow().getCafeId());
        assertEquals("Their PS5", resources.findById(theirRes.getId()).orElseThrow().getName());
    }

    @Test
    void resettingAStaffPasswordFromTheEditDialogWorks() throws Exception {
        String email = "staff." + UUID.randomUUID() + "@test.local";
        String id = JsonPath.read(json(post("/api/staff"),
                "{\"email\":\"" + email + "\",\"password\":\"First#Pass1\",\"name\":\"Neel\",\"permissions\":[\"sessions\"]}", 200), "$.id");
        // what the Staff dialog sends now (it used to send new_password, which the backend never read)
        json(put("/api/staff/" + id), "{\"name\":\"Neel\",\"permissions\":[\"sessions\"],\"is_active\":true,\"password\":\"Second#Pass2\"}", 200);
        User u = users.findById(id).orElseThrow();
        assertTrue(encoder.matches("Second#Pass2", u.getPassword()));
        assertFalse(encoder.matches("First#Pass1", u.getPassword()));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Second#Pass2\"}"))
                .andExpect(status().isOk());
    }
}
