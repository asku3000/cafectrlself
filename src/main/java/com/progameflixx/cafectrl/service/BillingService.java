package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.GameSession;
import com.progameflixx.cafectrl.entity.GameSessionItem;
import com.progameflixx.cafectrl.entity.RateCard;
import com.progameflixx.cafectrl.repository.RateCardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BillingService {

    @Autowired
    private RateCardRepository rateCardRepository;

    private static final String[] WEEKDAYS = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    public Map<String, Object> computeSessionBill(CustomerSession session) {
        List<Map<String, Object>> gamesBreakdown = new ArrayList<>();
        double gamesTotal = 0.0;
        double itemsTotal = 0.0;

        for (GameSession g : session.getGames()) {
            RateCard rc = rateCardRepository.findById(g.getRateCardId()).orElse(null);
            LocalDateTime start = g.getStartTime();
            LocalDateTime end = g.getEndTime() != null ? g.getEndTime() : LocalDateTime.now();
            int playerCount = g.getPlayerCount() != null ? g.getPlayerCount() : 1;

            Map<String, Object> charge = computeGameCharge(rc, start, end, playerCount);

            List<Map<String, Object>> itemsBreakdown = new ArrayList<>();
            double itemsSubtotal = 0.0;

            for (GameSessionItem it : g.getItems()) {
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("id", it.getId());
                itemMap.put("name", it.getName());
                itemMap.put("qty", it.getQty());
                itemMap.put("total", it.getTotal());
                itemMap.put("type", it.getType());
                itemsBreakdown.add(itemMap);
                itemsSubtotal += (it.getTotal() != null ? it.getTotal() : 0.0);
            }

            double gameAmount = (Double) charge.getOrDefault("amount", 0.0);

            Map<String, Object> gameData = new HashMap<>();
            gameData.put("game_session_id", g.getId());
            gameData.put("resource_name", g.getResourceName());
            gameData.put("game_type_name", g.getGameTypeName());
            gameData.put("status", g.getStatus());
            gameData.put("start_time", start.toString());
            gameData.put("end_time", end.toString());
            gameData.put("charge", charge);
            gameData.put("items", itemsBreakdown);
            gameData.put("items_subtotal", Math.round(itemsSubtotal * 100.0) / 100.0);
            gameData.put("subtotal", Math.round((gameAmount + itemsSubtotal) * 100.0) / 100.0);

            gamesBreakdown.add(gameData);
            gamesTotal += gameAmount;
            itemsTotal += itemsSubtotal;
        }

        double subtotal = Math.round((gamesTotal + itemsTotal) * 100.0) / 100.0;
        double adjustment = session.getAdjustment() != null ? session.getAdjustment() : 0.0;
        double grandTotal = Math.round((subtotal + adjustment) * 100.0) / 100.0;

        Map<String, Object> result = new HashMap<>();
        result.put("games", gamesBreakdown);
        result.put("games_total", Math.round(gamesTotal * 100.0) / 100.0);
        result.put("items_total", Math.round(itemsTotal * 100.0) / 100.0);
        result.put("subtotal", subtotal);
        result.put("adjustment", adjustment);
        result.put("grand_total", grandTotal);

        return result;
    }

    public Map<String, Object> computeGameCharge(RateCard card, LocalDateTime startTime, LocalDateTime endTime, int playerCount) {
        if (startTime == null || endTime == null || card == null) {
            return Map.of("amount", 0.0);
        }

        // 1. Fetch dynamic settings directly from your RateCard entity
        int graceMinutes = card.getGraceMinutes() != null ? card.getGraceMinutes() : 0;
        int intervalMinutes = card.getBillingIntervalMinutes() != null ? card.getBillingIntervalMinutes() : 30;

        // 2. Calculate billable time
        long totalMinutes = java.time.Duration.between(startTime, endTime).toMinutes();
        long effectiveMinutes = totalMinutes - graceMinutes;

        if (effectiveMinutes <= 0) {
            effectiveMinutes = 1; // Minimum charge threshold
        }

        // 3. Format weekday (e.g., "Mon") and convert to lower-case to match your JSON ("mon")
        String weekday = startTime.getDayOfWeek()
                .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                .toLowerCase();

        // 4. Fetch dynamic pricing from your JSON structure
        double rateInterval = extractPrice(card, weekday, intervalMinutes, playerCount); // e.g., 30m rate
        double rate60Min = extractPrice(card, weekday, 60, playerCount);                 // e.g., 60m rate

        // Fallbacks to prevent 0.0 bills if someone picks an unconfigured player count
        if (rateInterval == 0.0 && rate60Min == 0.0) {
            // Default to 1-player rates if exact match fails
            rateInterval = extractPrice(card, weekday, intervalMinutes, 1);
            rate60Min = extractPrice(card, weekday, 60, 1);
        }

        // 5. Block Grouping Algorithm
        long totalBlocks = (long) Math.ceil((double) effectiveMinutes / intervalMinutes);
        long blocksPerHour = 60 / intervalMinutes; // e.g., 60 / 30 = 2 blocks per hour

        long fullHours = totalBlocks / blocksPerHour;
        long remainderBlocks = totalBlocks % blocksPerHour;

        // 6. Calculate total charge (Player count is inherently handled by the exact JSON rate)
        double totalCharge = (fullHours * rate60Min) + (remainderBlocks * rateInterval);

        // 7. Prepare the detailed payload for the React UI receipt
        Map<String, Object> result = new HashMap<>();
        result.put("amount", totalCharge);

        // Capitalize weekday for the UI display (e.g., "mon" -> "Mon")
        String displayDay = weekday.substring(0, 1).toUpperCase() + weekday.substring(1);
        result.put("weekday", displayDay);
        result.put("billable_minutes", totalMinutes);
        result.put("player_count", playerCount);

        List<Map<String, Object>> appliedGrouped = new ArrayList<>();
        if (fullHours > 0) {
            appliedGrouped.add(Map.of(
                    "duration", 60,
                    "count", fullHours,
                    "price", rate60Min,
                    "subtotal", fullHours * rate60Min
            ));
        }
        if (remainderBlocks > 0) {
            appliedGrouped.add(Map.of(
                    "duration", intervalMinutes,
                    "count", remainderBlocks,
                    "price", rateInterval,
                    "subtotal", remainderBlocks * rateInterval
            ));
        }
        result.put("applied_grouped", appliedGrouped);

        return result;
    }

    // =====================================================================
    // HELPER: Safely parses your nested RateCard JSON structure
    // =====================================================================
    private double extractPrice(RateCard card, String weekday, int durationMinutes, int playerCount) {
        if (card.getWeekdayPrices() == null) return 0.0;

        // Extract the array for the specific day (e.g., "mon")
        Object dayRulesObj = card.getWeekdayPrices().get(weekday);
        if (!(dayRulesObj instanceof List)) return 0.0;

        List<?> dayRules = (List<?>) dayRulesObj;

        // Loop through the durations (30, 60) inside that day
        for (Object ruleObj : dayRules) {
            if (!(ruleObj instanceof Map)) continue;

            Map<?, ?> rule = (Map<?, ?>) ruleObj;

            // Check if this rule block matches the target duration (e.g., 30)
            Object durationObj = rule.get("duration");
            if (durationObj instanceof Number && ((Number) durationObj).intValue() == durationMinutes) {

                // We found the right duration. Now dive into "prices"
                Object pricesObj = rule.get("prices");
                if (pricesObj instanceof Map) {
                    Map<?, ?> prices = (Map<?, ?>) pricesObj;

                    // Look up the exact player count (the keys in your JSON are strings like "3")
                    Object priceObj = prices.get(String.valueOf(playerCount));
                    if (priceObj instanceof Number) {
                        return ((Number) priceObj).doubleValue();
                    }
                }
            }
        }
        return 0.0;
    }
    private double extractPriceForPlayerCount(RateCard rc, LocalDateTime start, int pc) {
        if (rc == null || rc.getWeekdayPrices() == null) return 0.0;

        int dayIndex = start.getDayOfWeek().getValue() - 1; // 0=Mon, 6=Sun
        String weekday = WEEKDAYS[dayIndex];

        Object raw = rc.getWeekdayPrices().get(weekday);
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            if (!list.isEmpty()) {
                Object firstSlab = list.get(0);
                if (firstSlab instanceof Map) {
                    Map<?, ?> slab = (Map<?, ?>) firstSlab;
                    if (slab.containsKey("prices")) {
                        Map<?, ?> prices = (Map<?, ?>) slab.get("prices");
                        if (prices.containsKey(String.valueOf(pc))) {
                            return Double.parseDouble(prices.get(String.valueOf(pc)).toString());
                        }
                    } else if (slab.containsKey("price")) {
                        return Double.parseDouble(slab.get("price").toString());
                    }
                }
            }
        }
        return 0.0;
    }

    public Map<String, Object> computeGameCharge(GameSession g) {
        if (g == null || g.getStartTime() == null || g.getEndTime() == null) {
            return Map.of("amount", 0.0);
        }

        // 1. Fetch the RateCard manually by ID since the Entity doesn't have the relationship mapped
        RateCard card = rateCardRepository.findById(g.getRateCardId())
                .orElse(null);

        if (card == null) return Map.of("amount", 0.0);

        // 2. Call your existing 4-parameter logic that returns the Map
        return computeGameCharge(
                card,
                g.getStartTime(),
                g.getEndTime(),
                g.getPlayerCount()
        );
    }
}