package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.entity.Resource;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.ResourceRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import com.progameflixx.cafectrl.service.MembershipCardService;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Optional card tagging on sessions; the non-member flow must keep working exactly as before. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
@Transactional
@WithMockUser(username = "floor@test.local", roles = "OPERATOR")
class SessionMembershipTaggingTest {

    static final String CAFE = "cafe-tagging-test";
    static final LocalDate DAY_1 = LocalDate.of(2026, 12, 1);

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ResourceRepository resources;
    @Autowired CustomerSessionRepository sessions;
    @Autowired MembershipPlanService plans;
    @Autowired MembershipCardService cards;
    @Autowired TestClockConfig.MutableClock clock;

    String resourceId;
    String resource2Id;
    MembershipCard card;

    @BeforeEach
    void setUp() {
        clock.setDate(DAY_1);
        // Floor operator: sessions + billing only, NOT memberships — tagging must still work
        User op = users.findByEmail("floor@test.local").orElseGet(() -> {
            User u = new User();
            u.setEmail("floor@test.local");
            u.setName("Floor Staff");
            u.setRole("OPERATOR");
            u.setCafeId(CAFE);
            u.setActive(true);
            u.setPermissions(new java.util.ArrayList<>(List.of("sessions", "billing")));
            return users.save(u);
        });
        resourceId = resource("PS5 #1");
        resource2Id = resource("PS5 #2");

        User admin = new User();
        admin.setCafeId(CAFE);
        admin.setName("Owner");
        MembershipPlanRequest p = new MembershipPlanRequest();
        p.setName("Silver " + UUID.randomUUID());
        p.setLoadAmountPaise(100_000L);
        p.setBonusType("FIXED");
        p.setBonusValue(0L);
        p.setValidityDays(30);
        String planId = plans.create(admin, p).getId();
        MembershipCardRequests.Issue r = new MembershipCardRequests.Issue();
        r.setCustomerName("Meera");
        r.setCustomerPhone("9111111111");
        r.setPlanId(planId);
        r.setPaymentMode("cash");
        card = cards.issue(admin, r);
    }

    String resource(String name) {
        Resource r = new Resource();
        r.setCafeId(CAFE);
        r.setName(name);
        return resources.save(r).getId();
    }

    String startJson(String resId, String cardId) {
        return """
                {"customer_name":"Meera","customer_phone":" 9111111111 ","resource_id":"%s","player_count":1%s}
                """.formatted(resId, cardId == null ? "" : ",\"membership_card_id\":\"" + cardId + "\"");
    }

    String idOf(String json) {
        return com.jayway.jsonpath.JsonPath.read(json, "$.id");
    }

    @Test
    void nonMemberSessionStartsAsBeforeAndNowKeepsPhone() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(startJson(resourceId, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.customer_phone").value("9111111111"))
                .andExpect(jsonPath("$.membership_card_id").value(nullValue()))
                .andExpect(jsonPath("$.membership_card").value(nullValue()));
    }

    @Test
    void startWithCardTagsItAndShowsHolderBalanceExpiry() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(startJson(resourceId, card.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membership_card_id").value(card.getId()))
                .andExpect(jsonPath("$.membership_card.card_number").value(card.getCardNumber()))
                .andExpect(jsonPath("$.membership_card.customer_name").value("Meera"))
                .andExpect(jsonPath("$.membership_card.total_balance_paise").value(100000))
                .andExpect(jsonPath("$.membership_card.expiry_date").value("2026-12-30"))
                .andExpect(jsonPath("$.membership_card.usable").value(true));

        mvc.perform(get("/api/sessions/active"))
                .andExpect(jsonPath("$[?(@.membership_card_id == '" + card.getId() + "')].membership_card.card_number")
                        .value(card.getCardNumber()));
    }

    @Test
    void sameCardCanBeTaggedToSeveralRunningSessions() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(startJson(resourceId, card.getId())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(startJson(resource2Id, card.getId())))
                .andExpect(status().isOk());
        long tagged = sessions.findByCafeIdAndStatus(CAFE, "active").stream()
                .filter(s -> card.getId().equals(s.getMembershipCardId())).count();
        assertEquals(2, tagged);
    }

    @Test
    void expiredCardIsBlockedAndNoSessionIsCreated() throws Exception {
        clock.setDate(DAY_1.plusDays(30));
        long before = sessions.findByCafeIdAndStatus(CAFE, "active").size();
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(startJson(resourceId, card.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Card " + card.getCardNumber() + " expired on 2026-12-30"));
        assertEquals(before, sessions.findByCafeIdAndStatus(CAFE, "active").size());
    }

    @Test
    void cardFromAnotherCafeIsNotFound() throws Exception {
        User other = new User();
        other.setCafeId("another-cafe");
        other.setName("x");
        MembershipPlanRequest p = new MembershipPlanRequest();
        p.setName("P");
        p.setLoadAmountPaise(100L);
        p.setValidityDays(5);
        MembershipCardRequests.Issue r = new MembershipCardRequests.Issue();
        r.setCustomerName("Other");
        r.setCustomerPhone("9222222222");
        r.setPlanId(plans.create(other, p).getId());
        r.setPaymentMode("cash");
        MembershipCard foreign = cards.issue(other, r);

        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(startJson(resourceId, foreign.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Membership card not found"));
    }

    @Test
    void tagChangeAndRemoveWhileRunningButNotAfterBilling() throws Exception {
        String body = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(startJson(resourceId, null)))
                .andReturn().getResponse().getContentAsString();
        String sid = idOf(body);

        mvc.perform(put("/api/sessions/" + sid + "/membership-card").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membership_card_id\":\"" + card.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membership_card.card_number").value(card.getCardNumber()));

        mvc.perform(put("/api/sessions/" + sid + "/membership-card").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membership_card_id\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membership_card_id").value(nullValue()));

        CustomerSession s = sessions.findById(sid).orElseThrow();
        s.setStatus("billed");
        sessions.save(s);
        mvc.perform(put("/api/sessions/" + sid + "/membership-card").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membership_card_id\":\"" + card.getId() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lookupFindsByPhoneNameOrNumberWithoutMembershipsPermission() throws Exception {
        mvc.perform(get("/api/sessions/membership-cards/lookup").param("q", "91111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].card_number").value(card.getCardNumber()));
        mvc.perform(get("/api/sessions/membership-cards/lookup").param("q", "meer"))
                .andExpect(jsonPath("$[0].customer_name").value("Meera"));
        mvc.perform(get("/api/sessions/membership-cards/lookup").param("q", card.getCardNumber().toLowerCase()))
                .andExpect(jsonPath("$[0].id").value(card.getId()));
        mvc.perform(get("/api/sessions/membership-cards/lookup").param("q", "m"))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
