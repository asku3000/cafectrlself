package com.progameflixx.cafectrl.controller;

// --- ALL REQUIRED IMPORTS ---

import com.progameflixx.cafectrl.dto.CheckoutRequest;
import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.repository.*;
import com.progameflixx.cafectrl.service.BillingService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true", maxAge = 3600)
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
    private PendingPaymentRepository pendingPaymentRepository;

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
            game.setStartTime(LocalDateTime.parse(st, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
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
        for (PaymentSplit p : req.getPayments()) {
            if ("pending".equalsIgnoreCase(p.getMode())) {
                PendingPayment debt = new PendingPayment();
                debt.setSessionId(session.getId());
                debt.setCafeId(session.getCafeId());
                debt.setCustomerName(session.getCustomerName());
                debt.setCustomerPhone(session.getCustomerPhone());
                debt.setAmount(p.getAmount());
                debt.setStatus("PENDING");
                debt.setCreatedAt(LocalDateTime.now());
                pendingPaymentRepository.save(debt);
            }
        }
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
            game.setStartTime(LocalDateTime.parse(st, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
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
    public ResponseEntity<?> endGame(@PathVariable String sid, @PathVariable String gid, @RequestBody Map<String, Object> payload, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        for (GameSession g : session.getGames()) {
            if (g.getId().equals(gid) && "active".equals(g.getStatus())) {
                g.setStatus("soft_closed");
                if (payload.containsKey("end_time") && payload.get("end_time") != null) {
                    String st = (String) payload.get("end_time");
                    g.setEndTime(LocalDateTime.parse(st, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } else {
                    g.setEndTime(LocalDateTime.now());
                }
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
                if(g.getItems() != null){
                    for(GameSessionItem gameSessionItem : g.getItems()){

                        // FIX: Use the reference ID of the product, NOT the session item's ID
                        // Note: Change getRefId() to getItemId() or getInventoryItemId() depending on what you named it in your GameSessionItem class!
                        String productId = gameSessionItem.getRefId();

                        Optional<InventoryItem> invItemOptional = inventoryRepository.findById(productId);

                        invItemOptional.ifPresent(item -> restoreStock(item, gameSessionItem.getQty()));
                    }
                }
                g.setStatus("cancelled");
                g.setEndTime(now);
            }
        }
        session.setStatus("cancelled");
        session.setBilledAt(now);
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    private void restoreStock(InventoryItem returnedItem, int quantityReturned) {
        if (Boolean.TRUE.equals(returnedItem.getIsTrackable())) {
            // 1. It's a direct trackable item (e.g., a Can of Coke)
            returnedItem.setStock(returnedItem.getStock() + quantityReturned);
            inventoryRepository.save(returnedItem);
        } else {
            // 2. It's an untracked Menu Item (e.g., Aloo Tikki Burger).
            // We must return the raw materials to the inventory!
            if (returnedItem.getIngredients() != null && !returnedItem.getIngredients().isEmpty()) {
                for (ItemRecipe recipe : returnedItem.getIngredients()) {
                    InventoryItem rawMaterial = recipe.getRawMaterial();

                    // Calculate total raw material to restore (Cancelled 2 Burgers * 1 Patty = Restore 2 Patties)
                    double totalToRestore = recipe.getQuantityRequired() * quantityReturned;

                    // Add the stock back to the raw material
                    rawMaterial.setStock((int) (rawMaterial.getStock() + totalToRestore));
                    inventoryRepository.save(rawMaterial);
                }
            }
        }
    }

    // --- ADD SNACK / ACCESSORY TO GAME ---
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/{sid}/games/{gid}/items")
    public ResponseEntity<?> addItem(@PathVariable String sid, @PathVariable String gid, @RequestBody Map<String, Object> req, Authentication auth) {
        String cafeId = getCafeId(auth);
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        if (session == null || !session.getCafeId().equals(cafeId)) {
            return ResponseEntity.status(404).body(Map.of("message", "Session not found"));
        }

        GameSession targetGame = session.getGames().stream()
                .filter(g -> g.getId().equals(gid))
                .findFirst().orElse(null);

        if (targetGame == null) return ResponseEntity.status(404).body(Map.of("message", "Game not found"));

        String type = (String) req.get("type");
        String refId = (String) req.get("ref_id");
        int qty = req.containsKey("qty") ? Integer.parseInt(req.get("qty").toString()) : 1;

        String itemName = "";
        double unitPrice = 0.0;

        if ("inventory".equals(type)) {
            com.progameflixx.cafectrl.entity.InventoryItem item = inventoryRepository.findById(refId).orElse(null);

            if (item == null || !item.getCafeId().equals(cafeId)) {
                return ResponseEntity.status(400).body(Map.of("message", "Inventory item not found"));
            }

            // --- THE MEANINGFUL ERROR FIX ---
            if (Boolean.TRUE.equals(item.getIsTrackable()) && item.getStock() != null) {
                if (item.getStock() < qty) {
                    // We use "message" because many React error formatters look for that key
                    String errorMsg = "Insufficient stock! " + item.getName() + " has only " + item.getStock() + " units left.";
                    System.out.println("Validation Failed: " + errorMsg); // Check your terminal/logs!
                    return ResponseEntity.status(400).body(Map.of("message", errorMsg));
                }

                item.setStock(item.getStock() - qty);
                inventoryRepository.save(item);
            } else{
                // 2. It's an untracked Menu Item (e.g., Masala Maggi).
                // Check if it has a recipe attached!
                if (item.getIngredients() != null && !item.getIngredients().isEmpty()) {

                    for (ItemRecipe recipe : item.getIngredients()) {
                        InventoryItem rawMaterial = recipe.getRawMaterial();
                        // Calculate total raw material needed (Sold 2 Burgers * 1 Patty = Deduct 2 Patties)
                        double totalRequired = recipe.getQuantityRequired() * qty;
                        try {
                            deductStock(rawMaterial, totalRequired);
                        } catch (Exception e) {
                            return ResponseEntity.status(400).body(Map.of("message", e.getMessage()));
                        }
                    }
                }
            }

            itemName = item.getName();
            unitPrice = item.getPrice();

        } else if ("accessory".equals(type)) {
            com.progameflixx.cafectrl.entity.Accessory acc = accessoryRepository.findById(refId).orElse(null);
            if (acc == null || !acc.getCafeId().equals(cafeId)) {
                return ResponseEntity.status(400).body(Map.of("message", "Accessory not found"));
            }
            itemName = acc.getName();
            unitPrice = acc.getPrice();
        }

        GameSessionItem sessionItem = new GameSessionItem();
        sessionItem.setType(type);
        sessionItem.setRefId(refId);
        sessionItem.setName(itemName);
        sessionItem.setUnitPrice(unitPrice);
        sessionItem.setQty(qty);
        sessionItem.setTotal(Math.round(unitPrice * qty * 100.0) / 100.0);
        sessionItem.setAddedAt(LocalDateTime.now());
        sessionItem.setGameSession(targetGame);
        targetGame.getItems().add(sessionItem);

        return ResponseEntity.ok(sessionRepository.save(session));
    }

    private void deductStock(InventoryItem item, double amountToDeduct) {
        if (item.getStock() < amountToDeduct) {
            throw new RuntimeException("Not enough raw stock for: " + item.getName());
        }
        item.setStock((int) (item.getStock() - amountToDeduct));
        inventoryRepository.save(item);
    }

    // --- REMOVE SNACK / ACCESSORY ---
    @DeleteMapping("/{sid}/games/{gid}/items/{itemId}")
    @Transactional
    public ResponseEntity<?> removeItem(@PathVariable String sid, @PathVariable String gid, @PathVariable String itemId, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        for (GameSession g : session.getGames()) {
            if (g.getId().equals(gid)) {

                // Find the item in the list BEFORE deleting it
                com.progameflixx.cafectrl.entity.GameSessionItem itemToRemove = g.getItems().stream()
                        .filter(i -> i.getId().equals(itemId))
                        .findFirst()
                        .orElse(null);

                if (itemToRemove != null) {

                    // Refund the stock ONLY if it's an inventory item
                    if ("inventory".equalsIgnoreCase(itemToRemove.getType())) {
                        InventoryItem inv = inventoryRepository.findById(itemToRemove.getRefId()).orElse(null);

                        if (inv != null) {
                            // THE FIX: Check if it's a trackable item or a recipe!
                            if (Boolean.TRUE.equals(inv.getIsTrackable())) {
                                // Standard Item: Add the quantity back to the fridge!
                                inv.setStock(inv.getStock() + itemToRemove.getQty());
                                inventoryRepository.save(inv);
                            } else {
                                // Recipe Item: Refund the raw materials!
                                if (inv.getIngredients() != null) {
                                    for (ItemRecipe recipe : inv.getIngredients()) {
                                        InventoryItem rawMaterial = recipe.getRawMaterial();

                                        // Calculate how much raw material to refund
                                        // (Qty of ingredients per item) * (Number of items deleted)
                                        int refundQty = (int) (recipe.getQuantityRequired() * itemToRemove.getQty());

                                        rawMaterial.setStock(rawMaterial.getStock() + refundQty);
                                        inventoryRepository.save(rawMaterial);
                                    }
                                }
                            }
                        }
                    }

                    // Now it is safe to remove it from the session
                    g.getItems().remove(itemToRemove);
                }
                break;
            }
        }

        return ResponseEntity.ok(sessionRepository.save(session));
    }
}