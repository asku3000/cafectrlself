package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.idempotency.Idempotent;
import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditDiff;
import com.progameflixx.cafectrl.audit.AuditFormat;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import com.progameflixx.cafectrl.audit.Audited;
import com.progameflixx.cafectrl.logging.LogContext;

import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 1. ADDED CORS TO PREVENT REACT CRASHES
@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('CAFE_ADMIN')")
public class CafeAdminController {

    private static final Logger logger = LoggerFactory.getLogger(CafeAdminController.class);

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

    @Autowired
    private AuditService audit;

    // Helper to get cafeId from the logged-in admin
    private String getCafeId(Authentication auth) {
        // Assuming auth.getName() returns the user's email or ID that matches your User entity primary key
        String cafeId = userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
        LogContext.putCafeId(cafeId);
        return cafeId;
    }

    // --- CAFE PROFILE (The Missing APIs) ---

    @GetMapping("/cafe")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public ResponseEntity<?> getMyCafe(Authentication auth) {
        String cafeId = getCafeId(auth);
        logger.debug("Fetching cafe profile");
        return cafeRepository.findById(cafeId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    logger.warn("Cafe profile {} not found", cafeId);
                    return ResponseEntity.notFound().build();
                });
    }

    @Idempotent("CAFE_UPDATE")
    @PutMapping("/cafe")
    @Audited(action = AuditActions.CAFE_UPDATED, entityType = AuditActions.ENTITY_CAFE)
    public ResponseEntity<?> updateCafe(@RequestBody Cafe updates, Authentication auth) {
        String cafeId = getCafeId(auth);

        Cafe existingCafe = cafeRepository.findById(cafeId).orElse(null);
        if (existingCafe == null) {
            logger.warn("Update cafe: cafe {} not found", cafeId);
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> before = cafeSnapshot(existingCafe);
        // Only update fields that were actually sent in the request
        if (updates.getName() != null) existingCafe.setName(updates.getName());
        if (updates.getPhone() != null) existingCafe.setPhone(updates.getPhone());
        if (updates.getAddress() != null) existingCafe.setAddress(updates.getAddress());
        existingCafe.setSetupComplete(updates.isSetupComplete());

        cafeRepository.save(existingCafe);
        logger.info("Cafe profile updated: name='{}' phone={} setupComplete={}",
                existingCafe.getName(), existingCafe.getPhone(), existingCafe.isSetupComplete());
        audit.record(AuditRecord.of(AuditActions.CAFE_UPDATED).entity(AuditActions.ENTITY_CAFE, cafeId)
                .description("Updated cafe profile '" + existingCafe.getName() + "'")
                .diff(before, cafeSnapshot(existingCafe)));
        return ResponseEntity.ok(existingCafe);
    }

    // --- STAFF MANAGEMENT ---

    @GetMapping("/staff")
    public List<User> listStaff(Authentication auth) {
        List<User> staff = userRepository.findByCafeIdAndRole(getCafeId(auth), "operator");
        logger.debug("Listed {} staff member(s)", staff.size());
        return staff;
    }

    @Idempotent("STAFF_CREATE")
    @PostMapping("/staff")
    @Audited(action = AuditActions.STAFF_CREATED, entityType = AuditActions.ENTITY_USER)
    public User createOperator(@RequestBody User staffReq, Authentication auth) {
        staffReq.setCafeId(getCafeId(auth));
        staffReq.setRole("OPERATOR");
        staffReq.setPassword(passwordEncoder.encode(staffReq.getPassword()));
        staffReq.setActive(true);
        User created = userRepository.save(staffReq);
        logger.info("Operator created: id={} email={} name='{}' permissions={}",
                created.getId(), created.getEmail(), created.getName(), created.getPermissions());
        audit.record(AuditRecord.of(AuditActions.STAFF_CREATED).entity(AuditActions.ENTITY_USER, created.getId())
                .description("Added staff " + created.getName() + " (" + created.getEmail() + ") with permissions "
                        + created.getPermissions())
                .diff(null, staffSnapshot(created)));
        return created;
    }

    @Idempotent("STAFF_DELETE")
    @DeleteMapping("/staff/{uid}")
    @Audited(action = AuditActions.STAFF_DELETED, entityType = AuditActions.ENTITY_USER, entityIdParam = "uid")
    public ResponseEntity<?> deleteOperator(@PathVariable String uid, Authentication auth) {
        String cafeId = getCafeId(auth);
        User op = userRepository.findForUpdate(uid).orElse(null);

        // Security Check: only staff belonging to this admin's cafe may be deleted
        if (op == null || !cafeId.equals(op.getCafeId())) {
            logger.warn("Delete operator rejected: {} not found in this cafe", uid);
            return ResponseEntity.status(404).body(Map.of("message", "Operator not found"));
        }

        logger.info("Deleting operator {} ({}, '{}')", uid, op.getEmail(), op.getName());
        Map<String, Object> before = staffSnapshot(op);
        userRepository.delete(op);
        audit.record(AuditRecord.of(AuditActions.STAFF_DELETED).entity(AuditActions.ENTITY_USER, uid)
                .description("Deleted staff " + op.getName() + " (" + op.getEmail() + ")")
                .diff(before, null));
        return ResponseEntity.ok().build();
    }

    @Idempotent("STAFF_UPDATE")
    @PutMapping("/staff/{id}")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    @Audited(action = AuditActions.STAFF_UPDATED, entityType = AuditActions.ENTITY_USER, entityIdParam = "id")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> updateStaff(@PathVariable String id, @RequestBody User req, Authentication auth) {
        String cafeId = getCafeId(auth);

        // 1. Find the existing operator (must belong to this admin's cafe)
        logger.info("Update operator {} requested", id);
        User op = userRepository.findForUpdate(id).orElse(null);
        if (op == null || !cafeId.equals(op.getCafeId())) {
            logger.warn("Update operator rejected: {} not found in this cafe", id);
            return ResponseEntity.status(404).body(Map.of("message", "Operator not found"));
        }

        Map<String, Object> before = staffSnapshot(op);
        boolean wasActive = op.isActive();
        // 2. Prevent unique email crashes
        if (req.getEmail() != null && !req.getEmail().equalsIgnoreCase(op.getEmail())) {
            boolean emailExists = userRepository.existsByEmailAndIdNot(req.getEmail(), id);
            if (emailExists) {
                logger.warn("Update operator {} rejected: email {} already exists", id, req.getEmail());
                return ResponseEntity.badRequest().body(Map.of("message", "Email already exists"));
            }
            op.setEmail(req.getEmail());
        }

        op.setName(req.getName());

        // 3. Prevent NullPointerException on password encoding
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            op.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        // 4. Safe collection handling for permissions
        if (req.getPermissions() != null) {
            op.getPermissions().clear();
            op.getPermissions().addAll(req.getPermissions());
        }
        op.setActive(req.isActive());
        User saved = userRepository.save(op);
        logger.info("Operator {} updated: email={} name='{}' active={} permissions={} passwordChanged={}",
                id, saved.getEmail(), saved.getName(), saved.isActive(), saved.getPermissions(),
                req.getPassword() != null && !req.getPassword().isBlank());
        boolean passwordChanged = req.getPassword() != null && !req.getPassword().isBlank();
        String action = wasActive == saved.isActive() ? AuditActions.STAFF_UPDATED
                : saved.isActive() ? AuditActions.STAFF_REACTIVATED : AuditActions.STAFF_DEACTIVATED;
        String verb = AuditActions.STAFF_UPDATED.equals(action) ? "Edited" : saved.isActive() ? "Reactivated" : "Deactivated";
        audit.record(AuditRecord.of(action).entity(AuditActions.ENTITY_USER, id)
                .description(verb + " staff " + saved.getName() + " (" + saved.getEmail() + ")"
                        + (passwordChanged ? "; login was reset" : ""))
                .diff(before, staffSnapshot(saved))
                .detail("login_reset", passwordChanged));
        return ResponseEntity.ok(saved);
    }

    // --- GAME TYPES (PC, PS5, etc.) ---

    @GetMapping("/game-types")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<GameType> listGameTypes(Authentication auth) {
        return gameTypeRepository.findByCafeId(getCafeId(auth));
    }

    @Idempotent("GAME_TYPE_CREATE")
    @PostMapping("/game-types")
    @Audited(action = AuditActions.GAME_TYPE_CREATED, entityType = AuditActions.ENTITY_GAME_TYPE)
    public GameType createGameType(@RequestBody GameType gt, Authentication auth) {
        gt.setCafeId(getCafeId(auth));
        GameType saved = gameTypeRepository.save(gt);
        logger.info("Game type saved: id={} name='{}'", saved.getId(), saved.getName());
        audit.record(AuditRecord.of(AuditActions.GAME_TYPE_CREATED).entity(AuditActions.ENTITY_GAME_TYPE, saved.getId())
                .description("Added game type '" + saved.getName() + "'"));
        return saved;
    }

    @Idempotent("GAME_TYPE_DELETE")
    @DeleteMapping("/game-types/{gid}")
    @Audited(action = AuditActions.GAME_TYPE_DELETED, entityType = AuditActions.ENTITY_GAME_TYPE, entityIdParam = "gid")
    public ResponseEntity<?> deleteGameType(@PathVariable String gid, Authentication auth) {
        String cafeId = getCafeId(auth);

        GameType gt = gameTypeRepository.findForUpdate(gid).orElse(null);

        // Security Check: Make sure it exists AND belongs to this specific cafe
        if (gt == null || !gt.getCafeId().equals(cafeId)) {
            logger.warn("Delete game type: {} not found in this cafe", gid);
            return ResponseEntity.notFound().build();
        }

        gameTypeRepository.delete(gt);
        logger.info("Game type deleted: id={} name='{}'", gid, gt.getName());
        audit.record(AuditRecord.of(AuditActions.GAME_TYPE_DELETED).entity(AuditActions.ENTITY_GAME_TYPE, gid)
                .description("Deleted game type '" + gt.getName() + "'"));
        return ResponseEntity.ok(Map.of("ok", true)); // Matches Python's {"ok": True}
    }

    // --- RATE CARDS (Pricing Slabs) ---

    @GetMapping("/rate-cards")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<RateCard> listRateCards(Authentication auth) {
        return rateCardRepository.findByCafeId(getCafeId(auth));
    }

    /** Always creates a new rate card (an id in the body is ignored; edits use PUT). */
    @Idempotent("RATE_CARD_CREATE")
    @PostMapping("/rate-cards")
    @Audited(action = AuditActions.RATE_CARD_CREATED, entityType = AuditActions.ENTITY_RATE_CARD)
    public RateCard createRateCard(@RequestBody RateCard rc, Authentication auth) {
        rc.setId(null);
        rc.setCafeId(getCafeId(auth));
        RateCard saved = rateCardRepository.save(rc);
        audit.record(AuditRecord.of(AuditActions.RATE_CARD_CREATED).entity(AuditActions.ENTITY_RATE_CARD, saved.getId())
                .description("Created rate card '" + saved.getName() + "'")
                .diff(null, rateCardSnapshot(saved)));
        logger.info("Rate card created: id={} grace={} interval={}", saved.getId(), saved.getGraceMinutes(), saved.getBillingIntervalMinutes());
        return saved;
    }

    /** Edits a rate card of this cafe (the UI's edit dialog calls this; it had no endpoint before). */
    @Idempotent("RATE_CARD_UPDATE")
    @PutMapping("/rate-cards/{rid}")
    @Audited(action = AuditActions.RATE_CARD_UPDATED, entityType = AuditActions.ENTITY_RATE_CARD, entityIdParam = "rid")
    public ResponseEntity<?> updateRateCard(@PathVariable String rid, @RequestBody RateCard updates, Authentication auth) {
        String cafeId = getCafeId(auth);
        RateCard rc = rateCardRepository.findForUpdate(rid).orElse(null);
        if (rc == null || !cafeId.equals(rc.getCafeId())) {
            logger.warn("Update rate card: {} not found in this cafe", rid);
            return ResponseEntity.status(404).body(Map.of("detail", "Rate card not found"));
        }
        Map<String, Object> before = rateCardSnapshot(rc);
        if (updates.getName() != null) rc.setName(updates.getName());
        if (updates.getGameTypeId() != null) rc.setGameTypeId(updates.getGameTypeId());
        if (updates.getWeekdayPrices() != null) rc.setWeekdayPrices(updates.getWeekdayPrices());
        if (updates.getBillingIntervalMinutes() != null) rc.setBillingIntervalMinutes(updates.getBillingIntervalMinutes());
        if (updates.getGraceMinutes() != null) rc.setGraceMinutes(updates.getGraceMinutes());
        if (updates.getPlayerCounts() != null) rc.setPlayerCounts(updates.getPlayerCounts());
        RateCard saved = rateCardRepository.save(rc);
        audit.record(AuditRecord.of(AuditActions.RATE_CARD_UPDATED).entity(AuditActions.ENTITY_RATE_CARD, rid)
                .description("Changed rate card '" + saved.getName() + "'")
                .diff(before, rateCardSnapshot(saved)));
        logger.info("Rate card updated: id={} grace={} interval={}", rid, saved.getGraceMinutes(), saved.getBillingIntervalMinutes());
        return ResponseEntity.ok(saved);
    }

    @Idempotent("RATE_CARD_DELETE")
    @DeleteMapping("/rate-cards/{rid}")
    @Audited(action = AuditActions.RATE_CARD_DELETED, entityType = AuditActions.ENTITY_RATE_CARD, entityIdParam = "rid")
    public ResponseEntity<?> deleteRateCard(@PathVariable String rid, Authentication auth) {
        String cafeId = getCafeId(auth);

        RateCard rc = rateCardRepository.findForUpdate(rid).orElse(null);
        if (rc == null || !rc.getCafeId().equals(cafeId)) {
            logger.warn("Delete rate card: {} not found in this cafe", rid);
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> before = rateCardSnapshot(rc);
        rateCardRepository.delete(rc);
        logger.info("Rate card deleted: id={}", rid);
        audit.record(AuditRecord.of(AuditActions.RATE_CARD_DELETED).entity(AuditActions.ENTITY_RATE_CARD, rid)
                .description("Deleted rate card '" + rc.getName() + "'").diff(before, null));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // --- RESOURCES (Individual Seats/Consoles) ---

    @GetMapping("/resources")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public List<Map<String, Object>> listResources(Authentication auth) {
        String cafeId = getCafeId(auth);

        List<Resource> resources = resourceRepository.findByCafeId(cafeId);
        List<CustomerSession> activeSessions = sessionRepository.findByCafeIdAndStatus(cafeId, "active");

        // FIX: Remove the 'Z' from the pattern here
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        Map<String, Map<String, Object>> activeMap = new HashMap<>();
        for (CustomerSession s : activeSessions) {
            for (GameSession g : s.getGames()) {
                if ("active".equals(g.getStatus())) {
                    Map<String, Object> activeData = new HashMap<>();
                    activeData.put("customer_name", s.getCustomerName());
                    activeData.put("session_id", s.getId());
                    activeData.put("start_time", g.getStartTime().format(formatter));
                    activeMap.put(g.getResourceId(), activeData);
                }
            }
        }

        List<Map<String, Object>> response = new ArrayList<>();
        for (Resource r : resources) {
            Map<String, Object> rMap = new HashMap<>();
            rMap.put("id", r.getId());
            rMap.put("cafe_id", r.getCafeId());
            rMap.put("name", r.getName());
            rMap.put("game_type_id", r.getGameTypeId());
            rMap.put("rate_card_id", r.getRateCardId());
            rMap.put("is_active", r.getIsActive());
            rMap.put("active", activeMap.get(r.getId()));
            response.add(rMap);
        }

        logger.debug("Listed {} resource(s), {} currently in use", resources.size(), activeMap.size());
        return response;
    }

    /** Always creates a new resource (an id in the body is ignored; edits use PUT). */
    @Idempotent("RESOURCE_CREATE")
    @PostMapping("/resources")
    @Audited(action = AuditActions.RESOURCE_CREATED, entityType = AuditActions.ENTITY_RESOURCE)
    public Resource createResource(@RequestBody Resource res, Authentication auth) {
        res.setId(java.util.UUID.randomUUID().toString());
        res.setCafeId(getCafeId(auth));
        Resource saved = resourceRepository.save(res);
        audit.record(AuditRecord.of(AuditActions.RESOURCE_CREATED).entity(AuditActions.ENTITY_RESOURCE, saved.getId())
                .description("Added resource '" + saved.getName() + "'")
                .diff(null, resourceSnapshot(saved)));
        logger.info("Resource created: id={} name='{}' gameType={} rateCard={}",
                saved.getId(), saved.getName(), saved.getGameTypeId(), saved.getRateCardId());
        return saved;
    }

    /** Edits a resource of this cafe (the UI's edit dialog calls this; it had no endpoint before). */
    @Idempotent("RESOURCE_UPDATE")
    @PutMapping("/resources/{rid}")
    @Audited(action = AuditActions.RESOURCE_UPDATED, entityType = AuditActions.ENTITY_RESOURCE, entityIdParam = "rid")
    public ResponseEntity<?> updateResource(@PathVariable String rid, @RequestBody Resource updates, Authentication auth) {
        String cafeId = getCafeId(auth);
        Resource res = resourceRepository.findForUpdate(rid).orElse(null);
        if (res == null || !cafeId.equals(res.getCafeId())) {
            logger.warn("Update resource: {} not found in this cafe", rid);
            return ResponseEntity.status(404).body(Map.of("detail", "Resource not found"));
        }
        Map<String, Object> before = resourceSnapshot(res);
        if (updates.getName() != null) res.setName(updates.getName());
        if (updates.getGameTypeId() != null) res.setGameTypeId(updates.getGameTypeId());
        if (updates.getRateCardId() != null) res.setRateCardId(updates.getRateCardId());
        if (updates.getIsActive() != null) res.setIsActive(updates.getIsActive());
        Resource saved = resourceRepository.save(res);
        audit.record(AuditRecord.of(AuditActions.RESOURCE_UPDATED).entity(AuditActions.ENTITY_RESOURCE, rid)
                .description("Edited resource '" + saved.getName() + "'")
                .diff(before, resourceSnapshot(saved)));
        logger.info("Resource updated: id={} name='{}' gameType={} rateCard={}",
                rid, saved.getName(), saved.getGameTypeId(), saved.getRateCardId());
        return ResponseEntity.ok(saved);
    }

    @Idempotent("RESOURCE_DELETE")
    @DeleteMapping("/resources/{rid}")
    @Audited(action = AuditActions.RESOURCE_DELETED, entityType = AuditActions.ENTITY_RESOURCE, entityIdParam = "rid")
    public ResponseEntity<?> deleteResource(@PathVariable String rid, Authentication auth) {
        String cafeId = getCafeId(auth);

        Resource res = resourceRepository.findForUpdate(rid).orElse(null);
        if (res == null || !res.getCafeId().equals(cafeId)) {
            logger.warn("Delete resource: {} not found in this cafe", rid);
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> before = resourceSnapshot(res);
        resourceRepository.delete(res);
        logger.info("Resource deleted: id={} name='{}'", rid, res.getName());
        audit.record(AuditRecord.of(AuditActions.RESOURCE_DELETED).entity(AuditActions.ENTITY_RESOURCE, rid)
                .description("Deleted resource '" + res.getName() + "'").diff(before, null));
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

    @Idempotent("ACCESSORY_CREATE")
    @PostMapping("/accessories")
    @Audited(action = AuditActions.ACCESSORY_CREATED, entityType = AuditActions.ENTITY_ACCESSORY)
    public Accessory createAccessory(@RequestBody Accessory acc, Authentication auth) {
        acc.setCafeId(getCafeId(auth));
        Accessory saved = accessoryRepository.save(acc);
        logger.info("Accessory created: id={} name='{}' price={}", saved.getId(), saved.getName(), saved.getPrice());
        audit.record(AuditRecord.of(AuditActions.ACCESSORY_CREATED).entity(AuditActions.ENTITY_ACCESSORY, saved.getId())
                .description("Added accessory '" + saved.getName() + "' at " + AuditFormat.rupees(saved.getPrice()))
                .diff(null, AuditDiff.snapshot("name", saved.getName(), "price", saved.getPrice())));
        return saved;
    }

    @Idempotent("ACCESSORY_UPDATE")
    @PutMapping("/accessories/{aid}")
    @Audited(action = AuditActions.ACCESSORY_UPDATED, entityType = AuditActions.ENTITY_ACCESSORY, entityIdParam = "aid")
    public ResponseEntity<?> updateAccessory(@PathVariable String aid, @RequestBody Accessory updates, Authentication auth) {
        String cafeId = getCafeId(auth);

        Accessory existingAcc = accessoryRepository.findForUpdate(aid).orElse(null);
        if (existingAcc == null || !existingAcc.getCafeId().equals(cafeId)) {
            logger.warn("Update accessory: {} not found in this cafe", aid);
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> before = AuditDiff.snapshot("name", existingAcc.getName(), "price", existingAcc.getPrice());
        // Update fields if they were sent in the request
        if (updates.getName() != null) existingAcc.setName(updates.getName());
        if (updates.getPrice() != null) existingAcc.setPrice(updates.getPrice());

        accessoryRepository.save(existingAcc);
        logger.info("Accessory updated: id={} name='{}' price={}", aid, existingAcc.getName(), existingAcc.getPrice());
        audit.record(AuditRecord.of(AuditActions.ACCESSORY_UPDATED).entity(AuditActions.ENTITY_ACCESSORY, aid)
                .description("Edited accessory '" + existingAcc.getName() + "'")
                .diff(before, AuditDiff.snapshot("name", existingAcc.getName(), "price", existingAcc.getPrice())));
        return ResponseEntity.ok(existingAcc);
    }

    @Idempotent("ACCESSORY_DELETE")
    @DeleteMapping("/accessories/{aid}")
    @Audited(action = AuditActions.ACCESSORY_DELETED, entityType = AuditActions.ENTITY_ACCESSORY, entityIdParam = "aid")
    public ResponseEntity<?> deleteAccessory(@PathVariable String aid, Authentication auth) {
        String cafeId = getCafeId(auth);

        Accessory acc = accessoryRepository.findForUpdate(aid).orElse(null);
        if (acc == null || !acc.getCafeId().equals(cafeId)) {
            logger.warn("Delete accessory: {} not found in this cafe", aid);
            return ResponseEntity.notFound().build();
        }

        accessoryRepository.delete(acc);
        logger.info("Accessory deleted: id={} name='{}'", aid, acc.getName());
        audit.record(AuditRecord.of(AuditActions.ACCESSORY_DELETED).entity(AuditActions.ENTITY_ACCESSORY, aid)
                .description("Deleted accessory '" + acc.getName() + "'")
                .diff(AuditDiff.snapshot("name", acc.getName(), "price", acc.getPrice()), null));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // =====================================================================
    // Audit snapshots: the fields compared for before/after. Never includes passwords.
    // =====================================================================

    private static Map<String, Object> cafeSnapshot(Cafe c) {
        return AuditDiff.snapshot("name", c.getName(), "phone", c.getPhone(), "address", c.getAddress(),
                "setup_complete", c.isSetupComplete());
    }

    private static Map<String, Object> staffSnapshot(User u) {
        return AuditDiff.snapshot("name", u.getName(), "email", u.getEmail(), "role", u.getRole(),
                "permissions", u.getPermissions() == null ? List.of() : new ArrayList<>(u.getPermissions()),
                "is_active", u.isActive());
    }

    private static Map<String, Object> rateCardSnapshot(RateCard r) {
        return AuditDiff.snapshot("name", r.getName(), "game_type_id", r.getGameTypeId(),
                "billing_interval_minutes", r.getBillingIntervalMinutes(), "grace_minutes", r.getGraceMinutes(),
                "player_counts", r.getPlayerCounts() == null ? null : new ArrayList<>(r.getPlayerCounts()),
                "weekday_prices", r.getWeekdayPrices() == null ? null : new java.util.LinkedHashMap<>(r.getWeekdayPrices()));
    }

    private static Map<String, Object> resourceSnapshot(Resource r) {
        return AuditDiff.snapshot("name", r.getName(), "game_type_id", r.getGameTypeId(),
                "rate_card_id", r.getRateCardId(), "is_active", r.getIsActive());
    }
}
