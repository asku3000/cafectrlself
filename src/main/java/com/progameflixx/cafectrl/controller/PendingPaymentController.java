package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.PendingPayment;
import com.progameflixx.cafectrl.repository.PendingPaymentRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/pending-payments")
@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true")
public class PendingPaymentController {

    @Autowired
    private PendingPaymentRepository pendingPaymentRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public ResponseEntity<?> getActivePending(Authentication auth) {
        String cafeId = getCafeId(auth); // Your context helper method
        return ResponseEntity.ok(pendingPaymentRepository.findByCafeIdAndStatus(cafeId, "PENDING"));
    }

    @PostMapping("/{id}/clear")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public ResponseEntity<?> clearPayment(@PathVariable Long id, @RequestBody Map<String, String> req) {
        PendingPayment debt = pendingPaymentRepository.findById(id).orElse(null);
        if (debt == null) return ResponseEntity.notFound().build();

        String mode = req.get("mode"); // "CASH", "UPI", "CARD"
        if (mode == null || mode.isBlank()) return ResponseEntity.badRequest().body("Payment mode is required");

        debt.setStatus("CLEARED");
        debt.setSettlementMode(mode.toUpperCase());
        debt.setClearedAt(LocalDateTime.now()); // Marks it strictly for today's collections logic

        pendingPaymentRepository.save(debt);
        return ResponseEntity.ok(Map.of("message", "Payment cleared successfully!"));
    }

    private String getCafeId(Authentication auth) {
        return userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
    }
}