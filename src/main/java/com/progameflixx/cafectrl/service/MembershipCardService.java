package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditDiff;
import com.progameflixx.cafectrl.audit.AuditFormat;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import com.progameflixx.cafectrl.dto.MembershipCardRequests;
import com.progameflixx.cafectrl.dto.MembershipCardView;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.entity.MembershipCard.Status;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry.Type;
import com.progameflixx.cafectrl.entity.MembershipPaymentPart;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.MembershipSettings;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.MembershipCardRepository;
import com.progameflixx.cafectrl.repository.MembershipLedgerRepository;
import com.progameflixx.cafectrl.repository.MembershipPlanRepository;
import com.progameflixx.cafectrl.repository.MembershipSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * All membership card balance changes go through this service. Every mutation:
 *  1. row-locks the card (SELECT ... FOR UPDATE), so concurrent operations on one card serialise;
 *  2. records any due expiry first, so an expired balance is never spent or carried;
 *  3. writes immutable ledger entries and updates the cached balances in the same transaction.
 * Card balances therefore always equal the ledger sums ({@link #reconcile}).
 */
@Service
public class MembershipCardService {

    private static final Logger logger = LoggerFactory.getLogger(MembershipCardService.class);

    static final Set<String> LOAD_PAYMENT_MODES = Set.of("cash", "upi", "card");
    static final int MAX_START_DAYS_AHEAD = 365;
    static final String SYSTEM_USER = "System";
    /** payment_mode on a ledger entry paid with more than one mode; the parts are in payment_breakdown. */
    public static final String SPLIT_MODE = "split";

    private final MembershipCardRepository cardRepository;
    private final MembershipLedgerRepository ledgerRepository;
    private final MembershipPlanRepository planRepository;
    private final MembershipSettingsRepository settingsRepository;
    private final MembershipPlanService planService;
    private final Clock clock;
    private final TransactionTemplate newTransaction;
    private final AuditService audit;

    public MembershipCardService(MembershipCardRepository cardRepository, MembershipLedgerRepository ledgerRepository,
                                 MembershipPlanRepository planRepository, MembershipSettingsRepository settingsRepository,
                                 MembershipPlanService planService, Clock clock, PlatformTransactionManager txManager,
                                 AuditService audit) {
        this.audit = audit;
        this.cardRepository = cardRepository;
        this.ledgerRepository = ledgerRepository;
        this.planRepository = planRepository;
        this.settingsRepository = settingsRepository;
        this.planService = planService;
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // =====================================================================
    // Reads
    // =====================================================================

    @Transactional(readOnly = true)
    public List<MembershipCardView> list(String cafeId, String query) {
        long threshold = planService.getSettings(cafeId).getLowBalanceThresholdPaise();
        LocalDate today = today();
        String q = query == null ? "" : query.trim();
        List<MembershipCard> cards = q.isEmpty()
                ? cardRepository.findByCafeIdOrderByIssuedAtDesc(cafeId)
                : cardRepository.search(cafeId, q);
        return cards.stream().map(c -> MembershipCardView.of(c, today, threshold)).toList();
    }

    /**
     * Filters for the Memberships screen. status: ACTIVE | SCHEDULED | EXPIRED | SUSPENDED | CLOSED | ALL
     * (effective status). expiringWithinDays: active cards whose last valid day is within that many days
     * (today counts as 1). lowBalance: active cards at or below the cafe's low-balance threshold.
     */
    public record CardFilter(String q, String status, String planId, Integer expiringWithinDays, boolean lowBalance) {}

    @Transactional(readOnly = true)
    public List<MembershipCardView> list(String cafeId, CardFilter f) {
        String status = f.status() == null || f.status().isBlank() ? "ALL" : f.status().trim().toUpperCase(Locale.ROOT);
        if (!status.equals("ALL")) {
            try {
                Status.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw bad("Unknown status filter '" + f.status() + "'");
            }
        }
        if (f.expiringWithinDays() != null && f.expiringWithinDays() < 0) throw bad("expiring_within_days must be 0 or more");
        String plan = f.planId() == null || f.planId().isBlank() ? null : f.planId();
        return list(cafeId, f.q()).stream()
                .filter(v -> status.equals("ALL") || v.status().equals(status))
                .filter(v -> plan == null || plan.equals(v.planId()))
                .filter(v -> f.expiringWithinDays() == null
                        || ("ACTIVE".equals(v.status()) && v.daysRemaining() <= f.expiringWithinDays()))
                .filter(v -> !f.lowBalance() || v.lowBalance())
                .toList();
    }

    /** Top-of-screen numbers. Outstanding = what the cafe still owes: active + scheduled + suspended cards. */
    public record Summary(
            @com.fasterxml.jackson.annotation.JsonProperty("active_cards") long activeCards,
            @com.fasterxml.jackson.annotation.JsonProperty("active_customers") long activeCustomers,
            @com.fasterxml.jackson.annotation.JsonProperty("scheduled_cards") long scheduledCards,
            @com.fasterxml.jackson.annotation.JsonProperty("suspended_cards") long suspendedCards,
            @com.fasterxml.jackson.annotation.JsonProperty("expiring_7d") long expiring7d,
            @com.fasterxml.jackson.annotation.JsonProperty("low_balance") long lowBalance,
            @com.fasterxml.jackson.annotation.JsonProperty("outstanding_paid_paise") long outstandingPaidPaise,
            @com.fasterxml.jackson.annotation.JsonProperty("outstanding_bonus_paise") long outstandingBonusPaise,
            @com.fasterxml.jackson.annotation.JsonProperty("outstanding_total_paise") long outstandingTotalPaise,
            @com.fasterxml.jackson.annotation.JsonProperty("pending_forfeit_paise") long pendingForfeitPaise) {}

    @Transactional(readOnly = true)
    public Summary summary(String cafeId) {
        List<MembershipCardView> all = list(cafeId, (String) null);
        Set<String> owed = Set.of("ACTIVE", "SCHEDULED", "SUSPENDED");
        long paid = 0, bonus = 0;
        for (MembershipCardView v : all) {
            if (owed.contains(v.status())) {
                paid += v.paidBalancePaise();
                bonus += v.bonusBalancePaise();
            }
        }
        List<MembershipCardView> active = all.stream().filter(v -> "ACTIVE".equals(v.status())).toList();
        return new Summary(
                active.size(),
                active.stream().map(MembershipCardView::customerPhone).distinct().count(),
                all.stream().filter(v -> "SCHEDULED".equals(v.status())).count(),
                all.stream().filter(v -> "SUSPENDED".equals(v.status())).count(),
                active.stream().filter(v -> v.daysRemaining() <= 7).count(),
                active.stream().filter(MembershipCardView::lowBalance).count(),
                paid, bonus, paid + bonus,
                all.stream().mapToLong(MembershipCardView::pendingForfeitPaise).sum());
    }

    @Transactional(readOnly = true)
    public MembershipCardView view(String cafeId, String cardId) {
        return toView(getCard(cafeId, cardId));
    }

    @Transactional(readOnly = true)
    public MembershipCard getCard(String cafeId, String cardId) {
        return cardRepository.findByIdAndCafeId(cardId, cafeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership card not found"));
    }

    @Transactional(readOnly = true)
    public List<MembershipLedgerEntry> ledger(String cafeId, String cardId) {
        getCard(cafeId, cardId); // cafe scoping
        return ledgerRepository.findByCardIdOrderByIdDesc(cardId);
    }

    /** True when the card's cached balances equal the sums over its ledger entries. */
    @Transactional(readOnly = true)
    public boolean reconcile(String cafeId, String cardId) {
        MembershipCard card = getCard(cafeId, cardId);
        Object[] sums = ledgerRepository.sumPortions(cardId).get(0);
        long paid = ((Number) sums[0]).longValue();
        long bonus = ((Number) sums[1]).longValue();
        boolean ok = paid == card.getPaidBalancePaise() && bonus == card.getBonusBalancePaise();
        if (!ok) {
            logger.error("Membership card {} does NOT reconcile: cached paid={} bonus={}, ledger paid={} bonus={}",
                    cardId, card.getPaidBalancePaise(), card.getBonusBalancePaise(), paid, bonus);
        }
        return ok;
    }

    public MembershipCardView toView(MembershipCard card) {
        long threshold = planService.getSettings(card.getCafeId()).getLowBalanceThresholdPaise();
        return MembershipCardView.of(card, today(), threshold);
    }

    // =====================================================================
    // Issue / recharge
    // =====================================================================

    @Transactional
    public MembershipCard issue(User actor, MembershipCardRequests.Issue req) {
        if (req == null) throw bad("Request body is required");
        String cafeId = actor.getCafeId();
        String key = idemKey("ISSUE", cafeId, req.getRequestId());
        Optional<MembershipCard> already = findByIdempotency(key);
        if (already.isPresent()) {
            logger.info("Issue card request {} already processed -> card {}", req.getRequestId(), already.get().getId());
            return already.get();
        }

        String name = requireText(req.getCustomerName(), "Customer name", 255);
        String phone = normalisePhone(req.getCustomerPhone());
        MembershipPlan plan = requireSellablePlan(cafeId, req.getPlanId());
        List<MembershipPaymentPart> parts = resolvePayments(req.getPayments(), req.getPaymentMode(), plan.getLoadAmountPaise());
        LocalDate start = resolveStartDate(req.getStartDate());
        String note = optionalText(req.getNote(), 500);

        MembershipCard card = new MembershipCard();
        card.setCafeId(cafeId);
        card.setCardNumber(nextCardNumber(cafeId));
        card.setCustomerName(name);
        card.setCustomerPhone(phone);
        card.applyPlanSnapshot(plan);
        card.setStatus(Status.ACTIVE);
        card.setStartDate(start);
        card.setExpiryDate(start.plusDays(plan.getValidityDays() - 1L));
        LocalDateTime now = now();
        card.setIssuedAt(now);
        card.setPeriodPurchasedAt(now);
        card.setIssuedByUserId(actor.getId());
        card.setIssuedByName(actor.getName());
        cardRepository.save(card);

        postLoad(card, Type.LOAD, plan, parts, key, note, actor);
        logger.info("Membership card {} ({}) issued to '{}' {} on plan '{}': paid={} bonus={} valid {}..{} via {}",
                card.getId(), card.getCardNumber(), name, phone, plan.getName(), plan.getLoadAmountPaise(),
                plan.getBonusAmountPaise(), card.getStartDate(), card.getExpiryDate(), parts);
        auditCard(AuditActions.CARD_ISSUED, actor, card,
                "Sold card " + card.getCardNumber() + " (" + plan.getName() + ") to " + name + " " + phone + ": paid "
                        + AuditFormat.paise(plan.getLoadAmountPaise()) + " by " + describeParts(parts) + ", bonus "
                        + AuditFormat.paise(plan.getBonusAmountPaise()) + ", valid " + AuditFormat.date(card.getStartDate())
                        + " - " + AuditFormat.date(card.getExpiryDate()),
                r -> r.detail("plan", plan.getName()).detail("paid_paise", plan.getLoadAmountPaise())
                        .detail("bonus_paise", plan.getBonusAmountPaise()).detail("payments", parts)
                        .detail("start_date", card.getStartDate()).detail("expiry_date", card.getExpiryDate())
                        .detail("customer_name", name).detail("customer_phone", phone).detail("note", note));
        return card;
    }

    /**
     * Starts a new validity period on the card. No carry-forward: any remaining balance is forfeited first
     * (EXPIRY_FORFEIT if the old period had already lapsed, otherwise RECHARGE_FORFEIT).
     */
    @Transactional
    public MembershipCard recharge(User actor, String cardId, MembershipCardRequests.Recharge req) {
        if (req == null) throw bad("Request body is required");
        String cafeId = actor.getCafeId();
        String key = idemKey("RECHARGE", cafeId, req.getRequestId());
        Optional<MembershipCard> already = findByIdempotency(key);
        if (already.isPresent()) {
            logger.info("Recharge request {} already processed -> card {}", req.getRequestId(), cardId);
            return already.get();
        }

        MembershipCard card = lock(cafeId, cardId);
        if (card.getStatus() == Status.CLOSED) throw bad("This card is closed and can't be recharged");
        if (card.effectiveStatus(today()) == Status.SUSPENDED) throw bad("This card is suspended. Reactivate it before recharging");
        if (req.getExpectedForfeitPaise() != null && req.getExpectedForfeitPaise() != card.totalBalancePaise()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The card balance changed since you opened this screen. Please reload and try again");
        }

        MembershipPlan plan = requireSellablePlan(cafeId, req.getPlanId());
        List<MembershipPaymentPart> parts = resolvePayments(req.getPayments(), req.getPaymentMode(), plan.getLoadAmountPaise());
        LocalDate start = resolveStartDate(req.getStartDate());
        String note = optionalText(req.getNote(), 500);

        Map<String, Object> before = cardSnapshot(card);
        String oldPlan = card.getPlanName();
        expireIfDue(card, actor);
        long forfeited = card.totalBalancePaise();
        forfeitAll(card, Type.RECHARGE_FORFEIT, actor, "Remaining balance forfeited on recharge (no carry-forward)");

        card.applyPlanSnapshot(plan);
        card.setStatus(Status.ACTIVE);
        card.setStartDate(start);
        card.setExpiryDate(start.plusDays(plan.getValidityDays() - 1L));
        card.setPeriodPurchasedAt(now());

        postLoad(card, Type.RECHARGE, plan, parts, key, note, actor);
        logger.info("Membership card {} ({}) recharged on plan '{}': paid={} bonus={} valid {}..{} via {}",
                card.getId(), card.getCardNumber(), plan.getName(), plan.getLoadAmountPaise(),
                plan.getBonusAmountPaise(), card.getStartDate(), card.getExpiryDate(), parts);
        auditCard(AuditActions.CARD_RECHARGED, actor, card,
                "Recharged card " + card.getCardNumber() + " (" + card.getCustomerName() + ") on " + plan.getName()
                        + (plan.getName().equals(oldPlan) ? "" : " (was " + oldPlan + ")") + ": paid "
                        + AuditFormat.paise(plan.getLoadAmountPaise()) + " by " + describeParts(parts) + ", bonus "
                        + AuditFormat.paise(plan.getBonusAmountPaise())
                        + (forfeited > 0 ? ", " + AuditFormat.paise(forfeited) + " old balance forfeited" : "")
                        + ", valid " + AuditFormat.date(card.getStartDate()) + " - " + AuditFormat.date(card.getExpiryDate()),
                r -> r.diff(before, cardSnapshot(card)).detail("payments", parts)
                        .detail("forfeited_paise", forfeited).detail("note", note));
        return card;
    }

    /** One ledger entry for the whole sale; payment_breakdown records how it was paid (one or several modes). */
    private void postLoad(MembershipCard card, Type loadType, MembershipPlan plan, List<MembershipPaymentPart> parts,
                          String key, String note, User actor) {
        post(card, loadType, plan.getLoadAmountPaise(), 0, actor, e -> {
            e.setPaymentMode(parts.size() == 1 ? parts.getFirst().getMode() : SPLIT_MODE);
            e.setPaymentBreakdown(parts);
            e.setPlanId(plan.getId());
            e.setPlanName(plan.getName());
            e.setIdempotencyKey(key);
            e.setNote(note);
        });
        long bonus = plan.getBonusAmountPaise();
        if (bonus > 0) {
            post(card, Type.BONUS, 0, bonus, actor, e -> {
                e.setPlanId(plan.getId());
                e.setPlanName(plan.getName());
                e.setIdempotencyKey(key == null ? null : key + ":BONUS");
                e.setNote(plan.getBonusType() == MembershipPlan.BonusType.PERCENT
                        ? "Bonus " + plan.getBonusValue() / 100.0 + "% of load"
                        : "Fixed bonus");
            });
        }
    }

    // =====================================================================
    // Admin actions (reason mandatory)
    // =====================================================================

    @Transactional
    public MembershipCard adjust(User admin, String cardId, MembershipCardRequests.Adjust req) {
        if (req == null) throw bad("Request body is required");
        String reason = requireText(req.getReason(), "Reason", 500);
        long paid = req.getPaidPaise() == null ? 0 : req.getPaidPaise();
        long bonus = req.getBonusPaise() == null ? 0 : req.getBonusPaise();
        if (paid < 0 || bonus < 0) throw bad("Amounts must not be negative; choose credit or debit instead");
        if (paid + bonus == 0) throw bad("Enter an amount to adjust");
        boolean debit;
        String dir = req.getDirection() == null ? "" : req.getDirection().trim().toUpperCase(Locale.ROOT);
        switch (dir) {
            case "CREDIT" -> debit = false;
            case "DEBIT" -> debit = true;
            default -> throw bad("Direction must be CREDIT or DEBIT");
        }

        String key = idemKey("ADJUST", admin.getCafeId(), req.getRequestId());
        Optional<MembershipCard> already = findByIdempotency(key);
        if (already.isPresent()) {
            logger.info("Adjustment request {} already processed -> card {}", req.getRequestId(), cardId);
            return already.get();
        }
        MembershipCard card = lock(admin.getCafeId(), cardId);
        expireIfDue(card, admin);
        if (card.getStatus() == Status.CLOSED) throw bad("This card is closed");
        if (card.getStatus() == Status.EXPIRED) throw bad("This card has expired. Extend its expiry first");
        if (debit && (paid > card.getPaidBalancePaise() || bonus > card.getBonusBalancePaise())) {
            throw bad("Debit is more than the card's balance");
        }

        long sign = debit ? -1 : 1;
        Map<String, Object> before = cardSnapshot(card);
        post(card, Type.ADJUSTMENT, sign * paid, sign * bonus, admin, e -> {
            e.setNote(reason);
            e.setIdempotencyKey(key);
        });
        auditCard(AuditActions.CARD_ADJUSTED, admin, card,
                (debit ? "Debited " : "Credited ") + AuditFormat.paise(paid + bonus) + (debit ? " from" : " to")
                        + " card " + card.getCardNumber() + " (" + card.getCustomerName() + ")"
                        + (paid > 0 && bonus > 0 ? ": paid " + AuditFormat.paise(paid) + ", bonus " + AuditFormat.paise(bonus)
                        : paid > 0 ? " (paid balance)" : " (bonus balance)"),
                r -> r.reason(reason).diff(before, cardSnapshot(card)).detail("direction", dir)
                        .detail("paid_paise", paid).detail("bonus_paise", bonus));
        logger.info("Membership card {} manual {}: paid={} bonus={} reason='{}'", cardId, dir, paid, bonus, reason);
        return card;
    }

    /**
     * Moves expiry later. If the card lapsed but the nightly job hasn't forfeited it yet, the balance survives.
     * If it was already expired and forfeited, the card is reactivated with whatever balance it has (zero);
     * an admin can credit it back with a reason.
     */
    @Transactional
    public MembershipCard extendExpiry(User admin, String cardId, MembershipCardRequests.Extend req) {
        if (req == null) throw bad("Request body is required");
        String reason = requireText(req.getReason(), "Reason", 500);
        LocalDate newExpiry = req.getNewExpiryDate();
        if (newExpiry == null) throw bad("New expiry date is required");

        MembershipCard card = lock(admin.getCafeId(), cardId);
        if (card.getStatus() == Status.CLOSED) throw bad("This card is closed");
        LocalDate old = card.getExpiryDate();
        if (!newExpiry.isAfter(old)) throw bad("New expiry date must be after the current one (" + old + ")");
        if (newExpiry.isBefore(today())) throw bad("New expiry date can't be in the past");

        Map<String, Object> before = cardSnapshot(card);
        if (card.getStatus() == Status.EXPIRED) card.setStatus(Status.ACTIVE);
        card.setExpiryDate(newExpiry);
        post(card, Type.EXPIRY_EXTENDED, 0, 0, admin, e -> e.setNote("Expiry " + old + " -> " + newExpiry + ": " + reason));
        logger.info("Membership card {} expiry extended {} -> {} reason='{}'", cardId, old, newExpiry, reason);
        auditCard(AuditActions.CARD_EXPIRY_EXTENDED, admin, card,
                "Extended card " + card.getCardNumber() + " (" + card.getCustomerName() + ") expiry from "
                        + AuditFormat.date(old) + " to " + AuditFormat.date(newExpiry),
                r -> r.reason(reason).diff(before, cardSnapshot(card)));
        return card;
    }

    /**
     * Moves the start of the card's current validity period. Expiry moves by the same number of days, so the
     * period length (including any earlier extension) is kept. Refused when:
     *  - the card is closed or has already expired (extend the expiry instead);
     *  - the new start is before the day this period was bought, or more than a year after it;
     *  - the card was already used on a day before the new start;
     *  - the new dates would end before today.
     */
    @Transactional
    public MembershipCard changeStartDate(User admin, String cardId, MembershipCardRequests.ChangeStart req) {
        if (req == null) throw bad("Request body is required");
        String reason = requireText(req.getReason(), "Reason", 500);
        LocalDate newStart = req.getNewStartDate();
        if (newStart == null) throw bad("New start date is required");

        MembershipCard card = lock(admin.getCafeId(), cardId);
        if (card.getStatus() == Status.CLOSED) throw bad("This card is closed");
        if (card.effectiveStatus(today()) == Status.EXPIRED) throw bad("This card has expired. Extend its expiry instead");

        LocalDate oldStart = card.getStartDate();
        if (newStart.equals(oldStart)) throw bad("The card already starts on " + oldStart);
        LocalDate bought = card.getPeriodPurchasedAt().toLocalDate();
        if (newStart.isBefore(bought)) throw bad("Start date can't be before the day the card was bought (" + bought + ")");
        if (newStart.isAfter(bought.plusDays(MAX_START_DAYS_AHEAD))) {
            throw bad("Start date can be at most " + MAX_START_DAYS_AHEAD + " days after the purchase date");
        }
        LocalDate lastUsed = card.getLastUsedAt() == null ? null : card.getLastUsedAt().toLocalDate();
        if (lastUsed != null && !lastUsed.isBefore(bought) && lastUsed.isBefore(newStart)) {
            throw bad("The card was already used on " + lastUsed + ", so it can't start after that");
        }
        long shift = java.time.temporal.ChronoUnit.DAYS.between(oldStart, newStart);
        LocalDate oldExpiry = card.getExpiryDate();
        LocalDate newExpiry = oldExpiry.plusDays(shift);
        if (newExpiry.isBefore(today())) throw bad("With that start date the card would already have expired on " + newExpiry);

        Map<String, Object> before = cardSnapshot(card);
        card.setStartDate(newStart);
        card.setExpiryDate(newExpiry);
        post(card, Type.START_DATE_CHANGED, 0, 0, admin, e -> e.setNote(
                "Valid " + oldStart + ".." + oldExpiry + " -> " + newStart + ".." + newExpiry + ": " + reason));
        logger.info("Membership card {} start date {} -> {} (expiry {} -> {}) reason='{}'",
                cardId, oldStart, newStart, oldExpiry, newExpiry, reason);
        auditCard(AuditActions.CARD_START_DATE_CHANGED, admin, card,
                "Moved card " + card.getCardNumber() + " (" + card.getCustomerName() + ") start from "
                        + AuditFormat.date(oldStart) + " to " + AuditFormat.date(newStart) + " (valid till "
                        + AuditFormat.date(newExpiry) + ")",
                r -> r.reason(reason).diff(before, cardSnapshot(card)));
        return card;
    }

    @Transactional
    public MembershipCard changeStatus(User admin, String cardId, MembershipCardRequests.StatusChange req) {
        if (req == null) throw bad("Request body is required");
        String reason = requireText(req.getReason(), "Reason", 500);
        String action = req.getAction() == null ? "" : req.getAction().trim().toUpperCase(Locale.ROOT);

        MembershipCard card = lock(admin.getCafeId(), cardId);
        if (card.getStatus() == Status.CLOSED) throw bad("This card is already closed");
        Status eff = card.effectiveStatus(today());
        Map<String, Object> before = cardSnapshot(card);
        long forfeitedOnClose = 0;

        switch (action) {
            case "SUSPEND" -> {
                if (eff == Status.EXPIRED) throw bad("This card has expired");
                if (card.getStatus() == Status.SUSPENDED) throw bad("This card is already suspended");
                card.setStatus(Status.SUSPENDED);
                post(card, Type.SUSPENDED, 0, 0, admin, e -> e.setNote(reason));
            }
            case "REACTIVATE" -> {
                if (card.getStatus() != Status.SUSPENDED) throw bad("Only a suspended card can be reactivated");
                if (eff == Status.EXPIRED) throw bad("This card has expired. Extend its expiry instead");
                card.setStatus(Status.ACTIVE);
                post(card, Type.REACTIVATED, 0, 0, admin, e -> e.setNote(reason));
            }
            case "CLOSE" -> {
                expireIfDue(card, admin);
                forfeitedOnClose = card.totalBalancePaise();
                forfeitAll(card, Type.CLOSE_FORFEIT, admin, "Balance forfeited on close: " + reason);
                card.setStatus(Status.CLOSED);
                post(card, Type.CLOSED, 0, 0, admin, e -> e.setNote(reason));
            }
            default -> throw bad("Action must be SUSPEND, REACTIVATE or CLOSE");
        }
        logger.info("Membership card {} {} reason='{}'", cardId, action, reason);
        long forfeitedFinal = forfeitedOnClose;
        String label = card.getCardNumber() + " (" + card.getCustomerName() + ")";
        switch (action) {
            case "SUSPEND" -> auditCard(AuditActions.CARD_SUSPENDED, admin, card, "Suspended card " + label,
                    r -> r.reason(reason).diff(before, cardSnapshot(card)));
            case "REACTIVATE" -> auditCard(AuditActions.CARD_REACTIVATED, admin, card, "Reactivated card " + label,
                    r -> r.reason(reason).diff(before, cardSnapshot(card)));
            default -> auditCard(AuditActions.CARD_CLOSED, admin, card, "Closed card " + label
                            + (forfeitedFinal > 0 ? ", " + AuditFormat.paise(forfeitedFinal) + " balance forfeited" : ""),
                    r -> r.reason(reason).diff(before, cardSnapshot(card)).detail("forfeited_paise", forfeitedFinal));
        }
        return card;
    }

    // =====================================================================
    // Billing (called inside the checkout / reopen / void transaction)
    // =====================================================================

    /**
     * Deducts {@code amountPaise} from the card for one bill. Idempotent per (session, bill number): a retry
     * returns the existing debit. Split between paid and bonus balance by the cafe's deduction order.
     */
    @Transactional
    public MembershipLedgerEntry debitForBill(User actor, String cafeId, String cardId, String sessionId,
                                              int billSeq, long amountPaise, String customerName) {
        if (amountPaise <= 0) throw bad("Card amount must be greater than zero");
        String key = "BILL:" + sessionId + ":" + billSeq;
        Optional<MembershipLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            logger.info("Bill debit {} already recorded (entry {})", key, existing.get().getId());
            return existing.get();
        }

        MembershipCard card = lock(cafeId, cardId);
        expireIfDue(card, actor);
        Status eff = card.effectiveStatus(today());
        if (eff != Status.ACTIVE) {
            String why = switch (eff) {
                case EXPIRED -> "expired on " + card.getExpiryDate();
                case SCHEDULED -> "only starts on " + card.getStartDate();
                default -> "is " + eff.name().toLowerCase(Locale.ROOT);
            };
            throw bad("Card " + card.getCardNumber() + " " + why + " and can't be used for billing");
        }
        if (card.totalBalancePaise() < amountPaise) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Card " + card.getCardNumber() + " has only "
                    + rupees(card.totalBalancePaise()) + ", less than the " + rupees(amountPaise) + " requested");
        }

        MembershipSettings.DeductionOrder order = planService.getSettings(cafeId).getDeductionOrder();
        long fromPaid;
        long fromBonus;
        if (order == MembershipSettings.DeductionOrder.BONUS_FIRST) {
            fromBonus = Math.min(card.getBonusBalancePaise(), amountPaise);
            fromPaid = amountPaise - fromBonus;
        } else {
            fromPaid = Math.min(card.getPaidBalancePaise(), amountPaise);
            fromBonus = amountPaise - fromPaid;
        }

        card.setLastUsedAt(now());
        MembershipLedgerEntry entry = post(card, Type.BILL_DEBIT, -fromPaid, -fromBonus, actor, e -> {
            e.setSessionId(sessionId);
            e.setIdempotencyKey(key);
            e.setNote("Bill #" + billSeq + (customerName != null ? " for " + customerName : ""));
        });
        logger.info("Card {} ({}) debited {} for session {} bill #{} (paid {}, bonus {}, order {})",
                cardId, card.getCardNumber(), amountPaise, sessionId, billSeq, fromPaid, fromBonus, order);
        auditCard(AuditActions.CARD_DEBITED, actor, card,
                "Deducted " + AuditFormat.paise(amountPaise) + " from card " + card.getCardNumber() + " for Bill "
                        + AuditFormat.billNo(sessionId) + (customerName != null ? " (" + customerName + ")" : "")
                        + "; balance left " + AuditFormat.paise(card.totalBalancePaise()),
                r -> r.detail("session_id", sessionId).detail("bill_seq", billSeq).detail("amount_paise", amountPaise)
                        .detail("from_paid_paise", fromPaid).detail("from_bonus_paise", fromBonus)
                        .detail("deduction_order", order.name()).detail("ledger_entry_id", entry.getId())
                        .detail("balance_after_paise", card.totalBalancePaise()));
        return entry;
    }

    /** Result of reversing one bill's card payments. */
    public record Reversal(String cardId, String cardNumber, long creditedPaise, long reForfeitedPaise) {}

    /**
     * Reverses every not-yet-reversed card debit of a session, restoring exactly the paid / bonus portions taken.
     * If the card has since expired or been closed, the credit is recorded and immediately forfeited again,
     * so the liability stays correct and the history shows what happened.
     */
    @Transactional
    public List<Reversal> reverseBillDebits(User actor, String cafeId, String sessionId, String reason) {
        List<Reversal> out = new ArrayList<>();
        for (MembershipLedgerEntry debit : ledgerRepository.findBySessionIdAndEntryTypeOrderByIdAsc(sessionId, Type.BILL_DEBIT)) {
            String key = "REVERSAL:" + debit.getId();
            if (ledgerRepository.findByIdempotencyKey(key).isPresent()) continue; // already reversed

            MembershipCard card = lock(cafeId, debit.getCardId());
            expireIfDue(card, actor);
            post(card, Type.REVERSAL, -debit.getPaidPortionPaise(), -debit.getBonusPortionPaise(), actor, e -> {
                e.setSessionId(sessionId);
                e.setReversesEntryId(debit.getId());
                e.setIdempotencyKey(key);
                e.setNote(reason);
            });
            long credited = -debit.getAmountPaise();
            long reForfeited = 0;
            if (card.getStatus() == Status.EXPIRED || card.getStatus() == Status.CLOSED) {
                reForfeited = card.totalBalancePaise();
                forfeitAll(card, card.getStatus() == Status.EXPIRED ? Type.EXPIRY_FORFEIT : Type.CLOSE_FORFEIT, actor,
                        "Reversed bill amount forfeited: card is " + card.getStatus().name().toLowerCase(Locale.ROOT));
            }
            logger.info("Reversed card debit {} on card {} for session {}: credited {} (re-forfeited {})",
                    debit.getId(), card.getCardNumber(), sessionId, credited, reForfeited);
            out.add(new Reversal(card.getId(), card.getCardNumber(), credited, reForfeited));
            long reForfeitedFinal = reForfeited;
            auditCard(AuditActions.CARD_DEBIT_REVERSED, actor, card,
                    "Returned " + AuditFormat.paise(credited) + " to card " + card.getCardNumber() + " from Bill "
                            + AuditFormat.billNo(sessionId)
                            + (reForfeitedFinal > 0 ? " (then forfeited: card is " + card.getStatus().name().toLowerCase(Locale.ROOT) + ")" : ""),
                    r -> r.reason(reason).detail("session_id", sessionId).detail("reversed_ledger_entry_id", debit.getId())
                            .detail("credited_paise", credited).detail("re_forfeited_paise", reForfeitedFinal));
        }
        return out;
    }

    // =====================================================================
    // Audit (same transaction as the balance change)
    // =====================================================================

    /** actor null = SYSTEM (scheduled expiry). */
    private void auditCard(String action, User actor, MembershipCard card, String description,
                           java.util.function.Consumer<AuditRecord> extra) {
        AuditRecord r = AuditRecord.of(action).entity(AuditActions.ENTITY_MEMBERSHIP_CARD, card.getId())
                .cafe(card.getCafeId()).description(description);
        if (actor == null) r.system(); else r.actor(actor);
        if (extra != null) extra.accept(r);
        audit.recordInTransaction(r);
    }

    private static Map<String, Object> cardSnapshot(MembershipCard c) {
        return AuditDiff.snapshot(
                "status", c.getStatus().name(),
                "plan", c.getPlanName(),
                "paid_balance_paise", c.getPaidBalancePaise(),
                "bonus_balance_paise", c.getBonusBalancePaise(),
                "start_date", String.valueOf(c.getStartDate()),
                "expiry_date", String.valueOf(c.getExpiryDate()));
    }

    private static String describeParts(List<MembershipPaymentPart> parts) {
        return String.join(" + ", parts.stream()
                .map(p -> p.getMode().toUpperCase(Locale.ROOT) + " " + AuditFormat.paise(p.getAmountPaise())).toList());
    }

    private static String rupees(long paise) {
        return "₹" + java.math.BigDecimal.valueOf(paise).movePointLeft(2).toPlainString();
    }

    // =====================================================================
    // Expiry
    // =====================================================================

    /** Nightly / startup job: records expiry (and forfeits the balance) for every lapsed card. */
    public int expireDueCards() {
        List<String> ids = cardRepository.findIdsDueForExpiry(List.of(Status.ACTIVE, Status.SUSPENDED), today());
        int expired = 0;
        for (String id : ids) {
            try {
                Boolean done = newTransaction.execute(tx -> {
                    MembershipCard card = cardRepository.findById(id).orElse(null);
                    if (card == null) return false;
                    return expireIfDue(lock(card.getCafeId(), id), null);
                });
                if (Boolean.TRUE.equals(done)) expired++;
            } catch (RuntimeException e) {
                logger.error("Failed to expire membership card {}: {}", id, e.toString(), e);
            }
        }
        if (!ids.isEmpty()) logger.info("Membership expiry run: {} of {} due card(s) expired", expired, ids.size());
        return expired;
    }

    /** Must be called with the card locked. Returns true if the card was moved to EXPIRED. */
    boolean expireIfDue(MembershipCard card, User actor) {
        if (card.getStatus() != Status.ACTIVE && card.getStatus() != Status.SUSPENDED) return false;
        if (!today().isAfter(card.getExpiryDate())) return false;
        LocalDate expiry = card.getExpiryDate();
        long forfeited = card.totalBalancePaise();
        long paidLeft = card.getPaidBalancePaise();
        long bonusLeft = card.getBonusBalancePaise();
        forfeitAll(card, Type.EXPIRY_FORFEIT, actor, "Card expired on " + expiry, "EXPIRY:" + card.getId() + ":" + expiry);
        card.setStatus(Status.EXPIRED);
        auditCard(AuditActions.CARD_EXPIRED, null, card,
                "Card " + card.getCardNumber() + " (" + card.getCustomerName() + ") expired after "
                        + AuditFormat.date(expiry) + (forfeited > 0 ? "; " + AuditFormat.paise(forfeited) + " forfeited" : ""),
                r -> r.detail("expiry_date", expiry).detail("forfeited_paise", forfeited)
                        .detail("forfeited_paid_paise", paidLeft).detail("forfeited_bonus_paise", bonusLeft));
        logger.info("Membership card {} ({}) expired (valid till {})", card.getId(), card.getCardNumber(), expiry);
        return true;
    }

    // =====================================================================
    // Ledger core
    // =====================================================================

    private void forfeitAll(MembershipCard card, Type type, User actor, String note) {
        forfeitAll(card, type, actor, note, null);
    }

    private void forfeitAll(MembershipCard card, Type type, User actor, String note, String key) {
        long paid = card.getPaidBalancePaise();
        long bonus = card.getBonusBalancePaise();
        if (paid + bonus == 0) return;
        post(card, type, -paid, -bonus, actor, e -> {
            e.setNote(note);
            e.setIdempotencyKey(key);
        });
    }

    /**
     * Applies a signed change to the (locked) card and records it. Balances can never go negative.
     * {@code actor} null means the system (scheduled job).
     */
    MembershipLedgerEntry post(MembershipCard card, Type type, long paidDelta, long bonusDelta, User actor,
                               Consumer<MembershipLedgerEntry> customise) {
        long newPaid = card.getPaidBalancePaise() + paidDelta;
        long newBonus = card.getBonusBalancePaise() + bonusDelta;
        if (newPaid < 0 || newBonus < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient card balance");
        }
        card.setPaidBalancePaise(newPaid);
        card.setBonusBalancePaise(newBonus);

        MembershipLedgerEntry e = new MembershipLedgerEntry();
        e.setCafeId(card.getCafeId());
        e.setCardId(card.getId());
        e.setEntryType(type);
        e.setPaidPortionPaise(paidDelta);
        e.setBonusPortionPaise(bonusDelta);
        e.setAmountPaise(paidDelta + bonusDelta);
        e.setPaidBalanceAfterPaise(newPaid);
        e.setBonusBalanceAfterPaise(newBonus);
        e.setUserId(actor != null ? actor.getId() : null);
        e.setUserName(actor != null ? actor.getName() : SYSTEM_USER);
        e.setCreatedAt(now());
        if (customise != null) customise.accept(e);
        cardRepository.save(card);
        return ledgerRepository.saveAndFlush(e);
    }

    MembershipCard lock(String cafeId, String cardId) {
        return cardRepository.findForUpdate(cardId, cafeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership card not found"));
    }

    // =====================================================================
    // Card numbers
    // =====================================================================

    /** Next sequential number for the cafe, e.g. MC-000042, handed out under a row lock on the settings row. */
    private String nextCardNumber(String cafeId) {
        ensureSettingsRow(cafeId);
        MembershipSettings s = settingsRepository.findForUpdate(cafeId)
                .orElseThrow(() -> new IllegalStateException("Membership settings row missing for cafe " + cafeId));
        long seq = s.getNextCardSeq();
        s.setNextCardSeq(seq + 1);
        settingsRepository.save(s);
        return s.getCardNumberPrefix() + "-" + String.format("%06d", seq);
    }

    /** Creates the cafe's settings row with defaults if missing; tolerates two first-ever issues racing. */
    private void ensureSettingsRow(String cafeId) {
        if (settingsRepository.existsById(cafeId)) return;
        try {
            newTransaction.executeWithoutResult(tx -> {
                if (!settingsRepository.existsById(cafeId)) {
                    MembershipSettings s = new MembershipSettings();
                    s.setCafeId(cafeId);
                    settingsRepository.saveAndFlush(s);
                }
            });
        } catch (DataIntegrityViolationException e) {
            logger.debug("Settings row for cafe {} was created concurrently", cafeId);
        }
    }

    // =====================================================================
    // Validation helpers
    // =====================================================================

    private Optional<MembershipCard> findByIdempotency(String key) {
        if (key == null) return Optional.empty();
        return ledgerRepository.findByIdempotencyKey(key).flatMap(e -> cardRepository.findById(e.getCardId()));
    }

    /** Looks up the card created/recharged by an earlier request with this id (used after a duplicate-key race). */
    @Transactional(readOnly = true)
    public Optional<MembershipCard> findProcessedRequest(String operation, String cafeId, String requestId) {
        return findByIdempotency(idemKey(operation, cafeId, requestId));
    }

    private static String idemKey(String operation, String cafeId, String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        String rid = requestId.trim();
        if (rid.length() > 64) throw bad("request_id is too long");
        return operation + ":" + cafeId + ":" + rid;
    }

    private MembershipPlan requireSellablePlan(String cafeId, String planId) {
        if (planId == null || planId.isBlank()) throw bad("Choose a plan");
        MembershipPlan plan = planRepository.findByIdAndCafeId(planId, cafeId)
                .orElseThrow(() -> bad("Plan not found"));
        if (!Boolean.TRUE.equals(plan.getIsActive())) throw bad("Plan '" + plan.getName() + "' is not on sale");
        return plan;
    }

    /**
     * How a sale / recharge was paid. With {@code payments}: every part must be cash/upi/card with an amount above
     * zero, parts of the same mode are merged, and the total must equal the plan price exactly (no pending).
     * Without it, {@code singleMode} pays the full price.
     */
    static List<MembershipPaymentPart> resolvePayments(List<MembershipPaymentPart> payments, String singleMode, long pricePaise) {
        if (payments == null || payments.isEmpty()) {
            return List.of(new MembershipPaymentPart(requireLoadMode(singleMode), pricePaise));
        }
        Map<String, Long> byMode = new LinkedHashMap<>();
        for (MembershipPaymentPart p : payments) {
            if (p == null) throw bad("Invalid payment line");
            String mode = requireLoadMode(p.getMode());
            if (p.getAmountPaise() == null || p.getAmountPaise() <= 0) throw bad("Each payment amount must be greater than zero");
            byMode.merge(mode, p.getAmountPaise(), Long::sum);
        }
        long total = byMode.values().stream().mapToLong(Long::longValue).sum();
        if (total != pricePaise) {
            throw bad("Payments add up to " + rupees(total) + " but the plan price is " + rupees(pricePaise));
        }
        List<MembershipPaymentPart> parts = new ArrayList<>();
        byMode.forEach((m, a) -> parts.add(new MembershipPaymentPart(m, a)));
        return parts;
    }

    private static String requireLoadMode(String raw) {
        String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!LOAD_PAYMENT_MODES.contains(mode)) throw bad("Payment mode must be cash, upi or card");
        return mode;
    }

    private LocalDate resolveStartDate(LocalDate requested) {
        LocalDate today = today();
        if (requested == null) return today;
        if (requested.isBefore(today)) throw bad("Start date can't be before today");
        if (requested.isAfter(today.plusDays(MAX_START_DAYS_AHEAD))) {
            throw bad("Start date can be at most " + MAX_START_DAYS_AHEAD + " days ahead");
        }
        return requested;
    }

    /** Keeps digits and a leading '+'; requires 6-15 digits. Several cards may share a phone number. */
    static String normalisePhone(String raw) {
        if (raw == null) throw bad("Customer phone is required");
        String s = raw.trim();
        boolean plus = s.startsWith("+");
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() < 6 || digits.length() > 15) throw bad("Enter a valid phone number");
        return plus ? "+" + digits : digits;
    }

    private static String requireText(String raw, String label, int max) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw bad(label + " is required");
        if (s.length() > max) throw bad(label + " must be at most " + max + " characters");
        return s;
    }

    private static String optionalText(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.length() > max) throw bad("Note must be at most " + max + " characters");
        return s;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
