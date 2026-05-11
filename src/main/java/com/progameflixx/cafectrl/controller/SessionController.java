package com.progameflixx.cafectrl.controller;

// --- ALL REQUIRED IMPORTS ---

import com.progameflixx.cafectrl.dto.CheckoutRequest;
import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.GameSession;
import com.progameflixx.cafectrl.entity.GameSessionItem;
import com.progameflixx.cafectrl.entity.Resource;
import com.progameflixx.cafectrl.repository.*;
import com.progameflixx.cafectrl.service.BillingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"}, allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/sessions")
@PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
public class SessionController {

    @Autowired
    private CustomerSessionRepository sessionRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BillingService billingService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private AccessoryRepository accessoryRepository;

    @Autowired
    private com.progameflixx.cafectrl.service.AuditService auditService;

    private String getCafeId(Authentication auth) {
        return userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
    }

    @GetMapping("/active")
    public List<CustomerSession> getActiveSessions(Authentication auth) {
        return sessionRepository.findByCafeIdAndStatus(getCafeId(auth), "active");
    }

    @PostMapping
    public ResponseEntity<?> startSession(@RequestBody Map<String, Object> payload, Authentication auth) {
        String cafeId = getCafeId(auth);
        String resourceId = (String) payload.get("resource_id");

        Resource resource = resourceRepository.findById(resourceId).orElse(null);
        if (resource == null || !resource.getCafeId().equals(cafeId)) {
            return ResponseEntity.badRequest().body("Resource not found");
        }

        // --- INSIDE startSession() ---

        CustomerSession session = new CustomerSession();
        session.setCafeId(cafeId);
        session.setCustomerName((String) payload.get("customer_name"));
        session.setOperatorName(userRepository.findByEmail(auth.getName()).get().getName());
        session.setCreatedAt(LocalDateTime.now());
        session.setStatus("active");

        GameSession game = new GameSession();
        game.setResourceId(resource.getId());
        game.setResourceName(resource.getName());
        game.setGameTypeId(resource.getGameTypeId());
        game.setRateCardId(resource.getRateCardId());

        if (payload.containsKey("start_time") && payload.get("start_time") != null) {
            String st = (String) payload.get("start_time");
            // Parse React's UTC string and securely convert it to Local IST time!
            game.setStartTime(Instant.parse(st).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        } else {
            game.setStartTime(LocalDateTime.now());
        }

        game.setPlayerCount(payload.containsKey("player_count") ? (Integer) payload.get("player_count") : 1);
        game.setStatus("active");

        session.addGame(game);
        CustomerSession saved = sessionRepository.save(session);
        // RECORD AUDIT
        auditService.log(auth, "SESSION_START", saved.getId(), Map.of(
                "customer", saved.getCustomerName(),
                "resource", game.getResourceName()
        ));
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{sid}/bill")
    public ResponseEntity<?> previewBill(@PathVariable String sid, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> bill = billingService.computeSessionBill(session);
        return ResponseEntity.ok(bill);
    }

    // --- CHECKOUT ---
    @PostMapping("/{sid}/checkout")
    public ResponseEntity<?> checkout(@PathVariable String sid, @RequestBody CheckoutRequest req, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        LocalDateTime now = LocalDateTime.now();

        for (GameSession g : session.getGames()) {
            if ("active".equals(g.getStatus())) {
                g.setStatus("soft_closed");
                g.setEndTime(now);
            }
        }

        // Let Spring do the heavy lifting! No ObjectMapper needed.
        session.setAdjustment(req.getAdjustment() != null ? req.getAdjustment() : 0.0);

        Map<String, Object> bill = billingService.computeSessionBill(session);
        double grandTotal = (Double) bill.get("grand_total");

        session.setStatus("billed");
        session.setBilledAt(now);
        session.setBillTotal(grandTotal);

        // Simply set the payments directly from the perfectly mapped request object
        if (req.getPayments() != null) {
            session.setPayments(req.getPayments());
        }
        CustomerSession saved = sessionRepository.save(session);
        // RECORD AUDIT
        auditService.log(auth, "SESSION_CHECKOUT", saved.getId(), Map.of(
                "customer", saved.getCustomerName(),
                "total", saved.getBillTotal(),
                "payments", saved.getPayments()
        ));
        return ResponseEntity.ok(saved);
    }

    // --- GET SINGLE SESSION ---
    @GetMapping("/{sid}")
    public ResponseEntity<?> getSession(@PathVariable String sid, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        // Ensure the session exists and belongs to this user's cafe
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(session);
    }

    // --- ADD ANOTHER GAME TO EXISTING SESSION ---
    @PostMapping("/{sid}/games")
    public ResponseEntity<?> addGameToSession(@PathVariable String sid, @RequestBody Map<String, Object> payload, Authentication auth) {
        String cafeId = getCafeId(auth);
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        if (session == null || !session.getCafeId().equals(cafeId)) {
            return ResponseEntity.notFound().build();
        }

        if (!"active".equals(session.getStatus())) {
            return ResponseEntity.badRequest().body("Session is not active");
        }

        String resourceId = (String) payload.get("resource_id");
        Resource resource = resourceRepository.findById(resourceId).orElse(null);
        if (resource == null || !resource.getCafeId().equals(cafeId)) {
            return ResponseEntity.badRequest().body("Resource not found");
        }

        GameSession game = new GameSession();
        game.setResourceId(resource.getId());
        game.setResourceName(resource.getName());
        game.setGameTypeId(resource.getGameTypeId());
        game.setRateCardId(resource.getRateCardId());

        // Parse React's UTC string and securely convert it to Local IST time!
        if (payload.containsKey("start_time") && payload.get("start_time") != null) {
            String st = (String) payload.get("start_time");
            game.setStartTime(java.time.Instant.parse(st).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        } else {
            game.setStartTime(LocalDateTime.now());
        }

        game.setPlayerCount(payload.containsKey("player_count") ? (Integer) payload.get("player_count") : 1);
        game.setStatus("active");

        session.addGame(game); // Hibernate automatically saves this to the database!
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    // --- END A SPECIFIC GAME (Keep session open) ---
    @PostMapping("/{sid}/games/{gid}/end")
    public ResponseEntity<?> endGame(@PathVariable String sid, @PathVariable String gid, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        for (GameSession g : session.getGames()) {
            if (g.getId().equals(gid) && "active".equals(g.getStatus())) {
                g.setStatus("soft_closed");
                g.setEndTime(LocalDateTime.now());
                break;
            }
        }
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    // --- FORCE CANCEL SESSION (Admin override, no bill) ---
    @PostMapping("/{sid}/cancel")
    public ResponseEntity<?> cancelSession(@PathVariable String sid, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        LocalDateTime now = LocalDateTime.now();
        for (GameSession g : session.getGames()) {
            if ("active".equals(g.getStatus())) {
                g.setStatus("cancelled");
                g.setEndTime(now);
            }
        }
        session.setStatus("cancelled");
        session.setBilledAt(now);
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    // --- ADD SNACK / ACCESSORY TO GAME ---
    @PostMapping("/{sid}/games/{gid}/items")
    public ResponseEntity<?> addItem(@PathVariable String sid, @PathVariable String gid, @RequestBody Map<String, Object> req, Authentication auth) {
        String cafeId = getCafeId(auth);
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        if (session == null || !session.getCafeId().equals(cafeId)) {
            return ResponseEntity.notFound().build();
        }

        // 1. Find the specific game
        GameSession targetGame = null;
        for (GameSession g : session.getGames()) {
            if (g.getId().equals(gid)) {
                targetGame = g;
                break;
            }
        }
        if (targetGame == null) return ResponseEntity.notFound().build();

        // 2. Get item details from the request
        String type = (String) req.get("type"); // "inventory" or "accessory"
        String refId = (String) req.get("ref_id");
        int qty = req.containsKey("qty") ? Integer.parseInt(req.get("qty").toString()) : 1;

        String itemName = "";
        double unitPrice = 0.0;

        // 3. Look up the item price and decrement stock if it's inventory
        if ("inventory".equals(type)) {
            com.progameflixx.cafectrl.entity.InventoryItem item = inventoryRepository.findById(refId).orElse(null);
            if (item == null || !item.getCafeId().equals(cafeId)) return ResponseEntity.badRequest().body("Inventory item not found");

            itemName = item.getName();
            unitPrice = item.getPrice();

            // Decrement stock
            if (item.getStock() != null) {
                item.setStock(item.getStock() - qty);
                inventoryRepository.save(item);
            }
        } else if ("accessory".equals(type)) {
            com.progameflixx.cafectrl.entity.Accessory acc = accessoryRepository.findById(refId).orElse(null);
            if (acc == null || !acc.getCafeId().equals(cafeId)) return ResponseEntity.badRequest().body("Accessory not found");

            itemName = acc.getName();
            unitPrice = acc.getPrice();
        } else {
            return ResponseEntity.badRequest().body("Invalid item type");
        }

        // 4. Create the line item
        GameSessionItem sessionItem = new GameSessionItem();
        sessionItem.setType(type);
        sessionItem.setRefId(refId);
        sessionItem.setName(itemName);
        sessionItem.setUnitPrice(unitPrice);
        sessionItem.setQty(qty);
        sessionItem.setTotal(Math.round(unitPrice * qty * 100.0) / 100.0);
        sessionItem.setAddedAt(LocalDateTime.now());

        // Helper method (Make sure GameSession.java has this, or just use .add)
        sessionItem.setGameSession(targetGame);
        targetGame.getItems().add(sessionItem);

        return ResponseEntity.ok(sessionRepository.save(session));
    }

    // --- REMOVE SNACK / ACCESSORY ---
    @DeleteMapping("/{sid}/games/{gid}/items/{itemId}")
    public ResponseEntity<?> removeItem(@PathVariable String sid, @PathVariable String gid, @PathVariable String itemId, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        for (GameSession g : session.getGames()) {
            if (g.getId().equals(gid)) {
                // Safely remove the item from the list
                g.getItems().removeIf(item -> item.getId().equals(itemId));
                break;
            }
        }

        return ResponseEntity.ok(sessionRepository.save(session));
    }
}