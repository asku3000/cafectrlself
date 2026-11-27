package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.UserRepository;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Permission enforcement on the card endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MembershipCardControllerTest {

    static final String CAFE = "cafe-card-ctrl-test";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired MembershipPlanService plans;

    String planId;

    @BeforeEach
    void setUp() {
        User admin = save("cardowner@test.local", "CAFE_ADMIN", List.of());
        save("seller@test.local", "OPERATOR", List.of("sessions", "memberships"));
        save("plainop@test.local", "OPERATOR", List.of("sessions", "billing"));
        MembershipPlanRequest r = new MembershipPlanRequest();
        r.setName("Bronze");
        r.setLoadAmountPaise(50_000L);
        r.setBonusType("FIXED");
        r.setBonusValue(5_000L);
        r.setValidityDays(30);
        MembershipPlan p = plans.create(admin, r);
        planId = p.getId();
    }

    User save(String email, String role, List<String> perms) {
        return users.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            u.setName(email);
            u.setRole(role);
            u.setCafeId(CAFE);
            u.setActive(true);
            u.setPermissions(new java.util.ArrayList<>(perms));
            return users.save(u);
        });
    }

    String issueJson() {
        return """
                {"customer_name":"Asha","customer_phone":"9000000001","plan_id":"%s","payment_mode":"cash"}
                """.formatted(planId);
    }

    @Test
    @WithMockUser(username = "seller@test.local", roles = "OPERATOR")
    void operatorWithMembershipsPermissionCanSellButNotAdjust() throws Exception {
        String body = mvc.perform(post("/api/membership/cards").contentType(MediaType.APPLICATION_JSON).content(issueJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_balance_paise").value(55000))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.usable").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/membership/cards/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciled").value(true))
                .andExpect(jsonPath("$.ledger.length()").value(2));

        mvc.perform(post("/api/membership/cards/" + id + "/adjust").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"CREDIT\",\"paid_paise\":100,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/membership/cards/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CLOSE\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "plainop@test.local", roles = "OPERATOR")
    void operatorWithoutMembershipsPermissionIsRefused() throws Exception {
        mvc.perform(get("/api/membership/cards")).andExpect(status().isForbidden());
        mvc.perform(post("/api/membership/cards").contentType(MediaType.APPLICATION_JSON).content(issueJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "cardowner@test.local", roles = "CAFE_ADMIN")
    void adminActionsRequireReason() throws Exception {
        String body = mvc.perform(post("/api/membership/cards").contentType(MediaType.APPLICATION_JSON).content(issueJson()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/membership/cards/" + id + "/adjust").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"CREDIT\",\"paid_paise\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Reason is required"));
        mvc.perform(post("/api/membership/cards/" + id + "/adjust").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"CREDIT\",\"paid_paise\":100,\"reason\":\"goodwill\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_balance_paise").value(50100));
    }
}
