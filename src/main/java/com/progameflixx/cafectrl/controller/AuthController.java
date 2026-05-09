package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.config.JwtService;
import com.progameflixx.cafectrl.dto.LoginRequest;
import com.progameflixx.cafectrl.dto.SignupRequest;
import com.progameflixx.cafectrl.entity.Cafe;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.CafeRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    // Translates: @api.post("/auth/signup")
    @PostMapping("/signup")
    @Transactional // Ensures both User and Cafe save, or neither do
    public ResponseEntity<?> signup(@RequestBody SignupRequest req) {
        String email = req.getEmail().toLowerCase();

        if (userRepository.findByEmail(email).isPresent()) {
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
        userRepository.save(user);

        // Update Cafe Owner
        cafe.setOwnerId(user.getId());
        cafeRepository.save(cafe);

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
        Optional<User> userOpt = userRepository.findByEmail(req.getEmail().toLowerCase());

        if (userOpt.isEmpty() || !passwordEncoder.matches(req.getPassword(), userOpt.get().getPassword())) {
            return ResponseEntity.status(401).body("Invalid email or password");
        }

        User user = userOpt.get();
        if (!user.isActive()) {
            return ResponseEntity.status(403).body("Account disabled");
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        ResponseCookie cookie = ResponseCookie.from("access_token", token)
                .httpOnly(true).secure(true).path("/").maxAge(7 * 24 * 60 * 60).build();

        Map<String, Object> response = new HashMap<>();
        response.put("user", user);
        response.put("access_token", token);

        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(response);
    }
}
