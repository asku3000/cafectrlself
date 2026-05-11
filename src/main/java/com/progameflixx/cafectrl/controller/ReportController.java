package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.GameSession;
import com.progameflixx.cafectrl.entity.GameSessionItem;
import com.progameflixx.cafectrl.entity.PaymentSplit;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
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

@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"}, allowCredentials = "true")
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasRole('CAFE_ADMIN')")
public class ReportController {

    @Autowired
    private CustomerSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BillingService billingService;

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

        // Fetch sessions with games and items joined
        List<CustomerSession> sessions = sessionRepository.findMonthlyReportData(
                cafeId, "billed", start, end);

        double totalRevenue = 0.0;
        Map<String, Double> byMode = new HashMap<>();
        Map<String, Map<String, Object>> byItem = new HashMap<>();

        // This is the specific list your UI expects
        List<Map<String, Object>> itemsTimeline = new ArrayList<>();
        // This is for the Games Chart (Revenue over time)
        Map<String, Double> hourlyGames = new HashMap<>();

        for (CustomerSession s : sessions) {
            totalRevenue += (s.getBillTotal() != null ? s.getBillTotal() : 0.0);
            String hourKey = s.getBilledAt() != null ? s.getBilledAt().getHour() + ":00" : "00:00";

            // 1. Payment Split
            if (s.getPayments() != null) {
                for (PaymentSplit p : s.getPayments()) {
                    String mode = (p.getMode() != null) ? p.getMode().toLowerCase() : "cash";
                    byMode.put(mode, byMode.getOrDefault(mode, 0.0) + (p.getAmount() != null ? p.getAmount() : 0.0));
                }
            }

            // 2. Process Games & Items
            double sessionGameRev = (s.getBillTotal() != null ? s.getBillTotal() : 0.0);

            for (GameSession g : s.getGames()) {
                for (GameSessionItem it : g.getItems()) {
                    sessionGameRev -= (it.getTotal() != null ? it.getTotal() : 0.0);

                    // --- UI FIX: Add individual item to the Timeline Log ---
                    Map<String, Object> logEntry = new HashMap<>();
                    logEntry.put("added_at", it.getAddedAt()); // LocalDateTime for fmtDateTime
                    logEntry.put("name", it.getName());
                    logEntry.put("qty", it.getQty());
                    logEntry.put("type", it.getType());
                    logEntry.put("customer_name", s.getCustomerName());
                    logEntry.put("resource_name", g.getResourceName());
                    logEntry.put("total", it.getTotal());
                    itemsTimeline.add(logEntry);

                    // Aggregate for the "By Item" summary table
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
            // Track hourly game revenue for the chart
            hourlyGames.put(hourKey, hourlyGames.getOrDefault(hourKey, 0.0) + sessionGameRev);
        }

        // 3. Sort the Items Timeline (Most recent at the top)
        itemsTimeline.sort((a, b) -> {
            LocalDateTime t1 = (LocalDateTime) a.get("added_at");
            LocalDateTime t2 = (LocalDateTime) b.get("added_at");
            return t2.compareTo(t1); // Descending order
        });

        // 4. Build Final Response
        Map<String, Object> resp = new HashMap<>();
        resp.put("date", date.toString());
        resp.put("total", totalRevenue);
        resp.put("by_mode", byMode);
        resp.put("by_item", byItem);
        resp.put("items_timeline", itemsTimeline); // Now matches your UI .map()
        resp.put("games_timeline", convertToTimeline(hourlyGames));
        resp.put("count", sessions.size());
        resp.put("sessions", sessions);

        return resp;
    }

    // Helper method to turn Map into Sorted List for the UI
    private List<Map<String, Object>> convertToTimeline(Map<String, Double> hourlyData) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Map.Entry<String, Double> entry : hourlyData.entrySet()) {
            Map<String, Object> point = new HashMap<>();
            point.put("time", entry.getKey());
            point.put("revenue", entry.getValue());
            timeline.add(point);
        }
        // Sort by time (09:00 before 10:00)
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

        // 1. Calculate the exact start and end of the requested month
        LocalDateTime start = LocalDateTime.of(year, month, 1, 0, 0);
        LocalDateTime end = start.plusMonths(1).minusNanos(1);

