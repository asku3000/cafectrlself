package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.dto.CheckoutRequest;
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

    public SessionBillingService(CustomerSessionRepository sessionRepository, PendingPaymentRepository pendingPaymentRepository,
                                 MembershipCardRepository cardRepository, BillingService billingService,
                                 MembershipCardService cardService) {
        this.sessionRepository = sessionRepository;
        this.pendingPaymentRepository = pendingPaymentRepository;
        this.cardRepository = cardRepository;
        this.billingService = billingService;
        this.cardService = cardService;
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
        return new CheckoutResult(saved, grandTotal, cardPayment);
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
        UndoResult undo = undoPayments(admin, session, "Bill reopened: " + why);
        session.setStatus("active");
        session.setBilledAt(null);
        session.setBillTotal(0.0);
        session.setPayments(new ArrayList<>());
        session.setNotes(appendNote(session.getNotes(), "Reopened by " + admin.getName() + ": " + why));
        CustomerSession saved = sessionRepository.save(session);
        logger.info("Session {} reopened by {}: reason='{}' reversals={} voidedDebts={}",
                sessionId, admin.getName(), why, undo.reversals().size(), undo.voidedDebts());
        return new UndoResult(saved, undo.reversals(), undo.voidedDebts());
    }

    /** Admin: cancels a billed session's bill. It drops out of revenue reports; card payments are reversed. */
    @Transactional
    public UndoResult voidBill(User admin, String sessionId, String reason) {
        String why = requireReason(reason);
        CustomerSession session = lockBilled(admin, sessionId);
        UndoResult undo = undoPayments(admin, session, "Bill voided: " + why);
        session.setStatus("voided");
        session.setNotes(appendNote(session.getNotes(), "Voided by " + admin.getName() + ": " + why));
        CustomerSession saved = sessionRepository.save(session);
        logger.info("Session {} voided by {}: reason='{}' reversals={} voidedDebts={}",
                sessionId, admin.getName(), why, undo.reversals().size(), undo.voidedDebts());
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
