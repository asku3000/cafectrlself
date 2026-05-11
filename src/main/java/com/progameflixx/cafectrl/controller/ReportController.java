package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.GameSession;
import com.progameflixx.cafectrl.entity.GameSessionItem;
import com.progameflixx.cafectrl.entity.PaymentSplit;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"}, allowCredentials = "true")
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasRole('CAFE_ADMIN')")
public class ReportController {

    @Autowired
    private CustomerSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    private String getCafeId(Authentication auth) {
        return userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
    }

    @GetMapping("/daily")
    public Map<String, Object> getDailyReport(@RequestParam(required = false) String date, Authentication auth) {
        String cafeId = getCafeId(auth);
        LocalDate targetDate = (date != null) ? LocalDate.parse(date) : LocalDate.now();

        LocalDateTime start = targetDate.atStartOfDay();
        LocalDateTime end = targetDate.atTime(LocalTime.MAX);

        List<CustomerSession> sessions = sessionRepository.findByCafeIdAndStatusAndBilledAtBetween(
                cafeId, "billed", start, end);

        double totalRevenue = 0.0;
        Map<String, Double> byMode = new HashMap<>();
        Map<String, Double> byGameType = new HashMap<>();
        Map<String, Map<String, Object>> byItem = new HashMap<>();
        List<Map<String, Object>> itemsTimeline = new ArrayList<>();

        for (CustomerSession s : sessions) {
            totalRevenue += s.getBillTotal();

            // Track Payment Modes
            for (PaymentSplit p : s.getPayments()) {
                byMode.put(p.getMode(), byMode.getOrDefault(p.getMode(), 0.0) + p.getAmount());
            }

            // In Option A, we iterate through the actual entities
            for (GameSession g : s.getGames()) {
                String gtName = g.getGameTypeName() != null ? g.getGameTypeName() : "Unknown";

                // Note: In a real app, you'd call billingService.computeGameCharge here
                // to get the exact split. For now, we'll aggregate items.
                for (GameSessionItem it : g.getItems()) {
                    String itemName = it.getName();
                    Map<String, Object> itemStats = byItem.computeIfAbsent(itemName, k -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("revenue", 0.0);
                        m.put("qty", 0);
                        m.put("type", it.getType());
                        return m;
                    });

                    itemStats.put("revenue", (Double)itemStats.get("revenue") + it.getTotal());
                    itemStats.put("qty", (Integer)itemStats.get("qty") + it.getQty());

                    // Create Timeline Entry
                    Map<String, Object> timelineEntry = new HashMap<>();
                    timelineEntry.put("name", itemName);
                    timelineEntry.put("qty", it.getQty());
                    timelineEntry.put("total", it.getTotal());
                    timelineEntry.put("customer_name", s.getCustomerName());
                    timelineEntry.put("added_at", it.getAddedAt());
                    itemsTimeline.add(timelineEntry);
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("total", totalRevenue);
        response.put("count", sessions.size());
        response.put("by_mode", byMode);
        response.put("by_item", byItem);
        response.put("items_timeline", itemsTimeline);
        response.put("sessions", sessions);
        return response;
    }
}