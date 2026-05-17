package com.progameflixx.cafectrl.config;

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

    // FIXED: Moved the @Value injection INSIDE the class
    @Value("${frontend.url}")
    private String corsOriginUrl;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Put your exact frontend URL here, using the injected variable
        configuration.setAllowedOrigins(Collections.singletonList(corsOriginUrl));

        // MUST allow OPTIONS for preflight requests!
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Cache-Control", "Content-Type"));
        configuration.setAllowCredentials(true); // Required if sending cookies/tokens

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Apply this configuration to ALL routes
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}