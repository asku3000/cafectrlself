package com.progameflixx.cafectrl.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/super-admin")
public class SuperAdminController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Translates: @api.get("/super-admin/cafes")
     */
    @GetMapping("/cafes")
    public List<Map<String, Object>> listAllCafes() {
        // 1. Fetch all cafes as a list of key-value maps (mimics Python dictionaries)
        String cafeSql = "SELECT * FROM cafes";
        List<Map<String, Object>> cafes = jdbcTemplate.queryForList(cafeSql);

        // 2. Loop through and attach the user and session counts
        for (Map<String, Object> cafe : cafes) {
            // Note: Make sure your MySQL column is actually named 'id'.
            // If your PK is 'cafe_id', change the string below.
            String cafeId = (String) cafe.get("id");

            String usersSql = "SELECT COUNT(*) FROM users WHERE cafe_id = ?";
            Integer usersCount = jdbcTemplate.queryForObject(usersSql, Integer.class, cafeId);

            String sessionsSql = "SELECT COUNT(*) FROM customer_sessions WHERE cafe_id = ?";
            Integer sessionsCount = jdbcTemplate.queryForObject(sessionsSql, Integer.class, cafeId);

            // Append counts exactly how React expects them
            cafe.put("users_count", usersCount != null ? usersCount : 0);
            cafe.put("sessions_count", sessionsCount != null ? sessionsCount : 0);
        }

        return cafes;
    }

    /**
     * Translates: @api.get("/super-admin/stats")
     */
    @GetMapping("/stats")
    public Map<String, Object> superStats() {
        // Count totals across tables
        Integer totalCafes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cafes", Integer.class);
        Integer totalUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users where role!='SUPER_ADMIN'", Integer.class);
        Integer totalSessions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customer_sessions", Integer.class);

        // Calculate Revenue using standard SQL SUM()
        String revenueSql = "SELECT SUM(bill_total) FROM customer_sessions WHERE status = 'billed'";
        Double totalRevenue = jdbcTemplate.queryForObject(revenueSql, Double.class);

        // Build exact JSON response expected by React
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_cafes", totalCafes != null ? totalCafes : 0);
        stats.put("total_users", totalUsers != null ? totalUsers : 0);
        stats.put("total_sessions", totalSessions != null ? totalSessions : 0);

        // Handle null in case there are 0 billed sessions yet
        stats.put("total_revenue", totalRevenue != null ? totalRevenue : 0.0);

        return stats;
    }
}
