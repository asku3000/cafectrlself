package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.dto.MembershipSettingsRequest;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.MembershipSettings;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.service.AuditService;
import com.progameflixx.cafectrl.service.CurrentUserService;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import com.progameflixx.cafectrl.service.MembershipReportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Membership plans and settings. Reads are open to operators (they need plans to sell cards);
 * every write is CAFE_ADMIN only.
 */
@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/membership")
@PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
public class MembershipPlanController {

    private final MembershipPlanService planService;
    private final CurrentUserService currentUser;
    private final AuditService auditService;
    private final MembershipReportService reportService;

    public MembershipPlanController(MembershipPlanService planService, CurrentUserService currentUser, AuditService auditService,
                                    MembershipReportService reportService) {
        this.planService = planService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.reportService = reportService;
    }

    @GetMapping("/plans")
    public List<MembershipPlan> listPlans(Authentication auth) {
        return planService.list(currentUser.require(auth).getCafeId());
    }

    @PostMapping("/plans")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    public MembershipPlan createPlan(@RequestBody MembershipPlanRequest req, Authentication auth) {
        User actor = currentUser.require(auth);
        MembershipPlan plan = planService.create(actor, req);
        auditService.log(auth, "MEMBERSHIP_PLAN_CREATE", plan.getId(), planMeta(plan));
        return plan;
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    public MembershipPlan updatePlan(@PathVariable String id, @RequestBody MembershipPlanRequest req, Authentication auth) {
        User actor = currentUser.require(auth);
        MembershipPlan plan = planService.update(actor, id, req);
        auditService.log(auth, "MEMBERSHIP_PLAN_UPDATE", plan.getId(), planMeta(plan));
        return plan;
    }

    @PatchMapping("/plans/{id}/active")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    public MembershipPlan setPlanActive(@PathVariable String id, @RequestBody Map<String, Boolean> body, Authentication auth) {
        User actor = currentUser.require(auth);
        boolean active = Boolean.TRUE.equals(body.get("active"));
        MembershipPlan plan = planService.setActive(actor, id, active);
        auditService.log(auth, active ? "MEMBERSHIP_PLAN_ACTIVATE" : "MEMBERSHIP_PLAN_DEACTIVATE", plan.getId(),
                Map.of("name", plan.getName()));
        return plan;
    }

    @GetMapping("/settings")
    public MembershipSettings getSettings(Authentication auth) {
        return planService.getSettings(currentUser.require(auth).getCafeId());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('CAFE_ADMIN')")
    public MembershipSettings updateSettings(@RequestBody MembershipSettingsRequest req, Authentication auth) {
        User actor = currentUser.require(auth);
        MembershipSettings s = planService.updateSettings(actor, req);
        auditService.log(auth, "MEMBERSHIP_SETTINGS_UPDATE", s.getCafeId(), Map.of(
                "deduction_order", s.getDeductionOrder().name(),
                "low_balance_threshold_paise", s.getLowBalanceThresholdPaise(),
                "card_number_prefix", s.getCardNumberPrefix()));
        return s;
    }

    /** Membership sales / consumption / forfeits for a date range (inclusive). Admins, or operators with "reports". */
    @GetMapping("/reports")
    public MembershipReportService.Report report(
            @RequestParam("from") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam("to") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            Authentication auth) {
        User user = currentUser.require(auth);
        CurrentUserService.requirePermission(user, "reports");
        return reportService.report(user.getCafeId(), from, to);
    }

    private static Map<String, Object> planMeta(MembershipPlan p) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", p.getName());
        m.put("load_amount_paise", p.getLoadAmountPaise());
        m.put("bonus_type", p.getBonusType().name());
        m.put("bonus_value", p.getBonusValue());
        m.put("validity_days", p.getValidityDays());
        m.put("is_active", p.getIsActive());
        return m;
    }
}
