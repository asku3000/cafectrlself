package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.dto.AddItemRequest;
import com.progameflixx.cafectrl.dto.StartSessionRequest;

import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.InventoryRepository;
import com.progameflixx.cafectrl.repository.ResourceRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/sessions")
// Operators, Cafe Admins, and Super Admins can all access these routes
@PreAuthorize("hasAnyRole('OPERATOR', 'CAFE_ADMIN', 'SUPER_ADMIN')")
public class SessionController {

    @Autowired
    private CustomerSessionRepository sessionRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private UserRepository userRepository; // To get current user details

    // Helper to get current user's cafeId (Translates your `cafe_filter` logic)
    private String getCurrentCafeId(Authentication auth) {
        User user = userRepository.findById(auth.getName()).orElseThrow();
        return user.getCafeId();
    }

    // --- 1. LIST ACTIVE SESSIONS ---
    @GetMapping("/active")
    public ResponseEntity<List<CustomerSession>> listActiveSessions(Authentication auth) {
        String cafeId = getCurrentCafeId(auth);
        List<CustomerSession> activeSessions = sessionRepository.findByCafeIdAndStatus(cafeId, "active");
        return ResponseEntity.ok(activeSessions);
    }

    // --- 2. START A SESSION ---
    @PostMapping("/")
    @Transactional
    public ResponseEntity<?> startSession(@RequestBody StartSessionRequest req, Authentication auth) {
        String cafeId = getCurrentCafeId(auth);
        User operator = userRepository.findById(auth.getName()).orElseThrow();

        // 1. Verify Resource Exists
        Resource resource = resourceRepository.findByIdAndCafeId(req.getResourceId(), cafeId)
                .orElseThrow(() -> new RuntimeException("Resource not found"));

        // (In a full production app, you'd add a query here to ensure the resource isn't already "active")

        // 2. Build the Game Session (The PC time)
        GameSession game = new GameSession();
        game.setResourceId(resource.getId());
        game.setResourceName(resource.getName());
        game.setGameTypeId(resource.getGameTypeId());
        game.setRateCardId(resource.getRateCardId());
        game.setStartTime(req.getStartTime() != null ? req.getStartTime() : Instant.now());

        // 3. Build the Parent Customer Session (The Bill)
        CustomerSession session = new CustomerSession();
        session.setCafeId(cafeId);
        session.setCustomerName(req.getCustomerName());
        session.setCustomerPhone(req.getCustomerPhone());
        session.setOperatorId(operator.getId());
        session.setOperatorName(operator.getName());

        // Link them together for MySQL JPA
        game.setCustomerSession(session);
        session.getGames().add(game);

        // Save parent (JPA automatically saves the child GameSession too!)
        CustomerSession savedSession = sessionRepository.save(session);
        return ResponseEntity.ok(savedSession);
    }

    // --- 3. ADD AN ITEM (SNACK/DRINK) TO A GAME ---
    @PostMapping("/{sid}/games/{gid}/items")
    @Transactional
    public ResponseEntity<?> addItem(
            @PathVariable String sid,
            @PathVariable String gid,
            @RequestBody AddItemRequest req,
            Authentication auth) {

        String cafeId = getCurrentCafeId(auth);

        // 1. Fetch the master session
        CustomerSession session = sessionRepository.findById(sid)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        // Ensure security: Does this session belong to this cafe?
        if (!session.getCafeId().equals(cafeId)) return ResponseEntity.status(403).build();

        // 2. Find the specific game inside the session
        GameSession targetGame = session.getGames().stream()
                .filter(g -> g.getId().equals(gid))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Game not found"));

        // 3. Handle Inventory Logic
        String itemName;
        Double unitPrice;

        if ("inventory".equals(req.getType())) {
            InventoryItem item = inventoryRepository.findByIdAndCafeId(req.getRefId(), cafeId)
                    .orElseThrow(() -> new RuntimeException("Inventory item not found"));

            itemName = item.getName();
            unitPrice = item.getPrice();

            // Deduct stock in the database
            item.setStock(item.getStock() - req.getQty());
            inventoryRepository.save(item);
        } else {
            throw new RuntimeException("Accessory logic goes here"); // Skipped for brevity
        }

        // 4. Create the Item Record
        GameSessionItem sessionItem = new GameSessionItem();
        sessionItem.setGameSession(targetGame); // Link to parent game
        sessionItem.setType(req.getType());
        sessionItem.setRefId(req.getRefId());
        sessionItem.setName(itemName);
        sessionItem.setUnitPrice(unitPrice);
        sessionItem.setQty(req.getQty());
        sessionItem.setTotal(unitPrice * req.getQty());

        // Add item to game and save the master session (Cascades down)
        targetGame.getItems().add(sessionItem);
        CustomerSession updatedSession = sessionRepository.save(session);

        return ResponseEntity.ok(updatedSession);
    }

    // --- 4. CHECKOUT (CLOSE SESSION) ---
    @PostMapping("/{sid}/checkout")
    @Transactional
    public ResponseEntity<?> checkout(@PathVariable String sid, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if ("billed".equals(session.getStatus())) {
            return ResponseEntity.badRequest().body("Session already billed");
        }

        // Auto-close any active games
        Instant now = Instant.now();
        for (GameSession game : session.getGames()) {
            if ("active".equals(game.getStatus())) {
                game.setStatus("soft_closed");
                game.setEndTime(now);
            }
        }

        // (You would call your BillingService here to calculate the final total)
        // billingService.computeSessionBill(session);

        session.setStatus("billed");
        session.setBilledAt(now);
        // session.setBillTotal(calculatedTotal);

        return ResponseEntity.ok(sessionRepository.save(session));
    }
}
