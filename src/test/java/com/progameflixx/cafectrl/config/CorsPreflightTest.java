package com.progameflixx.cafectrl.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The browser's preflight must allow what the UI sends (Idempotency-Key header, PATCH method). */
@SpringBootTest
@AutoConfigureMockMvc
class CorsPreflightTest {

    @Autowired MockMvc mvc;
    @Value("${frontend.url}") String origin;

    @Test
    void preflightAllowsTheIdempotencyKeyHeader() throws Exception {
        mvc.perform(options("/api/sessions").header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("idempotency-key")));
    }

    @Test
    void preflightAllowsPatch() throws Exception {
        mvc.perform(options("/api/membership/plans/x/active").header("Origin", origin)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsStringIgnoringCase("PATCH")));
    }
}
