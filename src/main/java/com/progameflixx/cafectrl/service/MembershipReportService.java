package com.progameflixx.cafectrl.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry;
import com.progameflixx.cafectrl.entity.MembershipLedgerEntry.Type;
import com.progameflixx.cafectrl.repository.MembershipCardRepository;
import com.progameflixx.cafectrl.repository.MembershipLedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Membership reports, computed purely from the ledger (the source of truth). All money in paise.
 *  - Sales: LOAD (new card) and RECHARGE entries = money collected, by plan and payment mode; BONUS = value given.
 *  - Consumed: BILL_DEBIT minus REVERSAL (a reversal counts on the day it happens).
 *  - Forfeited: EXPIRY / RECHARGE / CLOSE forfeits.
 */
@Service
public class MembershipReportService {

    static final int MAX_RANGE_DAYS = 366;

    private final MembershipLedgerRepository ledgerRepository;
    private final MembershipCardRepository cardRepository;

    public MembershipReportService(MembershipLedgerRepository ledgerRepository, MembershipCardRepository cardRepository) {
        this.ledgerRepository = ledgerRepository;
        this.cardRepository = cardRepository;
    }

    public record PaidBonus(@JsonProperty("paid_paise") long paidPaise,
                            @JsonProperty("bonus_paise") long bonusPaise,
                            @JsonProperty("total_paise") long totalPaise) {
        static PaidBonus of(long paid, long bonus) { return new PaidBonus(paid, bonus, paid + bonus); }
    }

    public record PlanSales(@JsonProperty("plan_id") String planId,
                            @JsonProperty("plan_name") String planName,
                            @JsonProperty("new_cards") long newCards,
                            long recharges,
                            @JsonProperty("collected_paise") long collectedPaise,
                            @JsonProperty("bonus_given_paise") long bonusGivenPaise,
                            @JsonProperty("by_mode") Map<String, Long> byMode) {}

    public record Report(LocalDate from, LocalDate to,
                         @JsonProperty("new_cards") long newCards,
                         long recharges,
                         @JsonProperty("collected_paise") long collectedPaise,
                         @JsonProperty("collected_by_mode") Map<String, Long> collectedByMode,
                         @JsonProperty("bonus_given_paise") long bonusGivenPaise,
                         @JsonProperty("by_plan") List<PlanSales> byPlan,
                         PaidBonus consumed,
                         @JsonProperty("bills_paid") long billsPaid,
                         @JsonProperty("expired") PaidBonus expiredForfeit,
                         @JsonProperty("forfeited_on_recharge") PaidBonus rechargeForfeit,
                         @JsonProperty("forfeited_on_close") PaidBonus closeForfeit,
                         @JsonProperty("forfeited_total") PaidBonus forfeitedTotal,
                         @JsonProperty("adjustments_net") PaidBonus adjustmentsNet) {}

    @Transactional(readOnly = true)
    public Report report(String cafeId, LocalDate from, LocalDate to) {
        if (from == null || to == null) throw bad("from and to dates are required");
        if (to.isBefore(from)) throw bad("'to' must be on or after 'from'");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) throw bad("Date range can be at most " + MAX_RANGE_DAYS + " days");
        List<MembershipLedgerEntry> entries = entries(cafeId, from, to);

        Map<String, long[]> plan = new LinkedHashMap<>();          // planId -> [newCards, recharges, collected, bonus]
        Map<String, String> planNames = new HashMap<>();
        Map<String, Map<String, Long>> planModes = new HashMap<>();
        Map<String, Long> modes = new TreeMap<>();
        long newCards = 0, recharges = 0, collected = 0, bonusGiven = 0, billsPaid = 0;
        long[] consumed = new long[2], expired = new long[2], onRecharge = new long[2], onClose = new long[2], adjust = new long[2];

