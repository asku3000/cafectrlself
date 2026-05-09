package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.GameType;
import com.progameflixx.cafectrl.entity.RateCard;
import com.progameflixx.cafectrl.entity.Resource;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.GameTypeRepository;
import com.progameflixx.cafectrl.repository.RateCardRepository;
import com.progameflixx.cafectrl.repository.ResourceRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // Helper to get cafeId from the logged-in admin
    private String getCafeId(Authentication auth) {
        return userRepository.findById(auth.getName()).orElseThrow().getCafeId();
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
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')") // Operators need to see these too
    public List<GameType> listGameTypes(Authentication auth) {
        return gameTypeRepository.findByCafeId(getCafeId(auth));
    }

    @PostMapping("/game-types")
    public GameType createGameType(@RequestBody GameType gt, Authentication auth) {
        gt.setCafeId(getCafeId(auth));
        return gameTypeRepository.save(gt);
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

    // --- RESOURCES (Individual Seats/Consoles) ---

    @GetMapping("/resources")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<Resource> listResources(Authentication auth) {
        return resourceRepository.findAll(); // In production, filter by CafeID
    }

    @PostMapping("/resources")
    public Resource createResource(@RequestBody Resource res, Authentication auth) {
        res.setCafeId(getCafeId(auth));
        return resourceRepository.save(res);
    }
}
