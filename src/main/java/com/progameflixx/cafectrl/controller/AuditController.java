package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.AuditLog;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.AuditLogRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"}, allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'CAFE_ADMIN', 'OPERATOR')")
public class AuditController {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> getAuditLogs(@RequestParam(defaultValue = "200") int limit, Authentication auth) {
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        // Create a PageRequest to limit the results to the requested number (e.g., 200)
        PageRequest pageRequest = PageRequest.of(0, limit);
        List<AuditLog> logs;

        // Super Admin sees everything, Cafe Admin sees only their cafe
        if ("SUPER_ADMIN".equalsIgnoreCase(user.getRole())) {
            logs = auditLogRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        } else {
            logs = auditLogRepository.findByCafeIdOrderByCreatedAtDesc(user.getCafeId(), pageRequest);
        }

        return ResponseEntity.ok(logs);
    }
}