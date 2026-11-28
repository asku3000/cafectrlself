package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditDiff;
import com.progameflixx.cafectrl.audit.AuditFormat;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import com.progameflixx.cafectrl.dto.CheckoutRequest;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.MembershipCardRepository;
import com.progameflixx.cafectrl.repository.PendingPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checkout, reopen and void for customer sessions, each in ONE transaction:
 * the session row is locked first, then (if used) the membership card row. The bill is computed exactly as
 * before by {@link BillingService}; only the final grand total can be paid from a card.
 */
@Service
public class SessionBillingService {

    private static final Logger logger = LoggerFactory.getLogger(SessionBillingService.class);
    static final String MEMBERSHIP_MODE = "membership";

    private final CustomerSessionRepository sessionRepository;
    private final PendingPaymentRepository pendingPaymentRepository;
    private final MembershipCardRepository cardRepository;
    private final BillingService billingService;
    private final MembershipCardService cardService;
    private final AuditService audit;
    private final AuditEventRepository auditEvents;

    public SessionBillingService(CustomerSessionRepository sessionRepository, PendingPaymentRepository pendingPaymentRepository,
                                 MembershipCardRepository cardRepository, BillingService billingService,
                                 MembershipCardService cardService, AuditService audit, AuditEventRepository auditEvents) {
        this.sessionRepository = sessionRepository;
        this.pendingPaymentRepository = pendingPaymentRepository;
        this.cardRepository = cardRepository;
        this.billingService = billingService;
        this.cardService = cardService;
        this.audit = audit;
        this.auditEvents = auditEvents;
    }

    /** Result of a checkout: the saved session plus the computed bill (for audit). */
    public record CheckoutResult(CustomerSession session, double grandTotal, PaymentSplit cardPayment) {}

