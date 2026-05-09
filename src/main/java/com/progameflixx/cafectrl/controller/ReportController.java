package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.dto.ReportResponse;
import com.progameflixx.cafectrl.entity.*;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasPermission('reports')") // Matches your require_perm("reports")
public class ReportController {

    @Autowired
    private CustomerSessionRepository sessionRepository;
    @Autowired
    private UserRepository userRepository;

    private String getCafeId(Authentication auth) {
        return userRepository.findById(auth.getName()).orElseThrow().getCafeId();
    }

    @GetMapping("/daily")
    public ReportResponse getDailyReport(
            @RequestParam(required = false) String date,
            Authentication auth) {

        String cafeId = getCafeId(auth);

        // Handle Date Logic
        LocalDate targetDate = (date != null) ? LocalDate.parse(date) : LocalDate.now();
        Instant start = targetDate.atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant end = start.plus(Duration.ofDays(1));

        List<CustomerSession> sessions = sessionRepository.findBilledSessionsInRange(cafeId, start, end);

        // Initialize report structure
        ReportResponse report = new ReportResponse();
        report.setDate(targetDate.toString());
        report.setTotal(0.0);
        report.setCount((long) sessions.size());

        Map<String, Double> byMode = new HashMap<>();
        Map<String, Double> byGameType = new HashMap<>();
        Map<String, ReportResponse.ItemStat> byItem = new HashMap<>();
        List<Map<String, Object>> timeline = new ArrayList<>();

        for (CustomerSession s : sessions) {
            report.setTotal(report.getTotal() + s.getBillTotal());

            // Aggregating Payment Modes
            for (CustomerSession.PaymentSplit p : s.getPayments()) {
                byMode.put(p.getMode(), byMode.getOrDefault(p.getMode(), 0.0) + p.getAmount());
            }

            // Aggregating Games and Items
            for (GameSession g : s.getGames()) {
                String gtName = g.getGameTypeName() != null ? g.getGameTypeName() : "Unknown";

                // Assuming GameSession now stores its calculated charge (as per compute_session_bill)
                // If using a simplified model, you'd call billingService here.
                // byGameType.put(gtName, byGameType.getOrDefault(gtName, 0.0) + gameCharge);

                for (GameSessionItem it : g.getItems()) {
                    ReportResponse.ItemStat stat = byItem.computeIfAbsent(it.getName(), k -> new ReportResponse.ItemStat());
                    stat.setRevenue(stat.getRevenue() != null ? stat.getRevenue() + it.getTotal() : it.getTotal());
                    stat.setQty(stat.getQty() != null ? stat.getQty() + it.getQty() : it.getQty());
                    stat.setType(it.getType());

                    // Build Timeline Entry
                    Map<String, Object> event = new HashMap<>();
                    event.put("name", it.getName());
                    event.put("qty", it.getQty());
                    event.put("customer", s.getCustomerName());
                    event.put("added_at", it.getAddedAt());
                    timeline.add(event);
                }
            }
        }

        report.setByMode(byMode);
        report.setByItem(byItem);
        report.setItemsTimeline(timeline);

        return report;
    }

    @GetMapping("/monthly")
    public Map<String, Object> getMonthlyReport(
            @RequestParam int year,
            @RequestParam int month,
            Authentication auth) {

        String cafeId = getCafeId(auth);

        LocalDate firstDay = LocalDate.of(year, month, 1);
        Instant start = firstDay.atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant end = firstDay.with(TemporalAdjusters.lastDayOfMonth())
                .atTime(23, 59, 59)
                .atZone(ZoneId.of("UTC")).toInstant();

        List<CustomerSession> sessions = sessionRepository.findBilledSessionsInRange(cafeId, start, end);

        Double totalRevenue = sessions.stream().mapToDouble(CustomerSession::getBillTotal).sum();

        Map<String, Object> response = new HashMap<>();
        response.put("year", year);
        response.put("month", month);
        response.put("total", Math.round(totalRevenue * 100.0) / 100.0);
        response.put("count", sessions.size());

        return response;
    }
}