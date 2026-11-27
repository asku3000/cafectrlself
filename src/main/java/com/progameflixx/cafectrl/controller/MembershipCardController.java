package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipCardView;
import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.service.AuditService;
import com.progameflixx.cafectrl.service.CurrentUserService;
import com.progameflixx.cafectrl.service.MembershipCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.function.Supplier;

/**
 * Membership cards. Selling, recharging and viewing need CAFE_ADMIN or the operator "memberships" permission;
 * manual adjustment, expiry extension and status changes are CAFE_ADMIN only and need a reason.
 */
@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/membership/cards")
@PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
public class MembershipCardController {

    private static final Logger logger = LoggerFactory.getLogger(MembershipCardController.class);

    private final MembershipCardService cardService;
    private final CurrentUserService currentUser;
    private final AuditService auditService;
    private final CustomerSessionRepository sessionRepository;

    public MembershipCardController(MembershipCardService cardService, CurrentUserService currentUser, AuditService auditService,
                                    CustomerSessionRepository sessionRepository) {
        this.cardService = cardService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.sessionRepository = sessionRepository;
    }

    @GetMapping
    public List<MembershipCardView> list(@RequestParam(name = "q", required = false) String q,
                                         @RequestParam(name = "status", required = false) String status,
                                         @RequestParam(name = "plan_id", required = false) String planId,
                                         @RequestParam(name = "expiring_within_days", required = false) Integer expiringWithinDays,
                                         @RequestParam(name = "low_balance", defaultValue = "false") boolean lowBalance,
                                         Authentication auth) {
        User user = currentUser.require(auth);
        CurrentUserService.requirePermission(user, CurrentUserService.PERM_MEMBERSHIPS);
        return cardService.list(user.getCafeId(),
                new MembershipCardService.CardFilter(q, status, planId, expiringWithinDays, lowBalance));
    }

    @GetMapping("/summary")
    public MembershipCardService.Summary summary(Authentication auth) {
        User user = currentUser.require(auth);
        CurrentUserService.requirePermission(user, CurrentUserService.PERM_MEMBERSHIPS);
        return cardService.summary(user.getCafeId());
    }

    @GetMapping("/{id}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> detail(@PathVariable String id, Authentication auth) {
        User user = currentUser.require(auth);
        CurrentUserService.requirePermission(user, CurrentUserService.PERM_MEMBERSHIPS);
        Map<String, Object> body = new HashMap<>();
        body.put("card", cardService.view(user.getCafeId(), id));
        body.put("ledger", cardService.ledger(user.getCafeId(), id));
        body.put("reconciled", cardService.reconcile(user.getCafeId(), id));
        body.put("sessions", linkedSessions(user.getCafeId(), id, (List<MembershipLedgerEntry>) body.get("ledger")));
        return body;
    }

    @PostMapping
    public MembershipCardView issue(@RequestBody MembershipCardRequests.Issue req, Authentication auth) {
        User user = currentUser.require(auth);
        CurrentUserService.requirePermission(user, CurrentUserService.PERM_MEMBERSHIPS);
        MembershipCard card = idempotent(() -> cardService.issue(user, req), "ISSUE", user, req.getRequestId());
        auditService.log(auth, "MEMBERSHIP_CARD_ISSUE", card.getId(), Map.of(
                "card_number", card.getCardNumber(), "customer", card.getCustomerName(),
                "plan", card.getPlanName(), "paid_paise", card.getPlanLoadAmountPaise(),
                "bonus_paise", card.getPlanBonusAmountPaise(),
                "payment_mode", String.valueOf(req.getPaymentMode())));
        return cardService.toView(card);
    }

    @PostMapping("/{id}/recharge")
    public MembershipCardView recharge(@PathVariable String id, @RequestBody MembershipCardRequests.Recharge req, Authentication auth) {
        User user = currentUser.require(auth);
        CurrentUserService.requirePermission(user, CurrentUserService.PERM_MEMBERSHIPS);
        MembershipCard card = idempotent(() -> cardService.recharge(user, id, req), "RECHARGE", user, req.getRequestId());
        auditService.log(auth, "MEMBERSHIP_CARD_RECHARGE", card.getId(), Map.of(
                "card_number", card.getCardNumber(), "plan", card.getPlanName(),
                "paid_paise", card.getPlanLoadAmountPaise(), "bonus_paise", card.getPlanBonusAmountPaise(),
                "payment_mode", String.valueOf(req.getPaymentMode())));
        return cardService.toView(card);
    }

