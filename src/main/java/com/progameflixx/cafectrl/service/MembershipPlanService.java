package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditDiff;
import com.progameflixx.cafectrl.audit.AuditFormat;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.dto.MembershipSettingsRequest;
import com.progameflixx.cafectrl.entity.MembershipMath;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.MembershipSettings;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.MembershipPlanRepository;
import com.progameflixx.cafectrl.repository.MembershipSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/** Membership plans (admin-configurable) and per-cafe membership settings. */
@Service
public class MembershipPlanService {

    private static final Logger logger = LoggerFactory.getLogger(MembershipPlanService.class);

    static final int MAX_VALIDITY_DAYS = 3650;
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern CARD_PREFIX = Pattern.compile("^[A-Z0-9]{1,10}$");

    private final MembershipPlanRepository planRepository;
    private final MembershipSettingsRepository settingsRepository;
    private final AuditService audit;

    public MembershipPlanService(MembershipPlanRepository planRepository, MembershipSettingsRepository settingsRepository,
                                 AuditService audit) {
        this.planRepository = planRepository;
        this.settingsRepository = settingsRepository;
        this.audit = audit;
    }

    // ---------------- Plans ----------------

    @Transactional(readOnly = true)
    public List<MembershipPlan> list(String cafeId) {
        return planRepository.findByCafeIdOrderBySortOrderAscNameAsc(cafeId);
    }

