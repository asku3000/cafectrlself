package com.progameflixx.cafectrl.testsupport;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

/**
 * Test-only: gives every MockMvc request its own Idempotency-Key unless the test sets one itself (to test replays)
 * or sends {@value #NO_KEY_HEADER} (to test that a missing key is refused). Real clients always send a key.
 */
@Configuration
public class TestIdempotencyKeys {

    public static final String NO_KEY_HEADER = "X-Test-No-Idempotency-Key";

    @Bean
    MockMvcBuilderCustomizer autoIdempotencyKey() {
        return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/").with(request -> {
            if (request.getHeader("Idempotency-Key") == null && request.getHeader(NO_KEY_HEADER) == null) {
                request.addHeader("Idempotency-Key", "test-" + UUID.randomUUID());
            }
            return request;
        }));
    }
}
