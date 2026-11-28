package com.progameflixx.cafectrl.controller;

// --- ALL REQUIRED IMPORTS ---

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditFormat;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.Audited;
import com.progameflixx.cafectrl.idempotency.Idempotent;
import com.progameflixx.cafectrl.dto.CheckoutRequest;
import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.logging.LogContext;
import com.progameflixx.cafectrl.repository.*;
import com.progameflixx.cafectrl.service.BillingService;
import com.progameflixx.cafectrl.service.SessionBillingService;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);

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
    private com.progameflixx.cafectrl.audit.AuditService auditService;

    @Autowired
    private com.progameflixx.cafectrl.service.SessionMembershipService sessionMembershipService;

    @Autowired
    private com.progameflixx.cafectrl.service.SessionBillingService sessionBillingService;

    @Autowired
    private MembershipCardRepository membershipCardRepository;

    @Autowired
    private com.progameflixx.cafectrl.service.CurrentUserService currentUserService;

    private String getCafeId(Authentication auth) {
        String cafeId = userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
        LogContext.putCafeId(cafeId);
        return cafeId;
    }

    @GetMapping("/active")
    public List<CustomerSession> getActiveSessions(Authentication auth) {
        List<CustomerSession> active = sessionRepository.findByCafeIdAndStatus(getCafeId(auth), "active");
        logger.debug("Fetched {} active session(s)", active.size());
        return sessionMembershipService.attachCards(active);
    }

    @PostMapping
    @Audited(action = AuditActions.SESSION_STARTED, entityType = AuditActions.ENTITY_SESSION)
    @Idempotent("SESSION_START")
    public ResponseEntity<?> startSession(@RequestBody Map<String, Object> payload, Authentication auth) {
        String cafeId = getCafeId(auth);
        String resourceId = (String) payload.get("resource_id");
        logger.info("Start session requested: customer='{}' resource={} players={} start_time={}",
                payload.get("customer_name"), resourceId, payload.get("player_count"), payload.get("start_time"));

        Resource resource = resourceId == null ? null : resourceRepository.findForUpdate(resourceId).orElse(null);
        if (resource == null || !resource.getCafeId().equals(cafeId)) {
            logger.warn("Start session rejected: resource {} not found in this cafe", resourceId);
            return ResponseEntity.badRequest().body("Resource not found");
        }
        requireResourceFree(cafeId, resource);

        // --- INSIDE startSession() ---

        // Optional membership card: validated before anything is saved, so a bad card never leaves a half-started session
        String cardId = payload.get("membership_card_id") instanceof String s && !s.isBlank() ? s : null;
        MembershipCard taggedCard = cardId == null ? null : sessionMembershipService.requireTaggable(cafeId, cardId);

        CustomerSession session = new CustomerSession();
        session.setCafeId(cafeId);
        session.setCustomerName((String) payload.get("customer_name"));
        // The UI has always sent the phone; it was previously dropped here and never saved
        if (payload.get("customer_phone") instanceof String phone && !phone.isBlank()) {
            session.setCustomerPhone(phone.trim());
        }
        session.setMembershipCardId(taggedCard != null ? taggedCard.getId() : null);
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
        logger.info("Session {} started for customer '{}' on {} (game {}, players={}, start={})",
                saved.getId(), saved.getCustomerName(), game.getResourceName(), game.getId(),
                game.getPlayerCount(), game.getStartTime());
        auditService.record(AuditRecord.of(AuditActions.SESSION_STARTED).entity(AuditActions.ENTITY_SESSION, saved.getId())
                .description("Started session for " + saved.getCustomerName() + " on " + game.getResourceName()
                        + " (" + game.getPlayerCount() + " player" + (game.getPlayerCount() == 1 ? "" : "s") + ")"
                        + (taggedCard != null ? " with card " + taggedCard.getCardNumber() : ""))
                .detail("customer_name", saved.getCustomerName())
                .detail("customer_phone", saved.getCustomerPhone())
                .detail("resource", game.getResourceName())
                .detail("resource_id", game.getResourceId())
                .detail("player_count", game.getPlayerCount())
                .detail("start_time", game.getStartTime())
                .detail("membership_card", taggedCard != null ? taggedCard.getCardNumber() : null));
        return ResponseEntity.ok(sessionMembershipService.attachCard(saved));
    }

    @GetMapping("/{sid}/bill")
    public ResponseEntity<?> previewBill(@PathVariable String sid,
                                         @RequestParam(name = "custom_time", required = false) String customTime,
                                         Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            logger.warn("Bill preview: session {} not found in this cafe", sid);
            return ResponseEntity.notFound().build();
        }

        // Backdated preview: price still-running games up to the chosen time (previously this param was ignored,
        // so the preview showed the live total while checkout billed the backdated one)
        LocalDateTime endOverride = null;
        if (customTime != null && !customTime.isBlank()) {
            try {
                endOverride = LocalDateTime.parse(customTime.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (java.time.format.DateTimeParseException e) {
                return ResponseEntity.badRequest().body(Map.of("detail", "Invalid custom_time"));
            }
        }
        // Price running games up to one fixed instant and return it, so a card-paid checkout can close at exactly
        // the amount the staff saw (otherwise a billing block could tick over between preview and close)
        LocalDateTime pricedUntil = (endOverride != null ? endOverride : LocalDateTime.now()).withNano(0);
        Map<String, Object> bill = billingService.computeSessionBill(session, pricedUntil);
        bill.put("priced_until", pricedUntil.toString());
        logger.info("Bill preview for session {} ('{}'): grand_total={} customTime={}", sid, session.getCustomerName(),
                bill.get("grand_total"), endOverride);
        return ResponseEntity.ok(bill);
    }

    // --- CHECKOUT (one transaction: bill + optional membership card payment) ---
    @PostMapping("/{sid}/checkout")
    @Audited(action = AuditActions.BILL_CREATED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("SESSION_CHECKOUT")
    public ResponseEntity<?> checkout(@PathVariable String sid, @RequestBody CheckoutRequest req, Authentication auth) {
        User actor = currentUserService.require(auth);
        SessionBillingService.CheckoutResult result = sessionBillingService.checkout(actor, sid, req);
        CustomerSession saved = result.session();
        // Audit entries (bill, discount, payments, card deduction) were written inside the checkout transaction
        return ResponseEntity.ok(sessionMembershipService.attachCard(saved));
    }

    // --- REOPEN A BILLED SESSION (admin): reverses card payments, voids unpaid debts, back to unbilled ---
    @PostMapping("/{sid}/reopen")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    @Audited(action = AuditActions.BILL_REOPENED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("BILL_REOPEN")
    public ResponseEntity<?> reopenBill(@PathVariable String sid, @RequestBody Map<String, String> body, Authentication auth) {
        User admin = currentUserService.require(auth);
        SessionBillingService.UndoResult r = sessionBillingService.reopen(admin, sid, body.get("reason"));
        return ResponseEntity.ok(sessionMembershipService.attachCard(r.session()));
    }

    // --- VOID A BILLED SESSION (admin): reverses card payments, voids unpaid debts, out of revenue ---
    @PostMapping("/{sid}/void")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    @Audited(action = AuditActions.BILL_VOIDED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("BILL_VOID")
    public ResponseEntity<?> voidBill(@PathVariable String sid, @RequestBody Map<String, String> body, Authentication auth) {
        User admin = currentUserService.require(auth);
        SessionBillingService.UndoResult r = sessionBillingService.voidBill(admin, sid, body.get("reason"));
        return ResponseEntity.ok(sessionMembershipService.attachCard(r.session()));
    }

    // --- GET SINGLE SESSION ---
    @GetMapping("/{sid}")
    public ResponseEntity<?> getSession(@PathVariable String sid, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        // Ensure the session exists and belongs to this user's cafe
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            logger.warn("Get session: {} not found in this cafe", sid);
            return ResponseEntity.notFound().build();
        }

        logger.debug("Fetched session {} (status={})", sid, session.getStatus());
        return ResponseEntity.ok(sessionMembershipService.attachCard(session));
    }

    // --- MEMBERSHIP CARD: tag / change / remove while the session is running (until billing) ---
    @PutMapping("/{sid}/membership-card")
    @Audited(action = AuditActions.SESSION_CARD_TAGGED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("SESSION_SET_CARD")
    public ResponseEntity<?> setMembershipCard(@PathVariable String sid, @RequestBody Map<String, Object> payload, Authentication auth) {
        String cafeId = getCafeId(auth);
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(cafeId)) {
            logger.warn("Set membership card: session {} not found in this cafe", sid);
            return ResponseEntity.notFound().build();
        }
        if (!"active".equals(session.getStatus())) {
            logger.warn("Set membership card rejected: session {} is {}", sid, session.getStatus());
            return ResponseEntity.badRequest().body(Map.of("detail", "The card can only be changed before the session is billed"));
        }

        String cardId = payload.get("membership_card_id") instanceof String s && !s.isBlank() ? s : null;
        String previous = session.getMembershipCardId();
        MembershipCard card = cardId == null ? null : sessionMembershipService.requireTaggable(cafeId, cardId);
        session.setMembershipCardId(card != null ? card.getId() : null);
        CustomerSession saved = sessionRepository.save(session);
        logger.info("Session {} membership card {} -> {}", sid, previous, saved.getMembershipCardId());

        String previousNumber = previous == null ? null
                : membershipCardRepository.findById(previous).map(MembershipCard::getCardNumber).orElse(previous);
        if (!java.util.Objects.equals(previous, saved.getMembershipCardId())) {
            String action = card == null ? AuditActions.SESSION_CARD_REMOVED
                    : previous == null ? AuditActions.SESSION_CARD_TAGGED : AuditActions.SESSION_CARD_CHANGED;
            String desc = card == null ? "Removed card " + previousNumber + " from " + saved.getCustomerName() + "'s session"
                    : previous == null ? "Tagged card " + card.getCardNumber() + " to " + saved.getCustomerName() + "'s session"
                    : "Changed card on " + saved.getCustomerName() + "'s session from " + previousNumber + " to " + card.getCardNumber();
            auditService.record(AuditRecord.of(action).entity(AuditActions.ENTITY_SESSION, sid).description(desc)
                    .diff(Map.of("membership_card", previousNumber == null ? "" : previousNumber),
                          Map.of("membership_card", card == null ? "" : card.getCardNumber())));
        }
        return ResponseEntity.ok(sessionMembershipService.attachCard(saved));
    }

    // --- MEMBERSHIP CARD LOOKUP for the session screens (search by name, phone or card number) ---
    @GetMapping("/membership-cards/lookup")
    public ResponseEntity<?> lookupMembershipCards(@RequestParam(name = "q", required = false) String q, Authentication auth) {
        return ResponseEntity.ok(sessionMembershipService.lookup(getCafeId(auth), q));
    }

    // --- ADD ANOTHER GAME TO EXISTING SESSION ---
    @PostMapping("/{sid}/games")
    @Audited(action = AuditActions.SESSION_GAME_ADDED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("SESSION_ADD_GAME")
    public ResponseEntity<?> addGameToSession(@PathVariable String sid, @RequestBody Map<String, Object> payload, Authentication auth) {
        String cafeId = getCafeId(auth);
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        String resourceId = (String) payload.get("resource_id");
        logger.info("Add game requested for session {}: resource={} players={} start_time={}",
                sid, resourceId, payload.get("player_count"), payload.get("start_time"));

        if (session == null || !session.getCafeId().equals(cafeId)) {
            logger.warn("Add game: session {} not found in this cafe", sid);
            return ResponseEntity.notFound().build();
        }

        if (!"active".equals(session.getStatus())) {
            logger.warn("Add game rejected: session {} is {}", sid, session.getStatus());
            return ResponseEntity.badRequest().body("Session is not active");
        }

        Resource resource = resourceId == null ? null : resourceRepository.findForUpdate(resourceId).orElse(null);
        if (resource == null || !resource.getCafeId().equals(cafeId)) {
            logger.warn("Add game rejected: resource {} not found in this cafe", resourceId);
            return ResponseEntity.badRequest().body("Resource not found");
        }
        requireResourceFree(cafeId, resource);

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
        CustomerSession savedSession = sessionRepository.save(session);
        logger.info("Game added to session {} on {} (players={}, start={})",
                sid, game.getResourceName(), game.getPlayerCount(), game.getStartTime());
        auditService.record(AuditRecord.of(AuditActions.SESSION_GAME_ADDED).entity(AuditActions.ENTITY_SESSION, sid)
                .description("Added " + game.getResourceName() + " to " + savedSession.getCustomerName() + "'s session")
                .detail("resource", game.getResourceName()).detail("resource_id", game.getResourceId())
                .detail("player_count", game.getPlayerCount()).detail("start_time", game.getStartTime()));
        return ResponseEntity.ok(savedSession);
    }

    // --- END A SPECIFIC GAME (Keep session open) ---
    @PostMapping("/{sid}/games/{gid}/end")
    @Audited(action = AuditActions.SESSION_GAME_ENDED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("SESSION_END_GAME")
    public ResponseEntity<?> endGame(@PathVariable String sid, @PathVariable String gid, @RequestBody Map<String, Object> payload, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            logger.warn("End game: session {} not found in this cafe", sid);
            return ResponseEntity.notFound().build();
        }

        boolean ended = false;
        for (GameSession g : session.getGames()) {
            if (g.getId().equals(gid) && "active".equals(g.getStatus())) {
                ended = true;
                g.setStatus("soft_closed");
                if (payload.containsKey("end_time") && payload.get("end_time") != null) {
                    String st = (String) payload.get("end_time");
                    g.setEndTime(LocalDateTime.parse(st, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } else {
                    g.setEndTime(LocalDateTime.now());
                }
                logger.info("Game {} ({}) in session {} ended at {}", gid, g.getResourceName(), sid, g.getEndTime());
                auditService.record(AuditRecord.of(AuditActions.SESSION_GAME_ENDED).entity(AuditActions.ENTITY_SESSION, sid)
                        .description("Ended " + g.getResourceName() + " for " + session.getCustomerName() + " at "
                                + AuditFormat.dateTime(g.getEndTime()))
                        .detail("resource", g.getResourceName()).detail("game_id", gid)
                        .detail("start_time", g.getStartTime()).detail("end_time", g.getEndTime()));
                break;
            }
        }
        if (!ended) {
            boolean exists = session.getGames().stream().anyMatch(g -> g.getId().equals(gid));
            logger.warn("End game rejected: game {} in session {} {}", gid, sid, exists ? "already ended" : "not found");
            throw new org.springframework.web.server.ResponseStatusException(
                    exists ? org.springframework.http.HttpStatus.CONFLICT : org.springframework.http.HttpStatus.NOT_FOUND,
                    exists ? "This game has already ended" : "Game not found");
        }
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    // --- FORCE CANCEL SESSION (Admin override, no bill) ---
    @PostMapping("/{sid}/cancel")
    @Audited(action = AuditActions.SESSION_CANCELLED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("SESSION_CANCEL")
    public ResponseEntity<?> cancelSession(@PathVariable String sid, Authentication auth) {
        CustomerSession session = sessionRepository.findForUpdate(sid).orElse(null);
        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            logger.warn("Cancel: session {} not found in this cafe", sid);
            return ResponseEntity.notFound().build();
        }
        requireActive(session, "cancelled");
        logger.info("Cancel requested for session {} ('{}', status={})", sid, session.getCustomerName(), session.getStatus());

        LocalDateTime now = LocalDateTime.now();
        String statusBefore = session.getStatus();
        List<Map<String, Object>> restored = new java.util.ArrayList<>();
        for (GameSession g : session.getGames()) {
            if ("active".equals(g.getStatus())) {
                if (g.getItems() != null) {
                    for (GameSessionItem it : g.getItems()) restored.add(Map.of("name", String.valueOf(it.getName()), "qty", it.getQty()));
                }
                if(g.getItems() != null){
                    for(GameSessionItem gameSessionItem : g.getItems()){

                        // FIX: Use the reference ID of the product, NOT the session item's ID
                        // Note: Change getRefId() to getItemId() or getInventoryItemId() depending on what you named it in your GameSessionItem class!
                        String productId = gameSessionItem.getRefId();

                        Optional<InventoryItem> invItemOptional = inventoryRepository.findById(productId);

                        if (invItemOptional.isPresent()) {
                            restoreStock(invItemOptional.get(), gameSessionItem.getQty());
                        } else {
                            logger.warn("Cancel session {}: item '{}' (ref {}) not in inventory, stock not restored",
                                    sid, gameSessionItem.getName(), productId);
                        }
                    }
                }
                g.setStatus("cancelled");
                g.setEndTime(now);
            }
        }
        session.setStatus("cancelled");
        session.setBilledAt(now);
        CustomerSession cancelled = sessionRepository.save(session);
        logger.info("Session {} cancelled", sid);
        auditService.record(AuditRecord.of(AuditActions.SESSION_CANCELLED).entity(AuditActions.ENTITY_SESSION, sid)
                .description("Cancelled " + cancelled.getCustomerName() + "'s session without a bill"
                        + (restored.isEmpty() ? "" : "; stock returned for " + restored.size() + " item line(s)"))
                .diff(Map.of("status", statusBefore), Map.of("status", "cancelled"))
                .detail("items_returned_to_stock", restored));
        return ResponseEntity.ok(cancelled);
    }

    /** A station can have only one running game at a time. Call with the resource row locked. */
    private void requireResourceFree(String cafeId, Resource resource) {
        for (CustomerSession s : sessionRepository.findByCafeIdAndStatus(cafeId, "active")) {
            for (GameSession g : s.getGames()) {
                if ("active".equals(g.getStatus()) && resource.getId().equals(g.getResourceId())) {
                    logger.warn("Start on {} rejected: already in use by session {} ('{}')", resource.getName(), s.getId(), s.getCustomerName());
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                            resource.getName() + " is already in use by " + s.getCustomerName());
                }
            }
        }
    }

    /** Refuses changes to a session that is no longer running (billed, cancelled, voided). */
    private void requireActive(CustomerSession session, String verb) {
        if (!"active".equals(session.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "This session is already " + session.getStatus() + " and can't be " + verb);
        }
    }

    private void restoreStock(InventoryItem returnedItem, int quantityReturned) {
        if (Boolean.TRUE.equals(returnedItem.getIsTrackable())) {
            // 1. It's a direct trackable item (e.g., a Can of Coke)
            returnedItem.setStock(returnedItem.getStock() + quantityReturned);
            inventoryRepository.save(returnedItem);
            logger.info("Stock restored: '{}' +{} -> {}", returnedItem.getName(), quantityReturned, returnedItem.getStock());
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
                    logger.info("Raw stock restored for '{}': '{}' +{} -> {}",
                            returnedItem.getName(), rawMaterial.getName(), totalToRestore, rawMaterial.getStock());
                }
            }
        }
    }

    // --- ADD SNACK / ACCESSORY TO GAME ---
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/{sid}/games/{gid}/items")
    @Audited(action = AuditActions.SESSION_ITEM_ADDED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("SESSION_ADD_ITEM")
    public ResponseEntity<?> addItem(@PathVariable String sid, @PathVariable String gid, @RequestBody Map<String, Object> req, Authentication auth) {
        String cafeId = getCafeId(auth);
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        logger.info("Add item requested for session {} game {}: type={} ref={} qty={}",
                sid, gid, req.get("type"), req.get("ref_id"), req.get("qty"));

        if (session == null || !session.getCafeId().equals(cafeId)) {
            logger.warn("Add item: session {} not found in this cafe", sid);
            return ResponseEntity.status(404).body(Map.of("message", "Session not found"));
        }
        requireActive(session, "changed");

        GameSession targetGame = session.getGames().stream()
                .filter(g -> g.getId().equals(gid))
                .findFirst().orElse(null);

        if (targetGame == null) {
            logger.warn("Add item: game {} not found in session {}", gid, sid);
            return ResponseEntity.status(404).body(Map.of("message", "Game not found"));
        }

        String type = (String) req.get("type");
        String refId = (String) req.get("ref_id");
        int qty = req.containsKey("qty") ? Integer.parseInt(req.get("qty").toString()) : 1;

        String itemName = "";
        double unitPrice = 0.0;

        if ("inventory".equals(type)) {
            com.progameflixx.cafectrl.entity.InventoryItem item = inventoryRepository.findById(refId).orElse(null);

            if (item == null || !item.getCafeId().equals(cafeId)) {
                logger.warn("Add item rejected: inventory item {} not found in this cafe", refId);
                return ResponseEntity.status(400).body(Map.of("message", "Inventory item not found"));
            }

            // --- THE MEANINGFUL ERROR FIX ---
            if (Boolean.TRUE.equals(item.getIsTrackable()) && item.getStock() != null) {
                if (item.getStock() < qty) {
                    // We use "message" because many React error formatters look for that key
                    String errorMsg = "Insufficient stock! " + item.getName() + " has only " + item.getStock() + " units left.";
                    logger.warn("Add item rejected for session {}: {}", sid, errorMsg);
                    return ResponseEntity.status(400).body(Map.of("message", errorMsg));
                }

                item.setStock(item.getStock() - qty);
                inventoryRepository.save(item);
                logger.info("Stock deducted: '{}' -{} -> {}", item.getName(), qty, item.getStock());
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
                            logger.warn("Add item rejected for session {}: '{}' - {}", sid, item.getName(), e.getMessage());
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
                logger.warn("Add item rejected: accessory {} not found in this cafe", refId);
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

        CustomerSession savedSession = sessionRepository.save(session);
        logger.info("Item added to session {} game {}: '{}' x{} @ {} = {}",
                sid, gid, itemName, qty, unitPrice, sessionItem.getTotal());
        auditService.record(AuditRecord.of(AuditActions.SESSION_ITEM_ADDED).entity(AuditActions.ENTITY_SESSION, sid)
                .description("Added " + itemName + " x" + qty + " (" + AuditFormat.rupees(sessionItem.getTotal())
                        + ") on " + targetGame.getResourceName() + " for " + savedSession.getCustomerName())
                .detail("type", type).detail("ref_id", refId).detail("name", itemName).detail("qty", qty)
                .detail("unit_price", unitPrice).detail("total", sessionItem.getTotal())
                .detail("resource", targetGame.getResourceName()));
        return ResponseEntity.ok(savedSession);
    }

    private void deductStock(InventoryItem item, double amountToDeduct) {
        if (item.getStock() < amountToDeduct) {
            logger.warn("Not enough raw stock for '{}': need {}, have {}", item.getName(), amountToDeduct, item.getStock());
            throw new RuntimeException("Not enough raw stock for: " + item.getName());
        }
        item.setStock((int) (item.getStock() - amountToDeduct));
        inventoryRepository.save(item);
        logger.info("Raw stock deducted: '{}' -{} -> {}", item.getName(), amountToDeduct, item.getStock());
    }

    // --- REMOVE SNACK / ACCESSORY ---
    @DeleteMapping("/{sid}/games/{gid}/items/{itemId}")
    @Audited(action = AuditActions.SESSION_ITEM_REMOVED, entityType = AuditActions.ENTITY_SESSION, entityIdParam = "sid")
    @Idempotent("SESSION_REMOVE_ITEM")
    @Transactional
    public ResponseEntity<?> removeItem(@PathVariable String sid, @PathVariable String gid, @PathVariable String itemId, Authentication auth) {
        CustomerSession session = sessionRepository.findById(sid).orElse(null);

        if (session == null || !session.getCafeId().equals(getCafeId(auth))) {
            logger.warn("Remove item: session {} not found in this cafe", sid);
            return ResponseEntity.notFound().build();
        }
        logger.info("Remove item {} requested from session {} game {}", itemId, sid, gid);
        requireActive(session, "changed");

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
                                logger.info("Stock refunded: '{}' +{} -> {}", inv.getName(), itemToRemove.getQty(), inv.getStock());
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
                                        logger.info("Raw stock refunded for '{}': '{}' +{} -> {}",
                                                inv.getName(), rawMaterial.getName(), refundQty, rawMaterial.getStock());
                                    }
                                }
                            }
                        } else {
                            logger.warn("Remove item: inventory {} for '{}' no longer exists, stock not refunded",
                                    itemToRemove.getRefId(), itemToRemove.getName());
                        }
                    }

                    // Now it is safe to remove it from the session
                    g.getItems().remove(itemToRemove);
                    logger.info("Item '{}' x{} removed from session {} game {}", itemToRemove.getName(), itemToRemove.getQty(), sid, gid);
                    auditService.record(AuditRecord.of(AuditActions.SESSION_ITEM_REMOVED).entity(AuditActions.ENTITY_SESSION, sid)
                            .description("Removed " + itemToRemove.getName() + " x" + itemToRemove.getQty() + " ("
                                    + AuditFormat.rupees(itemToRemove.getTotal()) + ") from " + g.getResourceName()
                                    + " for " + session.getCustomerName())
                            .detail("type", itemToRemove.getType()).detail("ref_id", itemToRemove.getRefId())
                            .detail("name", itemToRemove.getName()).detail("qty", itemToRemove.getQty())
                            .detail("total", itemToRemove.getTotal()).detail("resource", g.getResourceName()));
                } else {
                    logger.warn("Remove item: item {} not found in session {} game {}", itemId, sid, gid);
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.NOT_FOUND, "Item not found (it may already have been removed)");
                }
                break;
            }
        }

        return ResponseEntity.ok(sessionRepository.save(session));
    }
}