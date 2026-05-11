package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 1. ADDED CORS TO PREVENT REACT CRASHES
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"}, allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('CAFE_ADMIN')")
public class CafeAdminController {

    @Autowired
    private GameTypeRepository gameTypeRepository;
    @Autowired
    private RateCardRepository rateCardRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AccessoryRepository accessoryRepository;
    @Autowired
    private CustomerSessionRepository sessionRepository;

    // 2. INJECT CAFE REPOSITORY
    @Autowired
    private CafeRepository cafeRepository;

    // Helper to get cafeId from the logged-in admin
    private String getCafeId(Authentication auth) {
        // Assuming auth.getName() returns the user's email or ID that matches your User entity primary key
        return userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
    }

    // --- CAFE PROFILE (The Missing APIs) ---

    @GetMapping("/cafe")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public ResponseEntity<?> getMyCafe(Authentication auth) {
        String cafeId = getCafeId(auth);
        return cafeRepository.findById(cafeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/cafe")
    public ResponseEntity<?> updateCafe(@RequestBody Cafe updates, Authentication auth) {
        String cafeId = getCafeId(auth);

        Cafe existingCafe = cafeRepository.findById(cafeId).orElse(null);
        if (existingCafe == null) {
            return ResponseEntity.notFound().build();
        }

        // Only update fields that were actually sent in the request
        if (updates.getName() != null) existingCafe.setName(updates.getName());
        if (updates.getPhone() != null) existingCafe.setPhone(updates.getPhone());
        if (updates.getAddress() != null) existingCafe.setAddress(updates.getAddress());
        existingCafe.setSetupComplete(updates.isSetupComplete());

        cafeRepository.save(existingCafe);
        return ResponseEntity.ok(existingCafe);
    }

    // --- STAFF MANAGEMENT ---

    @GetMapping("/staff")
    public List<User> listStaff(Authentication auth) {
        return userRepository.findByCafeIdAndRole(getCafeId(auth), "operator");
    }

    @PostMapping("/staff")
    public User createOperator(@RequestBody User staffReq, Authentication auth) {
        staffReq.setCafeId(getCafeId(auth));
        staffReq.setRole("operator");
        staffReq.setPassword(passwordEncoder.encode(staffReq.getPassword()));
        return userRepository.save(staffReq);
    }

    @DeleteMapping("/staff/{uid}")
    public ResponseEntity<?> deleteOperator(@PathVariable String uid, Authentication auth) {
        userRepository.deleteById(uid);
        return ResponseEntity.ok().build();
    }

    // --- GAME TYPES (PC, PS5, etc.) ---

    @GetMapping("/game-types")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<GameType> listGameTypes(Authentication auth) {
        return gameTypeRepository.findByCafeId(getCafeId(auth));
    }

    @PostMapping("/game-types")
    public GameType createGameType(@RequestBody GameType gt, Authentication auth) {
        gt.setCafeId(getCafeId(auth));
        return gameTypeRepository.save(gt);
    }

    @DeleteMapping("/game-types/{gid}")
    public ResponseEntity<?> deleteGameType(@PathVariable String gid, Authentication auth) {
        String cafeId = getCafeId(auth);

        GameType gt = gameTypeRepository.findById(gid).orElse(null);

        // Security Check: Make sure it exists AND belongs to this specific cafe
        if (gt == null || !gt.getCafeId().equals(cafeId)) {
            return ResponseEntity.notFound().build();
        }

        gameTypeRepository.delete(gt);
        return ResponseEntity.ok(Map.of("ok", true)); // Matches Python's {"ok": True}
    }

    // --- RATE CARDS (Pricing Slabs) ---

    @GetMapping("/rate-cards")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<RateCard> listRateCards(Authentication auth) {
        return rateCardRepository.findByCafeId(getCafeId(auth));
    }

    @PostMapping("/rate-cards")
    public RateCard createRateCard(@RequestBody RateCard rc, Authentication auth) {
        rc.setCafeId(getCafeId(auth));
        return rateCardRepository.save(rc);
    }

    @DeleteMapping("/rate-cards/{rid}")
    public ResponseEntity<?> deleteRateCard(@PathVariable String rid, Authentication auth) {
        String cafeId = getCafeId(auth);

        RateCard rc = rateCardRepository.findById(rid).orElse(null);
        if (rc == null || !rc.getCafeId().equals(cafeId)) {
            return ResponseEntity.notFound().build();
        }

        rateCardRepository.delete(rc);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // --- RESOURCES (Individual Seats/Consoles) ---

    @GetMapping("/resources")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<Map<String, Object>> listResources(Authentication auth) {
        String cafeId = getCafeId(auth);

        // 1. Fetch all resources for this cafe
        List<Resource> resources = resourceRepository.findByCafeId(cafeId);

        // 2. Fetch all ACTIVE sessions for this cafe
        List<CustomerSession> activeSessions = sessionRepository.findByCafeIdAndStatus(cafeId, "active");

        // 3. Build a map of Resource ID -> Active Session Data
        Map<String, Map<String, Object>> activeMap = new HashMap<>();
        for (CustomerSession s : activeSessions) {
            for (GameSession g : s.getGames()) {
                if ("active".equals(g.getStatus())) {
                    Map<String, Object> activeData = new HashMap<>();
                    activeData.put("customer_name", s.getCustomerName());
                    activeData.put("session_id", s.getId());
                    // Convert LocalDateTime to ISO string for React to parse
                    activeData.put("start_time", g.getStartTime().toString());

                    activeMap.put(g.getResourceId(), activeData);
                }
            }
        }

        // 4. Combine them into the exact JSON format React expects
        List<Map<String, Object>> response = new ArrayList<>();
        for (Resource r : resources) {
            Map<String, Object> rMap = new HashMap<>();
            rMap.put("id", r.getId());
            rMap.put("cafe_id", r.getCafeId());
            rMap.put("name", r.getName());
            rMap.put("game_type_id", r.getGameTypeId());
            rMap.put("rate_card_id", r.getRateCardId());
            rMap.put("is_active", r.getIsActive()); // Safely mapped as is_active now!

            // This is the magic key React was looking for.
            // It will be null if there is no active session on this console.
            rMap.put("active", activeMap.get(r.getId()));

            response.add(rMap);
        }

        return response;
    }

    @PostMapping("/resources")
    public Resource createResource(@RequestBody Resource res, Authentication auth) {
        res.setCafeId(getCafeId(auth));
        return resourceRepository.save(res);
    }

    @DeleteMapping("/resources/{rid}")
    public ResponseEntity<?> deleteResource(@PathVariable String rid, Authentication auth) {
        String cafeId = getCafeId(auth);

        Resource res = resourceRepository.findById(rid).orElse(null);
        if (res == null || !res.getCafeId().equals(cafeId)) {
            return ResponseEntity.notFound().build();
        }

        resourceRepository.delete(res);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==========================================
    // --- ACCESSORIES ---
    // ==========================================

    @GetMapping("/accessories")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<Accessory> listAccessories(Authentication auth) {
        // Fetches only the accessories belonging to this specific admin's cafe
        return accessoryRepository.findByCafeId(getCafeId(auth));
    }

    @PostMapping("/accessories")
    public Accessory createAccessory(@RequestBody Accessory acc, Authentication auth) {
        acc.setCafeId(getCafeId(auth));
        return accessoryRepository.save(acc);
    }

    @PutMapping("/accessories/{aid}")
    public ResponseEntity<?> updateAccessory(@PathVariable String aid, @RequestBody Accessory updates, Authentication auth) {
        String cafeId = getCafeId(auth);

        Accessory existingAcc = accessoryRepository.findById(aid).orElse(null);
        if (existingAcc == null || !existingAcc.getCafeId().equals(cafeId)) {
            return ResponseEntity.notFound().build();
        }

        // Update fields if they were sent in the request
        if (updates.getName() != null) existingAcc.setName(updates.getName());
        if (updates.getPrice() != null) existingAcc.setPrice(updates.getPrice());

        accessoryRepository.save(existingAcc);
        return ResponseEntity.ok(existingAcc);
    }

    @DeleteMapping("/accessories/{aid}")
    public ResponseEntity<?> deleteAccessory(@PathVariable String aid, Authentication auth) {
        String cafeId = getCafeId(auth);

        Accessory acc = accessoryRepository.findById(aid).orElse(null);
        if (acc == null || !acc.getCafeId().equals(cafeId)) {
            return ResponseEntity.notFound().build();
        }

        accessoryRepository.delete(acc);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}