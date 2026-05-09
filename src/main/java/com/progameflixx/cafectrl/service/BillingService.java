package com.progameflixx.cafectrl.service;


import com.progameflixx.cafectrl.entity.RateCard;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class BillingService {

    // Helper class to represent a slab
    public static class Slab {
        public int duration;
        public double price;
        public Slab(int duration, double price) { this.duration = duration; this.price = price; }
    }

    public Map<String, Object> computeGameCharge(RateCard rateCard, Instant start, Instant end) {
        int interval = rateCard.getBillingIntervalMinutes() != null ? rateCard.getBillingIntervalMinutes() : 30;
        int grace = rateCard.getGraceMinutes() != null ? rateCard.getGraceMinutes() : 0;

        // In MySQL, we would store weekday prices as a serialized JSON string or a related table.
        // Assuming we parse it into a list of Slabs here:
        List<Slab> slabs = getSlabsForDay(rateCard, start);
        if (slabs.isEmpty()) {
            slabs.add(new Slab(interval, 0.0));
        }

        double totalMinutes = Math.max(0.0, Duration.between(start, end).toSeconds() / 60.0);
        int billableMinutes = (int) Math.max(0, Math.round(totalMinutes - grace));

        List<Slab> applied = new ArrayList<>();
        double totalCharge = 0.0;

        if (billableMinutes > 0) {
            int maxDur = slabs.stream().mapToInt(s -> s.duration).max().orElse(interval);
            int upper = billableMinutes + maxDur;

            double[] dp = new double[upper + 1];
            Arrays.fill(dp, Double.POSITIVE_INFINITY);
            dp[0] = 0.0;

            // To reconstruct the path
            int[] parent = new int[upper + 1];
            Slab[] parentSlab = new Slab[upper + 1];

            // Dynamic Programming: Minimum Coin Change variation
            for (int m = 0; m <= upper; m++) {
                if (dp[m] == Double.POSITIVE_INFINITY) continue;

                for (Slab s : slabs) {
                    int nm = Math.min(m + s.duration, upper);
                    double cost = dp[m] + s.price;
                    if (cost < dp[nm]) {
                        dp[nm] = cost;
                        parent[nm] = m;
                        parentSlab[nm] = s;
                    }
                }
            }

            // Find min cost covering at least 'billableMinutes'
            double best = Double.POSITIVE_INFINITY;
            int bestIdx = -1;
            for (int m = billableMinutes; m <= upper; m++) {
                if (dp[m] < best) {
                    best = dp[m];
                    bestIdx = m;
                }
            }

            if (best != Double.POSITIVE_INFINITY && bestIdx >= 0) {
                totalCharge = best;
                int cur = bestIdx;
                while (cur > 0 && parentSlab[cur] != null) {
                    applied.add(parentSlab[cur]);
                    cur = parent[cur];
                }
                Collections.reverse(applied);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("minutes", Math.round(totalMinutes * 100.0) / 100.0);
        result.put("billable_minutes", billableMinutes);
        result.put("amount", Math.round(totalCharge * 100.0) / 100.0);
        result.put("applied_slabs", applied);
        return result;
    }

    // Mock method to extract the correct slabs based on the day of the week
    private List<Slab> getSlabsForDay(RateCard rateCard, Instant start) {
        // Implementation depends on how you mapped the weekday_prices dict in MySQL
        return new ArrayList<>(List.of(new Slab(30, 100.0)));
    }
}