    @Transactional(readOnly = true)
    public MembershipPlan get(String cafeId, String planId) {
        return planRepository.findByIdAndCafeId(planId, cafeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
    }

    @Transactional
    public MembershipPlan create(User actor, MembershipPlanRequest req) {
        MembershipPlan plan = new MembershipPlan();
        plan.setCafeId(actor.getCafeId());
        apply(plan, req, true);
        plan.setUpdatedByName(actor.getName());
        MembershipPlan saved = planRepository.save(plan);
        logger.info("Membership plan {} created: '{}' load={} bonus={} {} validity={}d",
                saved.getId(), saved.getName(), saved.getLoadAmountPaise(), saved.getBonusType(),
                saved.getBonusValue(), saved.getValidityDays());
        audit.record(AuditRecord.of(AuditActions.MEMBERSHIP_PLAN_CREATED).actor(actor)
                .entity(AuditActions.ENTITY_MEMBERSHIP_PLAN, saved.getId())
                .description("Created membership plan '" + saved.getName() + "': pay " + AuditFormat.paise(saved.getLoadAmountPaise())
                        + ", gets " + AuditFormat.paise(saved.getTotalValuePaise()) + ", " + saved.getValidityDays() + " days")
                .diff(null, planSnapshot(saved)));
        return saved;
    }

    /** Editing a plan never touches issued cards: cards carry their own snapshot of plan values. */
    @Transactional
    public MembershipPlan update(User actor, String planId, MembershipPlanRequest req) {
        MembershipPlan plan = get(actor.getCafeId(), planId);
        Map<String, Object> before = planSnapshot(plan);
        apply(plan, req, false);
        plan.setUpdatedByName(actor.getName());
        MembershipPlan saved = planRepository.save(plan);
        logger.info("Membership plan {} updated: '{}' load={} bonus={} {} validity={}d active={}",
                saved.getId(), saved.getName(), saved.getLoadAmountPaise(), saved.getBonusType(),
                saved.getBonusValue(), saved.getValidityDays(), saved.getIsActive());
        audit.record(AuditRecord.of(AuditActions.MEMBERSHIP_PLAN_UPDATED).actor(actor)
                .entity(AuditActions.ENTITY_MEMBERSHIP_PLAN, saved.getId())
                .description("Edited membership plan '" + saved.getName() + "' (cards already sold keep their terms)")
                .diff(before, planSnapshot(saved)));
        return saved;
    }

    @Transactional
    public MembershipPlan setActive(User actor, String planId, boolean active) {
        MembershipPlan plan = get(actor.getCafeId(), planId);
        boolean was = Boolean.TRUE.equals(plan.getIsActive());
        plan.setIsActive(active);
        plan.setUpdatedByName(actor.getName());
        logger.info("Membership plan {} ('{}') {}", planId, plan.getName(), active ? "activated" : "deactivated");
        MembershipPlan saved = planRepository.save(plan);
        audit.record(AuditRecord.of(active ? AuditActions.MEMBERSHIP_PLAN_ACTIVATED : AuditActions.MEMBERSHIP_PLAN_DEACTIVATED)
                .actor(actor).entity(AuditActions.ENTITY_MEMBERSHIP_PLAN, saved.getId())
                .description((active ? "Put membership plan '" : "Took membership plan '") + saved.getName()
                        + (active ? "' on sale" : "' off sale (existing cards keep working)"))
                .diff(AuditDiff.snapshot("is_active", was), AuditDiff.snapshot("is_active", active)));
        return saved;
    }

    /**
     * Validates and copies request fields onto the plan. On create every required field must be present;
     * on update, null fields keep their current value.
     */
    private void apply(MembershipPlan plan, MembershipPlanRequest req, boolean creating) {
        if (req == null) throw bad("Request body is required");

        if (req.getName() != null || creating) {
            String name = req.getName() == null ? "" : req.getName().trim();
            if (name.isEmpty()) throw bad("Plan name is required");
            if (name.length() > 60) throw bad("Plan name must be at most 60 characters");
            if (planRepository.existsByCafeIdAndNameIgnoreCaseAndIdNot(plan.getCafeId(), name, plan.getId())) {
                throw bad("A plan named '" + name + "' already exists");
            }
            plan.setName(name);
        }

        if (req.getLoadAmountPaise() != null || creating) {
            Long load = req.getLoadAmountPaise();
            if (load == null || load <= 0) throw bad("Load amount must be greater than zero");
            plan.setLoadAmountPaise(load);
        }

        if (req.getBonusType() != null || creating) {
            plan.setBonusType(parseBonusType(req.getBonusType()));
        }

        if (req.getBonusValue() != null || creating) {
            long value = req.getBonusValue() == null ? 0L : req.getBonusValue();
            if (value < 0) throw bad("Bonus cannot be negative");
            plan.setBonusValue(value);
        }
        if (plan.getBonusType() == MembershipPlan.BonusType.PERCENT
                && plan.getBonusValue() > MembershipMath.FULL_PERCENT_BP) {
            throw bad("Percentage bonus cannot exceed 100%");
        }

        if (req.getValidityDays() != null || creating) {
            Integer days = req.getValidityDays();
            if (days == null || days < 1 || days > MAX_VALIDITY_DAYS) {
                throw bad("Validity must be between 1 and " + MAX_VALIDITY_DAYS + " days");
            }
            plan.setValidityDays(days);
        }

        if (req.getIsActive() != null) plan.setIsActive(req.getIsActive());

        if (req.getColor() != null) {
            String color = req.getColor().trim();
            if (!color.isEmpty() && !HEX_COLOR.matcher(color).matches()) throw bad("Colour must be a hex value like #3B82F6");
            plan.setColor(color.isEmpty() ? null : color);
        }

        if (req.getBadge() != null) {
            String badge = req.getBadge().trim();
            if (badge.length() > 40) throw bad("Badge must be at most 40 characters");
            plan.setBadge(badge.isEmpty() ? null : badge);
        }

        if (req.getSortOrder() != null) plan.setSortOrder(req.getSortOrder());

        if (req.getDescription() != null) {
            String desc = req.getDescription().trim();
            if (desc.length() > 1000) throw bad("Description must be at most 1000 characters");
            plan.setDescription(desc.isEmpty() ? null : desc);
        }
    }

    private static MembershipPlan.BonusType parseBonusType(String raw) {
        if (raw == null || raw.isBlank()) return MembershipPlan.BonusType.FIXED;
        try {
            return MembershipPlan.BonusType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("Bonus type must be FIXED or PERCENT");
        }
    }

    // ---------------- Settings ----------------

    /** Returns the cafe's settings, or unsaved defaults if none were stored yet. */
    @Transactional(readOnly = true)
    public MembershipSettings getSettings(String cafeId) {
        return settingsRepository.findById(cafeId).orElseGet(() -> {
            MembershipSettings s = new MembershipSettings();
            s.setCafeId(cafeId);
            return s;
        });
    }

    @Transactional
    public MembershipSettings updateSettings(User actor, MembershipSettingsRequest req) {
        if (req == null) throw bad("Request body is required");
        MembershipSettings s = getSettings(actor.getCafeId());
        Map<String, Object> before = settingsSnapshot(s);

        if (req.getDeductionOrder() != null) {
            try {
                s.setDeductionOrder(MembershipSettings.DeductionOrder.valueOf(req.getDeductionOrder().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw bad("Deduction order must be PAID_FIRST or BONUS_FIRST");
            }
        }
        if (req.getLowBalanceThresholdPaise() != null) {
            if (req.getLowBalanceThresholdPaise() < 0) throw bad("Low-balance threshold cannot be negative");
            s.setLowBalanceThresholdPaise(req.getLowBalanceThresholdPaise());
        }
        if (req.getCardNumberPrefix() != null) {
            String prefix = req.getCardNumberPrefix().trim().toUpperCase(Locale.ROOT);
            if (!CARD_PREFIX.matcher(prefix).matches()) throw bad("Card prefix must be 1-10 letters or digits");
            s.setCardNumberPrefix(prefix);
        }
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedByName(actor.getName());
        MembershipSettings saved = settingsRepository.save(s);
        logger.info("Membership settings updated: deductionOrder={} lowBalance={} prefix={}",
                saved.getDeductionOrder(), saved.getLowBalanceThresholdPaise(), saved.getCardNumberPrefix());
        audit.record(AuditRecord.of(AuditActions.MEMBERSHIP_SETTINGS_UPDATED).actor(actor)
                .entity(AuditActions.ENTITY_MEMBERSHIP_SETTINGS, saved.getCafeId())
                .description("Changed membership settings")
                .diff(before, settingsSnapshot(saved)));
        return saved;
    }

    static Map<String, Object> planSnapshot(MembershipPlan p) {
        return AuditDiff.snapshot("name", p.getName(), "load_amount_paise", p.getLoadAmountPaise(),
                "bonus_type", p.getBonusType() == null ? null : p.getBonusType().name(), "bonus_value", p.getBonusValue(),
                "validity_days", p.getValidityDays(), "is_active", p.getIsActive(), "color", p.getColor(),
                "badge", p.getBadge(), "sort_order", p.getSortOrder(), "description", p.getDescription());
    }

    static Map<String, Object> settingsSnapshot(MembershipSettings s) {
        return AuditDiff.snapshot("deduction_order", s.getDeductionOrder().name(),
                "low_balance_threshold_paise", s.getLowBalanceThresholdPaise(),
                "card_number_prefix", s.getCardNumberPrefix());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
