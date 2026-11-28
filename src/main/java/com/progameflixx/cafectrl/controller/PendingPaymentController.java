package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditDiff;
import com.progameflixx.cafectrl.audit.AuditFormat;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import com.progameflixx.cafectrl.audit.Audited;
import com.progameflixx.cafectrl.idempotency.Idempotent;
import com.progameflixx.cafectrl.logging.LogContext;

import com.progameflixx.cafectrl.dto.DebtClearRequest;
import com.progameflixx.cafectrl.entity.PaymentSplit;
import com.progameflixx.cafectrl.entity.PendingPayment;
import com.progameflixx.cafectrl.repository.PendingPaymentRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pending-payments")
@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true")
public class PendingPaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PendingPaymentController.class);

    @Autowired
    private PendingPaymentRepository pendingPaymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditService audit;

    @GetMapping
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public ResponseEntity<?> getActivePending(Authentication auth) {
        String cafeId = getCafeId(auth); // Your context helper method
        List<PendingPayment> pending = pendingPaymentRepository.findByCafeIdAndStatus(cafeId, "PENDING");
        logger.debug("Fetched {} active pending payment(s)", pending.size());
        return ResponseEntity.ok(pending);
    }

    @PostMapping("/{id}/clear")
    @Audited(action = AuditActions.PENDING_PAYMENT_SETTLED, entityType = AuditActions.ENTITY_PENDING_PAYMENT, entityIdParam = "id")
    @Idempotent("PENDING_SETTLE")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    @Transactional // <-- CRITICAL: If the server crashes on row 2, the whole split rolls back instantly!
    public ResponseEntity<?> clearPayment(@PathVariable Long id, @RequestBody DebtClearRequest req, Authentication auth) {
        String cafeId = getCafeId(auth);

        logger.info("Clear pending payment {} requested: payments={}", id, req.getPayments());
        // Locked: two settlements of the same debt (two devices, retries) run one after the other, and the
        // second one then sees CLEARED and is refused
        PendingPayment existingDebt = pendingPaymentRepository.findForUpdate(id).orElse(null);

        // Security Check: the debt must belong to the caller's cafe
        if (existingDebt == null || !cafeId.equals(existingDebt.getCafeId())) {
            logger.warn("Clear pending payment: {} not found in this cafe", id);
            return ResponseEntity.notFound().build();
        }

        if ("CLEARED".equalsIgnoreCase(existingDebt.getStatus())) {
            logger.warn("Clear pending payment {} rejected: already cleared", id);
            return ResponseEntity.badRequest().body("This debt record has already been settled.");
        }

        if (req.getPayments() == null || req.getPayments().isEmpty()) {
            logger.warn("Clear pending payment {} rejected: no payment breakdown", id);
            return ResponseEntity.badRequest().body("Payment breakdown is required.");
        }

        // 1. Double-Check Math: Total assigned MUST equal the exact old debt
        double incomingTotal = req.getPayments().stream()
                .mapToDouble(p -> p.getAmount() != null ? p.getAmount() : 0.0)
                .sum();

        if (Math.abs(incomingTotal - existingDebt.getAmount()) > 0.01) {
            logger.warn("Clear pending payment {} rejected: allocated {} but debt is {}", id, incomingTotal, existingDebt.getAmount());
            return ResponseEntity.badRequest().body("Math mismatch! You must allocate exactly " + existingDebt.getAmount());
        }

        // 2. Separate incoming splits into "Money collected today" vs "Debt carried forward"
        List<PaymentSplit> paidSplits = new ArrayList<>();
        double carriedForwardDebt = 0.0;

        for (PaymentSplit split : req.getPayments()) {
            if (split.getAmount() == null || split.getAmount() <= 0) continue;

            if ("pending".equalsIgnoreCase(split.getMode())) {
                carriedForwardDebt += split.getAmount();
            } else {
                paidSplits.add(split);
            }
        }

        if (paidSplits.isEmpty()) {
            logger.warn("Clear pending payment {} rejected: no funds collected (all {} carried forward)", id, carriedForwardDebt);
            return ResponseEntity.badRequest().body("You cannot settle an account without collecting at least some funds.");
        }

        LocalDateTime now = LocalDateTime.now();
        double originalDebt = existingDebt.getAmount();

        // 3. Process the 1st paid split by overwriting the EXISTING row
        // (This keeps the primary key alive for your historic audit logs)
        PaymentSplit firstSplit = paidSplits.get(0);
        existingDebt.setStatus("CLEARED");
        existingDebt.setSettlementMode(firstSplit.getMode().toUpperCase());
        existingDebt.setAmount(firstSplit.getAmount());
        existingDebt.setClearedAt(now);
        pendingPaymentRepository.save(existingDebt);
        logger.info("Pending payment {} ('{}', session {}) cleared: {} via {}",
                id, existingDebt.getCustomerName(), existingDebt.getSessionId(), firstSplit.getAmount(), firstSplit.getMode());

        // 4. If they used a 2nd payment mode today (e.g. Cash AND UPI), spawn Sibling Row A
        for (int i = 1; i < paidSplits.size(); i++) {
            PaymentSplit split = paidSplits.get(i);

            PendingPayment paidSibling = new PendingPayment();
            paidSibling.setSessionId(existingDebt.getSessionId());
            paidSibling.setCafeId(existingDebt.getCafeId());
            paidSibling.setCustomerName(existingDebt.getCustomerName());
            paidSibling.setCustomerPhone(existingDebt.getCustomerPhone());
            paidSibling.setCreatedAt(existingDebt.getCreatedAt()); // Match orig debt date

            paidSibling.setAmount(split.getAmount());
            paidSibling.setStatus("CLEARED");
            paidSibling.setSettlementMode(split.getMode().toUpperCase());
            paidSibling.setClearedAt(now);

            pendingPaymentRepository.save(paidSibling);
            logger.info("Additional settlement row {} for debt {}: {} via {}", paidSibling.getId(), id, split.getAmount(), split.getMode());
        }

        // 5. If they didn't pay it all off (₹700 pending), spawn Sibling Row B (The Carry Forward)
        if (carriedForwardDebt > 0) {
            PendingPayment carryForward = new PendingPayment();
            carryForward.setSessionId(existingDebt.getSessionId());
            carryForward.setCafeId(existingDebt.getCafeId());
            carryForward.setCustomerName(existingDebt.getCustomerName());
            carryForward.setCustomerPhone(existingDebt.getCustomerPhone());

            // CRITICAL: Stamp it with the OLD debt origination date so your aging reports don't reset!
            carryForward.setCreatedAt(existingDebt.getCreatedAt());

            carryForward.setAmount(carriedForwardDebt);
            carryForward.setStatus("PENDING");
            carryForward.setSettlementMode(null);
            carryForward.setClearedAt(null);
            carryForward.setNotes("Carried fwd from partial settlement. " + (req.getNotes() != null ? req.getNotes() : ""));

            pendingPaymentRepository.save(carryForward);
            logger.info("Carry-forward pending payment {} created from debt {}: amount={}", carryForward.getId(), id, carriedForwardDebt);
        }

        double collected = paidSplits.stream().mapToDouble(PaymentSplit::getAmount).sum();
        List<String> parts = paidSplits.stream()
                .map(sp -> sp.getMode().toUpperCase() + " " + AuditFormat.rupees(sp.getAmount())).toList();
        audit.recordInTransaction(AuditRecord.of(AuditActions.PENDING_PAYMENT_SETTLED)
                .entity(AuditActions.ENTITY_PENDING_PAYMENT, String.valueOf(id))
                .description("Collected " + AuditFormat.rupees(collected) + " of " + AuditFormat.rupees(originalDebt)
                        + " pending from " + existingDebt.getCustomerName() + " (Bill " + AuditFormat.billNo(existingDebt.getSessionId())
                        + "): " + String.join(" + ", parts)
                        + (carriedForwardDebt > 0 ? "; " + AuditFormat.rupees(carriedForwardDebt) + " still pending" : ""))
                .detail("session_id", existingDebt.getSessionId())
                .detail("payments", paidSplits.stream().map(sp -> Map.of("mode", sp.getMode(), "amount", sp.getAmount())).toList())
                .detail("original_amount", originalDebt)
                .detail("carried_forward", carriedForwardDebt)
                .detail("notes", req.getNotes()));
        return ResponseEntity.ok(Map.of("message", "Account successfully reconciled!"));
    }

    private String getCafeId(Authentication auth) {
        String cafeId = userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
        LogContext.putCafeId(cafeId);
        return cafeId;
    }
}