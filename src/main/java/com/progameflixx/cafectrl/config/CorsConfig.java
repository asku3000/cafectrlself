package com.progameflixx.cafectrl.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// ADDED: Missing utility imports
import java.util.Arrays;
import java.util.Collections;

@Configuration
public class CorsConfig {

    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    // FIXED: Moved the @Value injection INSIDE the class
    @Value("${frontend.url}")
    private String corsOriginUrl;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        logger.info("CORS allowed origin: {}", corsOriginUrl);

        // Put your exact frontend URL here, using the injected variable
        configuration.setAllowedOrigins(Collections.singletonList(corsOriginUrl));

        // MUST allow OPTIONS for preflight requests!
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Idempotency-Key: sent with every critical write (double-submit protection)
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Cache-Control", "Content-Type", "Idempotency-Key"));
        // response headers the UI reads (export file name / truncation flag, replayed-request marker, request id for support)
        configuration.setExposedHeaders(Arrays.asList("Content-Disposition", "X-Export-Truncated", "Idempotent-Replay", "X-Request-Id"));
        configuration.setAllowCredentials(true); // Required if sending cookies/tokens

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Apply this configuration to ALL routes
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}