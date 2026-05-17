package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.AuditLog;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.AuditLogRepository;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'CAFE_ADMIN', 'OPERATOR')")
public class AuditController {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerSessionRepository sessionRepository;

    @GetMapping()
    public List<Map<String, Object>> getAuditLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "200") int limit,
            Authentication auth) {

        String cafeId = getCafeId(auth);
        if (date == null) date = LocalDate.now();

        ZoneId zoneId = ZoneId.of("Asia/Kolkata");
        LocalDateTime start = date.atStartOfDay(zoneId).toLocalDateTime();
        LocalDateTime end = date.atTime(LocalTime.MAX).atZone(zoneId).toLocalDateTime();

        Pageable pageable = PageRequest.of(0, limit);
        List<AuditLog> rawLogs = auditLogRepository.findAuditsByCafeAndDate(cafeId, start, end, pageable);

        // Convert the raw logs into a Map to GUARANTEE the JSON keys perfectly match React
        List<Map<String, Object>> formattedLogs = new ArrayList<>();

        for (AuditLog log : rawLogs) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", log.getId());
            dto.put("created_at", log.getCreatedAt());

            // FIX 1: Force exact snake_case keys for React
            dto.put("user_name", log.getUserName() != null ? log.getUserName() : "System");
            dto.put("user_role", log.getUserRole() != null ? log.getUserRole() : "Admin");
            dto.put("action", log.getAction());

            // FIX 2: Translate the Session ID into the actual Customer Name
            String targetDisplay = log.getTarget();
            if (targetDisplay != null && log.getAction() != null && log.getAction().contains("SESSION")) {
                // Look up the session in the database
                com.progameflixx.cafectrl.entity.CustomerSession session =
                        sessionRepository.findById(targetDisplay).orElse(null);

                if (session != null) {
                    targetDisplay = session.getCustomerName();
                }
            }

            // Send the readable name instead of the UUID
            dto.put("target", targetDisplay);

            formattedLogs.add(dto);
        }

        return formattedLogs;
    }

    // Add this helper method at the bottom of your Controller
    private String getCafeId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User is not authenticated");
        }

        String username = auth.getName(); // Usually gets the email or username

        // Fetch the user from your database to get their assigned Cafe ID
        // Replace 'userRepository' and 'User' with your actual class names
        User currentUser = userRepository.findByEmail(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return currentUser.getCafeId();
    }
}