package com.progameflixx.cafectrl.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    @Autowired
    private JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        // Inside doFilterInternal...
        if (token != null && jwtService.isTokenValid(token)) {
            Claims claims = jwtService.extractAllClaims(token);
            String userId = claims.getSubject();
            String role = claims.get("role", String.class); // Extract the role from the token

            // Spring Security expects roles to be formatted like "ROLE_OPERATOR"
            String springRole = "ROLE_" + role.toUpperCase();
            List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(springRole));

            // Attach the user ID and their specific role to the security context
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    userId, null, authorities
            );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        // Continue to the next filter or the requested controller
        filterChain.doFilter(request, response);
    }

    // Translates: extract_token
    private String extractToken(HttpServletRequest request) {
        // 1. Check Cookies First
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 2. Fallback to Authorization Header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }
}