    @PostMapping("/{id}/adjust")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    public MembershipCardView adjust(@PathVariable String id, @RequestBody MembershipCardRequests.Adjust req, Authentication auth) {
        User user = currentUser.require(auth);
        MembershipCard card = cardService.adjust(user, id, req);
        auditService.log(auth, "MEMBERSHIP_CARD_ADJUST", card.getId(), Map.of(
                "card_number", card.getCardNumber(), "direction", String.valueOf(req.getDirection()),
                "paid_paise", req.getPaidPaise() == null ? 0 : req.getPaidPaise(),
                "bonus_paise", req.getBonusPaise() == null ? 0 : req.getBonusPaise(),
                "reason", req.getReason()));
        return cardService.toView(card);
    }

    @PostMapping("/{id}/extend")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    public MembershipCardView extend(@PathVariable String id, @RequestBody MembershipCardRequests.Extend req, Authentication auth) {
        User user = currentUser.require(auth);
        MembershipCard card = cardService.extendExpiry(user, id, req);
        auditService.log(auth, "MEMBERSHIP_CARD_EXTEND", card.getId(), Map.of(
                "card_number", card.getCardNumber(), "new_expiry", String.valueOf(card.getExpiryDate()),
                "reason", req.getReason()));
        return cardService.toView(card);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    public MembershipCardView changeStatus(@PathVariable String id, @RequestBody MembershipCardRequests.StatusChange req, Authentication auth) {
        User user = currentUser.require(auth);
        MembershipCard card = cardService.changeStatus(user, id, req);
        auditService.log(auth, "MEMBERSHIP_CARD_" + String.valueOf(req.getAction()).toUpperCase(), card.getId(), Map.of(
                "card_number", card.getCardNumber(), "reason", req.getReason()));
        return cardService.toView(card);
    }

    /**
     * Bills / sessions linked to the card: every session it paid for (from the ledger, so a bill stays listed even
     * if the card was untagged later) plus sessions it is currently tagged to. Newest first, with the net amount
     * paid from this card (debits minus reversals).
     */
    private List<Map<String, Object>> linkedSessions(String cafeId, String cardId, List<MembershipLedgerEntry> ledger) {
        Map<String, Long> netPaid = new HashMap<>();
        for (MembershipLedgerEntry e : ledger) {
            if (e.getSessionId() != null && (e.getEntryType() == MembershipLedgerEntry.Type.BILL_DEBIT
                    || e.getEntryType() == MembershipLedgerEntry.Type.REVERSAL)) {
                netPaid.merge(e.getSessionId(), -e.getAmountPaise(), Long::sum);
            }
        }
        Map<String, CustomerSession> byId = new LinkedHashMap<>();
        sessionRepository.findAllById(netPaid.keySet()).forEach(s -> byId.put(s.getId(), s));
        sessionRepository.findByCafeIdAndMembershipCardIdOrderByCreatedAtDesc(cafeId, cardId).forEach(s -> byId.put(s.getId(), s));
        return byId.values().stream()
                .filter(s -> cafeId.equals(s.getCafeId()))
                .sorted(Comparator.comparing(CustomerSession::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("customer_name", s.getCustomerName());
                    m.put("status", s.getStatus());
                    m.put("created_at", s.getCreatedAt());
                    m.put("billed_at", s.getBilledAt());
                    m.put("bill_total", s.getBillTotal());
                    m.put("card_paid_paise", netPaid.getOrDefault(s.getId(), 0L));
                    m.put("currently_tagged", cardId.equals(s.getMembershipCardId()));
                    return m;
                })
                .toList();
    }

    /**
     * Two identical requests (same request_id) racing past the "already processed?" check: the second hits the
     * unique idempotency key and rolls back. Return the card the first one produced instead of an error.
     */
    private MembershipCard idempotent(Supplier<MembershipCard> action, String operation, User user, String requestId) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            return cardService.findProcessedRequest(operation, user.getCafeId(), requestId)
                    .map(card -> {
                        logger.info("{} request {} was a concurrent duplicate; returning card {}", operation, requestId, card.getId());
                        return card;
                    })
                    .orElseThrow(() -> {
                        logger.error("{} failed on a data constraint: {}", operation, e.getMostSpecificCause().getMessage());
                        return new ResponseStatusException(HttpStatus.CONFLICT, "Could not save, please try again");
                    });
        }
    }
}