    @Transactional
    public CheckoutResult checkout(User actor, String sessionId, CheckoutRequest req) {
        CustomerSession session = lockOwned(actor, sessionId);
        if (!"active".equals(session.getStatus())) {
            // Double-click / retry / second device: never bill twice
            logger.warn("Checkout rejected: session {} is already {}", sessionId, session.getStatus());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This session is already " + session.getStatus());
        }
        List<PaymentSplit> payments = req.getPayments() != null ? new ArrayList<>(req.getPayments()) : new ArrayList<>();
        if (payments.stream().anyMatch(p -> p.getMode() != null && MEMBERSHIP_MODE.equalsIgnoreCase(p.getMode().trim()))) {
            throw bad("Card payments are set with membership_amount_paise, not as a payment split");
        }
        logger.info("Checkout requested for session {} ('{}'): adjustment={} manualBilledAt={} payments={} card={}",
                sessionId, session.getCustomerName(), req.getAdjustment(), req.getManualBilledAt(), payments,
                req.getMembershipAmountPaise());

        // ---- unchanged bill calculation ----
        LocalDateTime effectiveClosingTime = req.getManualBilledAt() != null ? req.getManualBilledAt() : LocalDateTime.now();
        for (GameSession g : session.getGames()) {
            if ("active".equals(g.getStatus())) {
                g.setStatus("soft_closed");
                g.setEndTime(effectiveClosingTime);
            }
        }
        session.setAdjustment(req.getAdjustment() != null ? req.getAdjustment() : 0.0);
        Map<String, Object> bill = billingService.computeSessionBill(session);
        double grandTotal = (Double) bill.get("grand_total");

        int billSeq = (session.getBillSeq() == null ? 0 : session.getBillSeq()) + 1;
        session.setBillSeq(billSeq);

        // ---- optional card payment, on the FINAL amount only ----
        PaymentSplit cardPayment = null;
        long cardPaise = req.getMembershipAmountPaise() == null ? 0 : req.getMembershipAmountPaise();
        if (cardPaise < 0) throw bad("Card amount can't be negative");
        if (cardPaise > 0) {
            if (session.getMembershipCardId() == null) throw bad("No membership card is tagged to this session");
            long grandPaise = MembershipMath.rupeesToPaise(grandTotal);
            if (cardPaise > grandPaise) {
                throw bad("Card amount " + MembershipMath.paiseToRupees(cardPaise) + " is more than the bill total " + grandTotal);
            }
            long otherPaise = payments.stream().mapToLong(p -> MembershipMath.rupeesToPaise(p.getAmount())).sum();
            if (otherPaise + cardPaise != grandPaise) {
                throw bad("Payments don't add up: card " + MembershipMath.paiseToRupees(cardPaise) + " + other "
                        + MembershipMath.paiseToRupees(otherPaise) + " must equal the total " + grandTotal);
            }
            MembershipLedgerEntry debit = cardService.debitForBill(actor, session.getCafeId(), session.getMembershipCardId(),
                    sessionId, billSeq, cardPaise, session.getCustomerName());
            MembershipCard card = cardRepository.findById(session.getMembershipCardId()).orElseThrow();
            cardPayment = new PaymentSplit(MEMBERSHIP_MODE, MembershipMath.paiseToRupees(cardPaise));
            cardPayment.setCardId(card.getId());
            cardPayment.setCardNumber(card.getCardNumber());
            cardPayment.setLedgerEntryId(debit.getId());
            cardPayment.setAmountPaise(cardPaise);
            cardPayment.setCardBalanceAfterPaise(debit.getBalanceAfterPaise());
            payments.add(0, cardPayment);
        }

        session.setStatus("billed");
        session.setBilledAt(effectiveClosingTime);
        session.setBillTotal(grandTotal);
        if (req.getNotes() != null) session.setNotes(req.getNotes());
        if (req.getPayments() != null || cardPayment != null) session.setPayments(payments);
        CustomerSession saved = sessionRepository.save(session);
        logger.info("Session {} billed: total={} card={} billedAt={} bill#={}", saved.getId(), grandTotal,
                cardPayment != null ? cardPayment.getAmount() : 0, effectiveClosingTime, billSeq);

        for (PaymentSplit p : payments) {
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
                logger.info("Pending payment {} created for session {} ('{}'): amount={}",
                        debt.getId(), session.getId(), session.getCustomerName(), debt.getAmount());
            }
        }
        auditCheckout(actor, saved, bill, payments, billSeq);
        return new CheckoutResult(saved, grandTotal, cardPayment);
    }

    // =====================================================================
    // Audit (same transaction as the bill: a bill can never exist without its audit trail)
    // =====================================================================

    private void auditCheckout(User actor, CustomerSession s, Map<String, Object> bill, List<PaymentSplit> payments, int billSeq) {
        String billNo = AuditFormat.billNo(s.getId());
        String where = resourcesOf(s);
        double adjustment = s.getAdjustment() == null ? 0.0 : s.getAdjustment();
        Map<String, Object> now = billSnapshot(s.getBillTotal(), adjustment, payments);

        AuditRecord created = AuditRecord.of(billSeq > 1 ? AuditActions.BILL_EDITED : AuditActions.BILL_CREATED)
                .actor(actor).entity(AuditActions.ENTITY_SESSION, s.getId())
                .description((billSeq > 1 ? "Re-closed " : "Closed ") + "Bill " + billNo + " for " + s.getCustomerName()
                        + where + ": total " + AuditFormat.rupees(s.getBillTotal()))
                .detail("bill_seq", billSeq)
                .detail("subtotal", bill.get("subtotal"))
                .detail("adjustment", adjustment)
                .detail("total", s.getBillTotal())
                .detail("billed_at", s.getBilledAt())
                .detail("notes", s.getNotes());
        if (billSeq > 1) {
            // what the bill looked like before it was reopened (recorded by BILL_REOPENED)
            previousBill(s.getId()).ifPresent(prev -> created.diff(prev, now));
        }
        audit.recordInTransaction(created);

        if (adjustment != 0.0) {
            boolean discount = adjustment < 0;
            audit.recordInTransaction(AuditRecord.of(discount ? AuditActions.BILL_DISCOUNT_APPLIED : AuditActions.BILL_ADJUSTED)
                    .actor(actor).entity(AuditActions.ENTITY_SESSION, s.getId())
                    .description((discount ? "Applied " + AuditFormat.rupees(-adjustment) + " discount on Bill "
                            : "Added " + AuditFormat.rupees(adjustment) + " manual adjustment on Bill ") + billNo + where)
                    .detail("adjustment", adjustment)
                    .detail("subtotal", bill.get("subtotal"))
                    .detail("total", s.getBillTotal()));
        }

        if (!payments.isEmpty()) {
            audit.recordInTransaction(AuditRecord.of(AuditActions.PAYMENT_RECORDED)
                    .actor(actor).entity(AuditActions.ENTITY_SESSION, s.getId())
                    .description("Payment for Bill " + billNo + ": " + describePayments(payments))
                    .detail("payments", paymentList(payments))
                    .detail("total", s.getBillTotal()));
        }
    }

    private void auditUndo(String action, User admin, CustomerSession s, String reason, Map<String, Object> previous,
                           String newStatus, UndoResult undo) {
        String billNo = AuditFormat.billNo(s.getId());
        String verb = AuditActions.BILL_VOIDED.equals(action) ? "Voided" : "Reopened";
        StringBuilder desc = new StringBuilder(verb + " Bill " + billNo + " for " + s.getCustomerName() + resourcesOf(s)
                + " (was " + AuditFormat.rupees((Double) previous.get("total")) + ")");
        long credited = undo.reversals().stream().mapToLong(MembershipCardService.Reversal::creditedPaise).sum();
        if (credited > 0) desc.append("; ").append(AuditFormat.paise(credited)).append(" returned to card");
        if (undo.voidedDebts() > 0) desc.append("; ").append(undo.voidedDebts()).append(" pending amount(s) voided");
        audit.recordInTransaction(AuditRecord.of(action)
                .actor(admin).entity(AuditActions.ENTITY_SESSION, s.getId())
                .description(desc.toString())
                .reason(reason)
                .diff(AuditDiff.snapshot("status", "billed"), AuditDiff.snapshot("status", newStatus))
                .detail("previous", previous)
                .detail("voided_pending_debts", undo.voidedDebts())
                .detail("card_reversals", undo.reversals().stream().map(r -> Map.of(
                        "card_number", r.cardNumber(), "credited_paise", r.creditedPaise(),
                        "re_forfeited_paise", r.reForfeitedPaise())).toList()));
    }

    /** The bill before its last reopen, from the BILL_REOPENED audit entry (null-safe). */
    @SuppressWarnings("unchecked")
    private java.util.Optional<Map<String, Object>> previousBill(String sessionId) {
        return auditEvents.findByEntityTypeAndEntityIdOrderByIdDesc(AuditActions.ENTITY_SESSION, sessionId).stream()
                .filter(e -> AuditActions.BILL_REOPENED.equals(e.getAction()) && e.getDetails() != null)
                .findFirst()
                .map(e -> e.getDetails().get("previous"))
                .filter(p -> p instanceof Map)
                .map(p -> (Map<String, Object>) p);
    }

    private static Map<String, Object> billSnapshot(Double total, double adjustment, List<PaymentSplit> payments) {
        return AuditDiff.snapshot("total", total, "adjustment", adjustment, "payments", paymentList(payments));
    }

    static List<Map<String, Object>> paymentList(List<PaymentSplit> payments) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PaymentSplit p : payments) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("mode", p.getMode());
            m.put("amount", p.getAmount());
            if (p.getCardNumber() != null) m.put("card_number", p.getCardNumber());
            out.add(m);
        }
        return out;
    }

    static String describePayments(List<PaymentSplit> payments) {
        List<String> parts = new ArrayList<>();
        for (PaymentSplit p : payments) {
            String mode = MEMBERSHIP_MODE.equalsIgnoreCase(p.getMode())
                    ? "CARD " + (p.getCardNumber() != null ? p.getCardNumber() : "")
                    : String.valueOf(p.getMode()).toUpperCase();
            parts.add(mode.trim() + " " + AuditFormat.rupees(p.getAmount()));
        }
        return String.join(" + ", parts);
    }

    private static String resourcesOf(CustomerSession s) {
        List<String> names = s.getGames().stream().map(GameSession::getResourceName)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return names.isEmpty() ? "" : " (" + String.join(", ", names) + ")";
    }

    public record UndoResult(CustomerSession session, List<MembershipCardService.Reversal> reversals, int voidedDebts) {}

    /**
     * Admin: puts a billed session back to unbilled so the bill can be corrected and closed again.
     * Card payments are reversed and the bill's unpaid pending debts are voided.
     */
    @Transactional
    public UndoResult reopen(User admin, String sessionId, String reason) {
        String why = requireReason(reason);
        CustomerSession session = lockBilled(admin, sessionId);
        Map<String, Object> previous = billSnapshot(session.getBillTotal(),
                session.getAdjustment() == null ? 0.0 : session.getAdjustment(), session.getPayments());
        UndoResult undo = undoPayments(admin, session, "Bill reopened: " + why);
        session.setStatus("active");
        session.setBilledAt(null);
        session.setBillTotal(0.0);
        session.setPayments(new ArrayList<>());
        session.setNotes(appendNote(session.getNotes(), "Reopened by " + admin.getName() + ": " + why));
        CustomerSession saved = sessionRepository.save(session);
        logger.info("Session {} reopened by {}: reason='{}' reversals={} voidedDebts={}",
                sessionId, admin.getName(), why, undo.reversals().size(), undo.voidedDebts());
        auditUndo(AuditActions.BILL_REOPENED, admin, saved, why, previous, "active", undo);
        return new UndoResult(saved, undo.reversals(), undo.voidedDebts());
    }

    /** Admin: cancels a billed session's bill. It drops out of revenue reports; card payments are reversed. */
    @Transactional
    public UndoResult voidBill(User admin, String sessionId, String reason) {
        String why = requireReason(reason);
        CustomerSession session = lockBilled(admin, sessionId);
        Map<String, Object> previous = billSnapshot(session.getBillTotal(),
                session.getAdjustment() == null ? 0.0 : session.getAdjustment(), session.getPayments());
        UndoResult undo = undoPayments(admin, session, "Bill voided: " + why);
        session.setStatus("voided");
        session.setNotes(appendNote(session.getNotes(), "Voided by " + admin.getName() + ": " + why));
        CustomerSession saved = sessionRepository.save(session);
        logger.info("Session {} voided by {}: reason='{}' reversals={} voidedDebts={}",
                sessionId, admin.getName(), why, undo.reversals().size(), undo.voidedDebts());
        auditUndo(AuditActions.BILL_VOIDED, admin, saved, why, previous, "voided", undo);
        return new UndoResult(saved, undo.reversals(), undo.voidedDebts());
    }

    private UndoResult undoPayments(User admin, CustomerSession session, String reason) {
        List<PendingPayment> debts = pendingPaymentRepository.findBySessionId(session.getId());
        if (debts.stream().anyMatch(d -> "CLEARED".equalsIgnoreCase(d.getStatus()))) {
            throw bad("Part of this bill's pending amount was already settled; it can't be reopened or voided");
        }
        int voided = 0;
        for (PendingPayment d : debts) {
            if ("PENDING".equalsIgnoreCase(d.getStatus())) {
                d.setStatus("VOIDED");
                d.setNotes(appendNote(d.getNotes(), reason));
                pendingPaymentRepository.save(d);
                voided++;
            }
        }
        List<MembershipCardService.Reversal> reversals =
                cardService.reverseBillDebits(admin, session.getCafeId(), session.getId(), reason);
        return new UndoResult(session, reversals, voided);
    }

    private CustomerSession lockOwned(User actor, String sessionId) {
        CustomerSession s = sessionRepository.findForUpdate(sessionId).orElse(null);
        if (s == null || !s.getCafeId().equals(actor.getCafeId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        return s;
    }

    private CustomerSession lockBilled(User admin, String sessionId) {
        CustomerSession s = lockOwned(admin, sessionId);
        if (!"billed".equals(s.getStatus())) throw bad("Only a billed session can be reopened or voided (this one is " + s.getStatus() + ")");
        return s;
    }

    private static String requireReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) throw bad("A reason is required");
        if (r.length() > 300) throw bad("Reason must be at most 300 characters");
        return r;
    }

    private static String appendNote(String existing, String line) {
        String base = existing == null || existing.isBlank() ? "" : existing + "\n";
        return base + "[" + LocalDateTime.now().withNano(0) + "] " + line;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
