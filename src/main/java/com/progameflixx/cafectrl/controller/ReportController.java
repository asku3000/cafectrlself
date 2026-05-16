package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.GameTypeRepository;
import com.progameflixx.cafectrl.repository.PendingPaymentRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import com.progameflixx.cafectrl.service.BillingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"}, allowCredentials = "true")
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
public class ReportController {

    @Autowired
    private CustomerSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BillingService billingService;

    @Autowired
    private GameTypeRepository gameTypeRepository;

    @Autowired
    private PendingPaymentRepository pendingPaymentRepository;

    private String getCafeId(Authentication auth) {
        return userRepository.findByEmail(auth.getName()).orElseThrow().getCafeId();
    }

    @GetMapping("/daily")
    @Transactional(readOnly = true)
    public Map<String, Object> getDailyReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {

        String cafeId = getCafeId(auth);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);

        List<com.progameflixx.cafectrl.entity.CustomerSession> sessions =
                sessionRepository.findMonthlyReportData(cafeId, "billed", start, end);

        // NEW: Fetch debts that were cleared today
        List<com.progameflixx.cafectrl.entity.PendingPayment> clearedToday =
                pendingPaymentRepository.findByCafeIdAndStatusAndClearedAtBetween(cafeId, "CLEARED", start, end);

        Map<String, String> gameTypeNames = gameTypeRepository.findAll().stream()
                .collect(Collectors.toMap(gt -> gt.getId(), gt -> gt.getName(), (a, b) -> a));

        // REPLACED: totalRevenue with totalCollected to track true cash-in-hand
        double totalCollected = 0.0;
        Map<String, Double> byMode = new HashMap<>();
        Map<String, Double> byGameType = new HashMap<>();
        Map<String, Map<String, Object>> byItem = new HashMap<>();
        List<Map<String, Object>> itemsTimeline = new ArrayList<>();
        Map<String, Double> hourlyGames = new HashMap<>();