        // 2. Fetch data using the FETCH JOIN method to avoid empty item lists
        List<CustomerSession> sessions = sessionRepository.findMonthlyReportData(
                cafeId, "billed", start, end);

        double totalRevenue = 0.0;
        Map<String, Double> byMode = new HashMap<>();
        Map<String, Double> byDay = new HashMap<>();
        Map<String, Double> byGameType = new HashMap<>();
        Map<String, Map<String, Object>> byItem = new HashMap<>();
        Map<String, Double> byItemDay = new HashMap<>();
        Map<String, Double> byGameDay = new HashMap<>();

        java.time.format.DateTimeFormatter dayFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (CustomerSession s : sessions) {
            double sessionBill = s.getBillTotal() != null ? s.getBillTotal() : 0.0;
            totalRevenue += sessionBill;

            String dayKey = s.getBilledAt() != null ? s.getBilledAt().format(dayFormatter) : "Unknown Date";
            byDay.put(dayKey, byDay.getOrDefault(dayKey, 0.0) + sessionBill);

            // Track Payments Split
            if (s.getPayments() != null) {
                for (com.progameflixx.cafectrl.entity.PaymentSplit p : s.getPayments()) {
                    String mode = (p.getMode() != null) ? p.getMode().toLowerCase() : "cash";
                    double amt = (p.getAmount() != null) ? p.getAmount() : 0.0;
                    byMode.put(mode, byMode.getOrDefault(mode, 0.0) + amt);
                }
            }

            // Aggregate Game Charges using BillingService
            Map<String, Object> billData = billingService.computeSessionBill(s);
            List<Map<String, Object>> gamesBreakdown = (List<Map<String, Object>>) billData.get("games");

            if (gamesBreakdown != null) {
                for (Map<String, Object> gData : gamesBreakdown) {
                    String gtName = gData.get("game_type_name") != null ? (String) gData.get("game_type_name") : "General";
                    Map<String, Object> charge = (Map<String, Object>) gData.get("charge");
                    double gameAmt = (charge != null && charge.get("amount") != null) ? (Double) charge.get("amount") : 0.0;

                    byGameType.put(gtName, byGameType.getOrDefault(gtName, 0.0) + gameAmt);
                    byGameDay.put(dayKey, byGameDay.getOrDefault(dayKey, 0.0) + gameAmt);
                }
            }

            // Aggregate Items (Snacks/Drinks/Accessories)
            for (com.progameflixx.cafectrl.entity.GameSession g : s.getGames()) {
                for (com.progameflixx.cafectrl.entity.GameSessionItem it : g.getItems()) {
                    String itemName = it.getName() != null ? it.getName() : "Unknown Item";
                    double itemTotal = it.getTotal() != null ? it.getTotal() : 0.0;
                    int itemQty = it.getQty() != null ? it.getQty() : 0;

                    // Update 'byItem' for Top Items Table
                    Map<String, Object> stats = byItem.computeIfAbsent(itemName, k -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("revenue", 0.0);
                        m.put("qty", 0);
                        return m;
                    });
                    stats.put("revenue", (Double) stats.get("revenue") + itemTotal);
                    stats.put("qty", (Integer) stats.get("qty") + itemQty);

                    // Update 'byItemDay' for Timeline Chart
                    byItemDay.put(dayKey, byItemDay.getOrDefault(dayKey, 0.0) + itemTotal);
                }
            }
        }

        // 3. Format Top Items for React mapping
        List<Map<String, Object>> topItems = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : byItem.entrySet()) {
            Map<String, Object> itemObj = new HashMap<>(entry.getValue());
            itemObj.put("name", entry.getKey());
            topItems.add(itemObj);
        }
        topItems.sort((a, b) -> Double.compare((Double) b.get("revenue"), (Double) a.get("revenue")));

        // 4. Final Response Construction
        Map<String, Object> resp = new HashMap<>();
        resp.put("year", year);
        resp.put("month", month);
        resp.put("total", Math.round(totalRevenue * 100.0) / 100.0);
        resp.put("count", sessions.size());
        resp.put("by_mode", byMode);
        resp.put("by_day", byDay);
        resp.put("by_game_type", byGameType);
        resp.put("by_item", byItem);
        resp.put("top_items", topItems);
        resp.put("by_game_day", byGameDay);
        resp.put("by_item_day", byItemDay);

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