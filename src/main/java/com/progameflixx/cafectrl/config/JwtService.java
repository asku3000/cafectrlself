package com.progameflixx.cafectrl.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    private final long EXPIRATION_TIME = 1000 * 60 * 60 * 24 * 7; // 7 days in milliseconds

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    // Translates: create_access_token
    public String generateToken(String userId, String email, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        claims.put("role", role);
        claims.put("type", "access");

        return Jwts.builder()
                .claims(claims)
                .subject(email)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(getSigningKey())
                .compact();
    }

    // Translates: decode_token
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return true; // If it parses without throwing an exception, it is valid/unexpired
        } catch (Exception e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    // Generic method to extract any specific piece of data (claim) from the token
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        logger.info("Username of logged in user :{}",claims.getSubject());
        return claimsResolver.apply(claims);
    }
}
