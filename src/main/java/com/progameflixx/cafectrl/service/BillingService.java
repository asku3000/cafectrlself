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

    private Map<String, Object> computeGameCharge(RateCard rc, LocalDateTime start, LocalDateTime end, int playerCount) {
        int interval = (rc != null && rc.getBillingIntervalMinutes() != null) ? rc.getBillingIntervalMinutes() : 30;
        int grace = (rc != null && rc.getGraceMinutes() != null) ? rc.getGraceMinutes() : 0;

        double totalMinutes = Math.max(0.0, Duration.between(start, end).toMinutes());
        int billableMinutes = Math.max(0, (int) Math.round(totalMinutes - grace));

        // Note: For effort level, I'm implementing the extraction of the basic rate per interval.
        // Full DP logic from python can be injected here if you have complex multi-hour slabs!
        double ratePerInterval = extractPriceForPlayerCount(rc, start, playerCount);

        int intervals = billableMinutes > 0 ? (int) Math.ceil((double) billableMinutes / interval) : 0;
        double totalCharge = intervals * ratePerInterval;

        Map<String, Object> res = new HashMap<>();
        res.put("minutes", Math.round(totalMinutes * 100.0) / 100.0);
        res.put("billable_minutes", billableMinutes);
        res.put("interval_minutes", interval);
        res.put("player_count", playerCount);
        res.put("amount", Math.round(totalCharge * 100.0) / 100.0);
        res.put("rate_per_interval", ratePerInterval);
        return res;
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
}