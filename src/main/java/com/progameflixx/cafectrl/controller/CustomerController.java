package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.dto.CustomerProfileView;
import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.PendingPaymentRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/customers")
@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true")
public class CustomerController {

    @Autowired
    private CustomerSessionRepository sessionRepository;

    @Autowired
    private PendingPaymentRepository pendingPaymentRepository;

    @Autowired
    private UserRepository userRepository;

    // --- 1. DIRECTORY: GET ALL UNIQUE CUSTOMER PROFILES ---
//    @GetMapping
//    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
//    public ResponseEntity<?> getCustomerDirectory(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size, Authentication auth) {
//        String cafeId = getCafeId(auth);
//
//        List<CustomerSession> allSessions = sessionRepository.findByCafeId(cafeId);
//
//        // Group by Name + Phone unique combination pairs
//        Map<String, List<CustomerSession>> grouped = allSessions.stream()
//                .collect(Collectors.groupingBy(s ->
//                        (s.getCustomerName().trim().toLowerCase() + "|||" +
//                                (s.getCustomerPhone() != null ? s.getCustomerPhone().trim() : ""))
//                ));
//
//        List<Map<String, Object>> directory = new ArrayList<>();
//
//        for (Map.Entry<String, List<CustomerSession>> entry : grouped.entrySet()) {
//            List<CustomerSession> userSessions = entry.getValue();
//            CustomerSession latest = userSessions.get(userSessions.size() - 1);
//
//            Map<String, Object> profile = new HashMap<>();
//            profile.put("name", latest.getCustomerName());
//            profile.put("phone", latest.getCustomerPhone());
//            profile.put("totalVisits", userSessions.size());
//            profile.put("lastVisited", latest.getCreatedAt());
//            directory.add(profile);
//        }
//
//        return ResponseEntity.ok(directory);
//    }

    @GetMapping()
    public ResponseEntity<Map<String, Object>> getCustomers(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        String cafeId = getCafeId(auth);

        // Sort by last visited descending
        Pageable paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastVisited"));

        Page<CustomerProfileView> pageResult = sessionRepository.getGroupedCustomerProfiles(cafeId, paging);

        Map<String, Object> response = new HashMap<>();
        response.put("data", pageResult.getContent());
        response.put("currentPage", pageResult.getNumber());
        response.put("totalItems", pageResult.getTotalElements());
        response.put("totalPages", pageResult.getTotalPages());

        return ResponseEntity.ok(response);
    }
    // --- 2. HISTORY: GET TIMELINE FOR SPECIFIC PROFILE ---
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public ResponseEntity<?> getCustomerHistory(
            @RequestParam String name,
            @RequestParam(required = false) String phone,
            Authentication auth) {

        String cafeId = getCafeId(auth);
        List<CustomerSession> history = sessionRepository.findByCafeIdAndCustomerNameIgnoreCase(cafeId, name.trim());

        if (phone != null && !phone.isBlank()) {
            history = history.stream()
                    .filter(s -> phone.equals(s.getCustomerPhone()))
                    .collect(Collectors.toList());
        }

        // Sort timeline by newest session entries first
        history.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return ResponseEntity.ok(history);
    }

    // --- 3. LEDGER: GET UNSETTLED ITEMS ---
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public ResponseEntity<?> getPendingBalances(Authentication auth) {
        String cafeId = getCafeId(auth);
        return ResponseEntity.ok(pendingPaymentRepository.findByCafeIdAndStatus(cafeId, "PENDING"));
    }

    private String getCafeId(Authentication auth) {
        return userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
    }
}