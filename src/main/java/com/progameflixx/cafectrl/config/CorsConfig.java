package com.progameflixx.cafectrl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Put your exact frontend URL here
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000"));

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
