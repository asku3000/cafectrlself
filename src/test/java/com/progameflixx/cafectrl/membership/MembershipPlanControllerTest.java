package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.UserRepository;
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

/** Role enforcement on the plan/settings endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MembershipPlanControllerTest {

    private static final String PLAN_JSON = """
            {"name":"Bronze","load_amount_paise":50000,"bonus_type":"FIXED","bonus_value":5000,"validity_days":30}
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void users() {
        saveUser("owner@test.local", "CAFE_ADMIN");
        saveUser("op@test.local", "OPERATOR");
    }

    private void saveUser(String email, String role) {
        if (userRepository.findByEmail(email).isPresent()) return;
        User u = new User();
        u.setEmail(email);
        u.setName(email);
        u.setRole(role);
        u.setCafeId("cafe-ctrl-test");
        u.setActive(true);
        u.setPermissions(List.of("sessions"));
        userRepository.save(u);
    }

    @Test
    @WithMockUser(username = "owner@test.local", roles = "CAFE_ADMIN")
    void adminCanCreateAndList() throws Exception {
        mvc.perform(post("/api/membership/plans").contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bronze"))
                .andExpect(jsonPath("$.bonus_amount_paise").value(5000))
                .andExpect(jsonPath("$.total_value_paise").value(55000));

        mvc.perform(get("/api/membership/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bronze"));
    }

    @Test
    @WithMockUser(username = "owner@test.local", roles = "CAFE_ADMIN")
    void validationErrorsReturn400WithDetail() throws Exception {
        mvc.perform(post("/api/membership/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"load_amount_paise\":0,\"validity_days\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Load amount must be greater than zero"));
    }

    @Test
    @WithMockUser(username = "op@test.local", roles = "OPERATOR")
    void operatorCanReadButNotWrite() throws Exception {
        mvc.perform(get("/api/membership/plans")).andExpect(status().isOk());
        mvc.perform(get("/api/membership/settings")).andExpect(status().isOk());

        mvc.perform(post("/api/membership/plans").contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/membership/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deduction_order\":\"BONUS_FIRST\"}"))
                .andExpect(status().isForbidden());
    }
}
