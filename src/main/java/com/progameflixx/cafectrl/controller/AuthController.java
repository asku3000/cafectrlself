package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditDiff;
import com.progameflixx.cafectrl.audit.AuditFormat;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import com.progameflixx.cafectrl.audit.Audited;
import com.progameflixx.cafectrl.config.JwtService;
import com.progameflixx.cafectrl.dto.LoginRequest;
import com.progameflixx.cafectrl.dto.SignupRequest;
import com.progameflixx.cafectrl.entity.Cafe;
import com.progameflixx.cafectrl.entity.PasswordResetToken;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.logging.LogContext;
import com.progameflixx.cafectrl.repository.CafeRepository;
import com.progameflixx.cafectrl.repository.PasswordResetTokenRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CafeRepository cafeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private AuditService audit;

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    // Translates: @api.post("/auth/signup")
    @PostMapping("/signup")
    @Transactional // Ensures both User and Cafe save, or neither do
    public ResponseEntity<?> signup(@RequestBody SignupRequest req) {
        String email = req.getEmail().toLowerCase();
        logger.info("Signup attempt for {} (cafe '{}')", email, req.getCafeName());

        if (userRepository.findByEmail(email).isPresent()) {
            logger.warn("Signup rejected: email {} already registered", email);
            audit.recordFailure(AuditRecord.of(AuditActions.SIGNUP).anonymous(email).detail("cafe_name", req.getCafeName()),
                    "Email already registered");
            return ResponseEntity.badRequest().body("Email already registered");
        }

        // 1. Create Cafe
        Cafe cafe = new Cafe();
        cafe.setName(req.getCafeName());
        cafe.setPhone(req.getPhone());
        cafe.setAddress(req.getAddress());
        cafe.setOwnerId("pending");
        cafe = cafeRepository.save(cafe); // Save to get the generated ID

        // 2. Create User
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(req.getPassword())); // BCrypt
        user.setName(req.getName());
        user.setRole("CAFE_ADMIN");
        user.setCafeId(cafe.getId());
        user.setActive(true);
        userRepository.save(user);

        // Update Cafe Owner
        cafe.setOwnerId(user.getId());
        cafeRepository.save(cafe);
        LogContext.putUser(user.getEmail(), user.getRole());
        LogContext.putCafeId(cafe.getId());
        logger.info("Signup successful: user {} created as owner of cafe {} ('{}')", user.getId(), cafe.getId(), cafe.getName());
        audit.record(AuditRecord.of(AuditActions.SIGNUP).actor(user).entity(AuditActions.ENTITY_CAFE, cafe.getId())
                .description("Signed up cafe '" + cafe.getName() + "' with owner " + user.getName() + " (" + email + ")")
                .detail("cafe_name", cafe.getName()).detail("owner_email", email));

        // 3. Generate JWT & Cookie
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        ResponseCookie cookie = ResponseCookie.from("access_token", token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(7 * 24 * 60 * 60)
                .build();

        Map<String, Object> response = new HashMap<>();
        response.put("user", user); // Password will be hidden if you add @JsonIgnore to the User entity password field
        response.put("cafe", cafe);
        response.put("access_token", token);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    // Translates: @api.post("/auth/login")
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        String loginEmail = req.getEmail().toLowerCase();
        Optional<User> userOpt = userRepository.findByEmail(loginEmail);

        if (userOpt.isEmpty() || !passwordEncoder.matches(req.getPassword(), userOpt.get().getPassword())) {
            logger.warn("Login failed for {}: invalid credentials", loginEmail);
            AuditRecord failed = AuditRecord.of(AuditActions.LOGIN_FAILED).entity(AuditActions.ENTITY_USER, userOpt.map(User::getId).orElse(null))
                    .description("Failed login for " + loginEmail);
            if (userOpt.isPresent()) failed.actor(userOpt.get()); else failed.anonymous(loginEmail);
            audit.recordFailure(failed, userOpt.isPresent() ? "Wrong password" : "Unknown email");
            return ResponseEntity.status(401).body("Invalid email or password");
        }

        User user = userOpt.get();
        LogContext.putUser(user.getEmail(), user.getRole());
        LogContext.putCafeId(user.getCafeId());
        if (!user.isActive()) {
            logger.warn("Login blocked for {}: account disabled", loginEmail);
            audit.recordFailure(AuditRecord.of(AuditActions.LOGIN_FAILED).actor(user).entity(AuditActions.ENTITY_USER, user.getId())
                    .description("Blocked login for " + loginEmail), "Account disabled");
            return ResponseEntity.status(403).body("Account disabled");
        }
        logger.info("Login successful for {} ({}) role={}", user.getEmail(), user.getName(), user.getRole());
        audit.record(AuditRecord.of(AuditActions.LOGIN_SUCCEEDED).actor(user).entity(AuditActions.ENTITY_USER, user.getId())
                .description(user.getName() + " logged in"));

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        ResponseCookie cookie = ResponseCookie.from("access_token", token)
                .httpOnly(true).secure(true).path("/").maxAge(7 * 24 * 60 * 60).build();

        Map<String, Object> response = new HashMap<>();
        response.put("user", user);
        response.put("access_token", token);

        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(response);
    }

    /**
     * Clears the login cookie and records the logout. Login tokens are stateless JWTs, so the token itself stays
     * valid until it expires; the UI also drops its copy.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(Authentication auth) {
        ResponseCookie cleared = ResponseCookie.from("access_token", "")
                .httpOnly(true).secure(true).path("/").maxAge(0).build();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            userRepository.findByEmail(auth.getName()).ifPresent(u -> {
                LogContext.putCafeId(u.getCafeId());
                logger.info("Logout for {}", u.getEmail());
                audit.record(AuditRecord.of(AuditActions.LOGOUT).actor(u).entity(AuditActions.ENTITY_USER, u.getId())
                        .description(u.getName() + " logged out"));
            });
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cleared.toString()).body(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(HttpServletRequest request) {
        // 1. Get the token from the header (or cookie)
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            // Fallback: check the cookie if you're using them
            // You can also extract this logic into a utility
            logger.warn("/me called without a Bearer token");
            return ResponseEntity.status(401).body("No token found");
        }

        try {
            // 2. Validate token and get the email/subject
            String email = jwtService.extractUsername(token); // Or whatever method your JwtService uses

            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isPresent()) {
                LogContext.putCafeId(userOpt.get().getCafeId());
                logger.debug("/me resolved user {}", email);
                Map<String, Object> response = new HashMap<>();
                response.put("user", userOpt.get());
                return ResponseEntity.ok(response);
            }
            logger.warn("/me: token valid but user {} no longer exists", email);
        } catch (Exception e) {
            logger.warn("/me: invalid session token ({})", e.getMessage());
            return ResponseEntity.status(401).body("Invalid session");
        }

        return ResponseEntity.status(401).body("Unauthorized");
    }

    // --- 1. FORGOT PASSWORD (Request Recovery Link) ---
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> req, HttpServletRequest request) {
        String email = req.get("email");
        logger.info("Password reset requested for {}", email);

        // Lookup target account entries
        User user = userRepository.findByEmail(email).orElse(null);

        // STRICT SECURITY GUARD: Only allow password recovery for CAFE_ADMIN role
        if (user == null || !"CAFE_ADMIN".equalsIgnoreCase(user.getRole())) {
            // Return a vague message so malicious users can't guess valid admin emails
            logger.warn("Password reset ignored for {}: no CAFE_ADMIN account", email);
            audit.recordFailure(AuditRecord.of(AuditActions.PASSWORD_RESET_REQUESTED).anonymous(String.valueOf(email))
                    .description("Password reset requested for " + email), "No admin account with that email");
            return ResponseEntity.ok(Map.of("message", "If the email matches an Admin account, a verification token has been generated."));
        }

        // Clean up any previously forgotten tokens for this email to keep DB tidy
        try {
            tokenRepository.deleteByEmail(email);
        } catch (Exception e) {
            // Safe to catch if no old tokens exist
            logger.debug("No previous reset tokens removed for {}: {}", email, e.getMessage());
        }

        // Generate a secure unique UUID token
        String token = java.util.UUID.randomUUID().toString();

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setToken(token);
        resetToken.setEmail(email);
        // Token valid for exactly 15 minutes
        resetToken.setExpiryDate(LocalDateTime.now().plusMinutes(15));

        tokenRepository.save(resetToken);

        // Simulate Email Delivery directly into your local running terminal logs
        String origin = request.getHeader("Origin");

        // Fallback safeguard if the browser didn't supply an origin header for some reason
        if (origin == null || origin.isBlank()) {
            origin = "http://localhost:3000";
        }

        // ... your token generation logic ...

        // 2. Build the link dynamically using the client's origin
        String resetLink = origin + "/reset-password?token=" + token;
        logger.info("Password reset token generated for admin {} (valid 15 min)", email);
        audit.record(AuditRecord.of(AuditActions.PASSWORD_RESET_REQUESTED).actor(user).entity(AuditActions.ENTITY_USER, user.getId())
                .description("Password reset link generated for " + email + " (valid 15 minutes)"));
        // The link contains a live reset token, so it is only written at DEBUG level
        logger.debug("Password reset link for {}: {}", email, resetLink);
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Recovery link generated successfully!, Press F12, Click on link");
        response.put("devResetLink", resetLink);
        return ResponseEntity.ok(response);
    }

    // --- 2. RESET PASSWORD (Commit New Password using Token) ---
    @Transactional
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> req) {
        String token = req.get("token");
        String newPassword = req.get("newPassword");

        // Locate the security token configuration row
        PasswordResetToken resetToken = tokenRepository.findByToken(token).orElse(null);
        if (resetToken == null || resetToken.isExpired()) {
            logger.warn("Password reset failed: token {}", resetToken == null ? "not found" : "expired for " + resetToken.getEmail());
            audit.recordFailure(AuditRecord.of(AuditActions.PASSWORD_RESET_COMPLETED)
                    .anonymous(resetToken == null ? "unknown" : resetToken.getEmail()),
                    resetToken == null ? "Invalid reset link" : "Expired reset link");
            return ResponseEntity.badRequest().body(Map.of("message", "The verification token is invalid or has expired."));
        }

        // Locate matching Admin account
        User user = userRepository.findByEmail(resetToken.getEmail()).orElse(null);
        if (user == null || !"CAFE_ADMIN".equalsIgnoreCase(user.getRole())) {
            logger.warn("Password reset failed: no CAFE_ADMIN account for {}", resetToken.getEmail());
            return ResponseEntity.badRequest().body(Map.of("message", "Account validation failed."));
        }

        // Hash and save the incoming plain text password securely
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Immediately burn the token row so it can never be used twice
        tokenRepository.delete(resetToken);
        logger.info("Password reset completed for {}", user.getEmail());
        audit.record(AuditRecord.of(AuditActions.PASSWORD_RESET_COMPLETED).actor(user).entity(AuditActions.ENTITY_USER, user.getId())
                .description(user.getName() + " reset their password with an emailed link"));

        return ResponseEntity.ok(Map.of("message", "Password updated successfully! You can now log in."));
    }

    @PostMapping("/change-password")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    @Audited(action = AuditActions.PASSWORD_CHANGED, entityType = AuditActions.ENTITY_USER)
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> req, Authentication auth) {
        String principalIdentifier = auth.getName();
        String oldPassword = req.get("current_password");
        String newPassword = req.get("new_password");

        if (newPassword == null || newPassword.isBlank()) {
            logger.warn("Change password rejected: new password empty");
            return ResponseEntity.badRequest().body(Map.of("message", "New password cannot be empty"));
        }

        // FIX: Try looking up by UUID first, then fallback to Email
        User user = userRepository.findById(principalIdentifier)
                .orElseGet(() -> userRepository.findByEmail(principalIdentifier).orElse(null));

        // Safeguard check if the user genuinely doesn't exist in the DB
        if (user == null) {
            logger.warn("Change password failed: user {} not found", principalIdentifier);
            return ResponseEntity.status(404).body(Map.of("message", "Logged-in user profile not found."));
        }

        // Verify the old password matches what's in the DB
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            logger.warn("Change password rejected for {}: incorrect current password", user.getEmail());
            return ResponseEntity.badRequest().body(Map.of("message", "Incorrect current password"));
        }

        // Hash and save the new password
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        logger.info("Password changed for {}", user.getEmail());
        audit.record(AuditRecord.of(AuditActions.PASSWORD_CHANGED).actor(user).entity(AuditActions.ENTITY_USER, user.getId())
                .description(user.getName() + " changed their password"));

        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }
}