        for (com.progameflixx.cafectrl.entity.CustomerSession s : sessions) {
            String hourKey = s.getBilledAt() != null ? s.getBilledAt().getHour() + ":00" : "00:00";

            // 1. Process Payments (Cash Accounting Logic)
            if (s.getPayments() != null) {
                for (com.progameflixx.cafectrl.entity.PaymentSplit p : s.getPayments()) {
                    String mode = (p.getMode() != null) ? p.getMode().toLowerCase() : "cash";
                    double amt = (p.getAmount() != null) ? p.getAmount() : 0.0;

                    // Track all modes (including pending so it shows in the UI grid)
                    byMode.put(mode, byMode.getOrDefault(mode, 0.0) + amt);

                    // Add to true collected revenue ONLY if it isn't "pending"
                    if (!mode.contains("pending")) {
                        totalCollected += amt;
                    }
                }
            }

            // ---> THE UI FIX: Generate and attach the full Receipt Breakdown <---
            Map<String, Object> breakdown = billingService.computeSessionBill(s);
            s.setBillBreakdown(breakdown);

            // 2. Process Games for the Charts & Timeline
            double sessionGameTotal = 0.0;
            for (com.progameflixx.cafectrl.entity.GameSession g : s.getGames()) {

                // Get the game charge to feed the Pie Chart
                Map<String, Object> chargeResult = billingService.computeGameCharge(g);
                double gameCharge = 0.0;
                if (chargeResult != null && chargeResult.get("amount") != null) {
                    gameCharge = ((Number) chargeResult.get("amount")).doubleValue();
                }

                String typeName = gameTypeNames.getOrDefault(g.getGameTypeId(), "General Gaming");
                byGameType.put(typeName, byGameType.getOrDefault(typeName, 0.0) + gameCharge);
                sessionGameTotal += gameCharge;

                // Process Items for the Timeline List
                for (com.progameflixx.cafectrl.entity.GameSessionItem it : g.getItems()) {
                    Map<String, Object> logEntry = new HashMap<>();
                    logEntry.put("added_at", it.getAddedAt());
                    logEntry.put("name", it.getName());
                    logEntry.put("qty", it.getQty());
                    logEntry.put("type", it.getType());
                    logEntry.put("customer_name", s.getCustomerName());
                    logEntry.put("resource_name", g.getResourceName());
                    logEntry.put("total", it.getTotal());
                    itemsTimeline.add(logEntry);

                    Map<String, Object> stats = byItem.computeIfAbsent(it.getName(), k -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("revenue", 0.0);
                        m.put("qty", 0);
                        m.put("type", it.getType());
                        return m;
                    });
                    stats.put("revenue", (Double) stats.get("revenue") + (it.getTotal() != null ? it.getTotal() : 0.0));
                    stats.put("qty", (Integer) stats.get("qty") + (it.getQty() != null ? it.getQty() : 0));
                }
            }
            hourlyGames.put(hourKey, hourlyGames.getOrDefault(hourKey, 0.0) + sessionGameTotal);
        }

        // 3. Sort Timeline
        itemsTimeline.sort((a, b) -> {
            LocalDateTime t1 = (LocalDateTime) a.get("added_at");
            LocalDateTime t2 = (LocalDateTime) b.get("added_at");
            return (t1 != null && t2 != null) ? t2.compareTo(t1) : 0;
        });

        // NEW: 2.5 Inject Settled Debts into Today's Revenue & Create UI Trail
        List<Map<String, Object>> recoveredDebts = new ArrayList<>(); // <-- Create the list for the UI

        for (com.progameflixx.cafectrl.entity.PendingPayment p : clearedToday) {
            double amt = p.getAmount() != null ? p.getAmount() : 0.0;
            String mode = p.getSettlementMode() != null ? p.getSettlementMode().toLowerCase() : "cash";

            totalCollected += amt;
            byMode.put(mode, byMode.getOrDefault(mode, 0.0) + amt);

            // Map the details for the React frontend
            Map<String, Object> debtLog = new HashMap<>();
            debtLog.put("id", p.getId());
            debtLog.put("customer_name", p.getCustomerName());
            debtLog.put("customer_phone", p.getCustomerPhone());
            debtLog.put("amount", amt);
            debtLog.put("mode", mode);
            debtLog.put("cleared_at", p.getClearedAt());
            debtLog.put("created_at", p.getCreatedAt()); // When the debt originally happened

            recoveredDebts.add(debtLog);
        }
        recoveredDebts.sort((a, b) -> {
            LocalDateTime t1 = (LocalDateTime) a.get("cleared_at");
            LocalDateTime t2 = (LocalDateTime) b.get("cleared_at");
            return (t1 != null && t2 != null) ? t2.compareTo(t1) : 0;
        });

        // 4. Return Final JSON
        Map<String, Object> resp = new HashMap<>();
        resp.put("date", date.toString());
        resp.put("total", Math.round(totalCollected * 100.0) / 100.0); // Now perfectly reflects true cash in drawer
        resp.put("by_mode", byMode);
        resp.put("by_item", byItem);
        resp.put("by_game_type", byGameType);
        resp.put("items_timeline", itemsTimeline);
        resp.put("games_timeline", convertToTimeline(hourlyGames)); // (Assuming you have this helper method below)
        resp.put("count", sessions.size());
        resp.put("sessions", sessions);
        resp.put("recovered_debts", recoveredDebts);
        return resp;
    }

    private List<Map<String, Object>> convertToTimeline(Map<String, Double> hourlyData) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Map.Entry<String, Double> entry : hourlyData.entrySet()) {
            Map<String, Object> point = new HashMap<>();
            point.put("time", entry.getKey());
            point.put("revenue", Math.round(entry.getValue() * 100.0) / 100.0);
            timeline.add(point);
        }
        timeline.sort((a, b) -> ((String) a.get("time")).compareTo((String) b.get("time")));
        return timeline;
    }

    @GetMapping("/monthly")
    @Transactional(readOnly = true)
    public Map<String, Object> getMonthlyReport(
            @RequestParam int year,
            @RequestParam int month,
            Authentication auth) {

        String cafeId = getCafeId(auth);

        // 1. Calculate Start and End of the Month
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.withDayOfMonth(startOfMonth.lengthOfMonth());
        LocalDateTime start = startOfMonth.atStartOfDay();
        LocalDateTime end = endOfMonth.atTime(LocalTime.MAX);

        // 2. Fetch Sessions
        List<com.progameflixx.cafectrl.entity.CustomerSession> sessions =
                sessionRepository.findMonthlyReportData(cafeId, "billed", start, end);

        // 3. Pre-fetch Game Type Names (THE FIX FOR THE PIE CHART)
        Map<String, String> gameTypeNames = gameTypeRepository.findAll().stream()
                .collect(Collectors.toMap(gt -> gt.getId(), gt -> gt.getName(), (a, b) -> a));

        double totalRevenue = 0.0;
        Map<String, Double> byMode = new HashMap<>();
        Map<String, Double> byDay = new HashMap<>();

        // Detailed Aggregations for Monthly UI Charts
        Map<String, Double> byGameType = new HashMap<>(); // For Pie Chart
        Map<String, Map<String, Object>> byItem = new HashMap<>(); // For Top Items Table/Pie
        Map<String, Double> byGameDay = new HashMap<>(); // For Stacked Bar Chart
        Map<String, Double> byItemDay = new HashMap<>(); // For Stacked Bar Chart

        for (com.progameflixx.cafectrl.entity.CustomerSession s : sessions) {

            double billTotal = s.getBillTotal() != null ? s.getBillTotal() : 0.0;
            totalRevenue += billTotal;

            // "YYYY-MM-DD" grouping key for the daily bar charts
            String dayKey = s.getBilledAt() != null ? s.getBilledAt().toLocalDate().toString() : startOfMonth.toString();
            byDay.put(dayKey, byDay.getOrDefault(dayKey, 0.0) + billTotal);

            // Payments Split
            if (s.getPayments() != null) {
                for (com.progameflixx.cafectrl.entity.PaymentSplit p : s.getPayments()) {
                    String mode = (p.getMode() != null) ? p.getMode().toLowerCase() : "cash";
                    byMode.put(mode, byMode.getOrDefault(mode, 0.0) + (p.getAmount() != null ? p.getAmount() : 0.0));
                }
            }

            double sessionGameTotal = 0.0;
            double sessionItemTotal = 0.0;

            // Games & Items Processing
            for (com.progameflixx.cafectrl.entity.GameSession g : s.getGames()) {

                // Get exact game charge via BillingService
                Map<String, Object> chargeResult = billingService.computeGameCharge(g);
                double gameCharge = 0.0;
                if (chargeResult != null && chargeResult.get("amount") != null) {
                    gameCharge = ((Number) chargeResult.get("amount")).doubleValue();
                }

                // Apply Name Lookup for Pie Chart slices
                String typeName = gameTypeNames.getOrDefault(g.getGameTypeId(), "General Gaming");
                byGameType.put(typeName, byGameType.getOrDefault(typeName, 0.0) + gameCharge);
                sessionGameTotal += gameCharge;

                for (com.progameflixx.cafectrl.entity.GameSessionItem it : g.getItems()) {
                    double itemTotal = it.getTotal() != null ? it.getTotal() : 0.0;
                    sessionItemTotal += itemTotal;

                    // Summary for Top Items table
                    Map<String, Object> stats = byItem.computeIfAbsent(it.getName(), k -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("revenue", 0.0);
                        m.put("qty", 0);
                        m.put("type", it.getType());
                        return m;
                    });
                    stats.put("revenue", (Double) stats.get("revenue") + itemTotal);
                    stats.put("qty", (Integer) stats.get("qty") + (it.getQty() != null ? it.getQty() : 0));
                }
            }

            // Populate Stacked Bar Chart data
            byGameDay.put(dayKey, byGameDay.getOrDefault(dayKey, 0.0) + sessionGameTotal);
            byItemDay.put(dayKey, byItemDay.getOrDefault(dayKey, 0.0) + sessionItemTotal);
        }
        List<com.progameflixx.cafectrl.entity.PendingPayment> clearedMonthly =
                pendingPaymentRepository.findByCafeIdAndStatusAndClearedAtBetween(cafeId, "CLEARED", start, end);
        double monthlyPendingTotalCleared = clearedMonthly.stream().mapToDouble(PendingPayment::getAmount).sum();
        // Return the exact JSON structure your Monthly React Tab expects
        Map<String, Object> resp = new HashMap<>();
        resp.put("year", year);
        resp.put("month", month);
        resp.put("total", Math.round(totalRevenue * 100.0) / 100.0);
        resp.put("count", sessions.size());
        resp.put("by_mode", byMode);
        resp.put("by_day", byDay);
        resp.put("by_game_type", byGameType); // Fixes the Pie Chart
        resp.put("by_item", byItem);
        resp.put("by_game_day", byGameDay);   // Fixes Stacked Bar (Games)
        resp.put("by_item_day", byItemDay);   // Fixes Stacked Bar (Items)
        resp.put("pending_total_cleared", monthlyPendingTotalCleared);
        // Note: We deliberately do NOT return the "sessions" list here because a
        // whole month of raw session data would make the JSON payload huge and
        // slow down the browser, and the Monthly UI tab doesn't have a "Bills" table anyway.

        return resp;
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
    public Map<String, Object> getDashboardStats(Authentication auth) {
        String cafeId = getCafeId(auth);

        // 1. Define "Today" in IST (Indian Standard Time)
        java.time.ZonedDateTime nowIST = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.LocalDateTime startOfDay = nowIST.toLocalDate().atStartOfDay();
        java.time.LocalDateTime endOfDay = nowIST.toLocalDate().atTime(java.time.LocalTime.MAX);

        // 2. Fetch all billed sessions for today
        List<CustomerSession> billedToday = sessionRepository.findByCafeIdAndStatusAndBilledAtBetween(
                cafeId, "billed", startOfDay, endOfDay);

        double totalRevenue = 0.0;
        double cashTotal = 0.0;
        double upiTotal = 0.0;
        double cardTotal = 0.0;

        for (CustomerSession s : billedToday) {
            totalRevenue += (s.getBillTotal() != null ? s.getBillTotal() : 0.0);

            if (s.getPayments() != null) {
                for (PaymentSplit p : s.getPayments()) {
                    String mode = p.getMode().toLowerCase();
                    double amt = p.getAmount() != null ? p.getAmount() : 0.0;
                    if (mode.contains("cash")) cashTotal += amt;
                    else if (mode.contains("upi")) upiTotal += amt;
                    else if (mode.contains("card")) cardTotal += amt;
                }
            }
        }

        // 3. Get Active Session Count (Occupancy)
        long activeSessions = sessionRepository.countByCafeIdAndStatus(cafeId, "active");

        // 4. Build the JSON structure React expects
        Map<String, Object> response = new HashMap<>();
        response.put("total_revenue", Math.round(totalRevenue * 100.0) / 100.0);
        response.put("active_sessions", activeSessions);
        response.put("today_count", billedToday.size());

        // This 'payment_split' object powers your Pie Chart
        Map<String, Double> paymentSplit = new HashMap<>();
        paymentSplit.put("cash", Math.round(cashTotal * 100.0) / 100.0);
        paymentSplit.put("upi", Math.round(upiTotal * 100.0) / 100.0);
        paymentSplit.put("card", Math.round(cardTotal * 100.0) / 100.0);
        response.put("payment_split", paymentSplit);

        return response;
    }
}