        for (MembershipLedgerEntry e : entries) {
            long paid = e.getPaidPortionPaise(), bonus = e.getBonusPortionPaise();
            String key = e.getPlanId() == null ? "unknown" : e.getPlanId();
            switch (e.getEntryType()) {
                case LOAD, RECHARGE -> {
                    long[] p = plan.computeIfAbsent(key, k -> new long[4]);
                    planNames.putIfAbsent(key, e.getPlanName());
                    if (e.getEntryType() == Type.LOAD) { p[0]++; newCards++; } else { p[1]++; recharges++; }
                    p[2] += paid;
                    collected += paid;
                    String mode = e.getPaymentMode() == null ? "unknown" : e.getPaymentMode();
                    modes.merge(mode, paid, Long::sum);
                    planModes.computeIfAbsent(key, k -> new TreeMap<>()).merge(mode, paid, Long::sum);
                }
                case BONUS -> {
                    plan.computeIfAbsent(key, k -> new long[4])[3] += bonus;
                    planNames.putIfAbsent(key, e.getPlanName());
                    bonusGiven += bonus;
                }
                case BILL_DEBIT -> { add(consumed, -paid, -bonus); billsPaid++; }
                case REVERSAL -> { add(consumed, -paid, -bonus); billsPaid--; }
                case EXPIRY_FORFEIT -> add(expired, -paid, -bonus);
                case RECHARGE_FORFEIT -> add(onRecharge, -paid, -bonus);
                case CLOSE_FORFEIT -> add(onClose, -paid, -bonus);
                case ADJUSTMENT -> add(adjust, paid, bonus);
                default -> { /* zero-amount status events */ }
            }
        }

        List<PlanSales> byPlan = plan.entrySet().stream()
                .map(en -> new PlanSales(en.getKey(), planNames.getOrDefault(en.getKey(), "Unknown"),
                        en.getValue()[0], en.getValue()[1], en.getValue()[2], en.getValue()[3],
                        planModes.getOrDefault(en.getKey(), Map.of())))
                .sorted(Comparator.comparingLong(PlanSales::collectedPaise).reversed())
                .toList();

        return new Report(from, to, newCards, recharges, collected, modes, bonusGiven, byPlan,
                PaidBonus.of(consumed[0], consumed[1]), Math.max(billsPaid, 0),
                PaidBonus.of(expired[0], expired[1]), PaidBonus.of(onRecharge[0], onRecharge[1]),
                PaidBonus.of(onClose[0], onClose[1]),
                PaidBonus.of(expired[0] + onRecharge[0] + onClose[0], expired[1] + onRecharge[1] + onClose[1]),
                PaidBonus.of(adjust[0], adjust[1]));
    }

    /** One card sale / recharge, for the daily report's "membership sales" list. */
    public record Sale(@JsonProperty("entry_id") Long entryId,
                       @JsonProperty("card_id") String cardId,
                       @JsonProperty("card_number") String cardNumber,
                       @JsonProperty("customer_name") String customerName,
                       String type,
                       @JsonProperty("plan_name") String planName,
                       String mode,
                       @JsonProperty("amount_paise") long amountPaise,
                       @JsonProperty("created_at") LocalDateTime createdAt,
                       @JsonProperty("user_name") String userName) {}

    /** Card sales and recharges (money collected) on one day, oldest first. */
    @Transactional(readOnly = true)
    public List<Sale> salesOn(String cafeId, LocalDate day) {
        List<MembershipLedgerEntry> loads = entries(cafeId, day, day).stream()
                .filter(e -> e.getEntryType() == Type.LOAD || e.getEntryType() == Type.RECHARGE)
                .toList();
        Map<String, String[]> cardInfo = new HashMap<>();
        cardRepository.findAllById(loads.stream().map(MembershipLedgerEntry::getCardId).distinct().toList())
                .forEach(c -> cardInfo.put(c.getId(), new String[]{c.getCardNumber(), c.getCustomerName()}));
        return loads.stream().map(e -> {
            String[] info = cardInfo.getOrDefault(e.getCardId(), new String[]{null, null});
            return new Sale(e.getId(), e.getCardId(), info[0], info[1], e.getEntryType().name(), e.getPlanName(),
                    e.getPaymentMode(), e.getPaidPortionPaise(), e.getCreatedAt(), e.getUserName());
        }).toList();
    }

    private List<MembershipLedgerEntry> entries(String cafeId, LocalDate from, LocalDate to) {
        return ledgerRepository.findByCafeIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(
                cafeId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private static void add(long[] acc, long paid, long bonus) {
        acc[0] += paid;
        acc[1] += bonus;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
