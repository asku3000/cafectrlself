package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.repository.CafeRepository;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminController {

    @Autowired
    private CustomerSessionRepository sessionRepository;
    @Autowired
    private CafeRepository cafeRepository;

    @GetMapping("/stats")
    public Map<String, Object> getGlobalStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_cafes", cafeRepository.count());
        stats.put("total_sessions", sessionRepository.count());
        stats.put("total_revenue", sessionRepository.sumAllRevenue());
        return stats;
    }
}
