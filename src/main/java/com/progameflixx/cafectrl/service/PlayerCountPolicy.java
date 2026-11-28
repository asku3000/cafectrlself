package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.entity.RateCard;
import com.progameflixx.cafectrl.entity.Resource;
import com.progameflixx.cafectrl.repository.RateCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * Decides the player count for a new game on a resource, from the counts its rate card is priced for.
 * <ul>
 *   <li>Player-priced resource (rate card offers more than one count, e.g. PS5 1-4): the count is mandatory and
 *       must be one of those counts. Nothing is ever filled in silently, because each count has its own rate.</li>
 *   <li>Single-count resource (pool table, steering sim: e.g. [1]): a missing count means that only count.</li>
 * </ul>
 * Used by every start path (new session, add game to a session). Existing games are never re-validated.
 */
@Component
public class PlayerCountPolicy {

    private static final Logger logger = LoggerFactory.getLogger(PlayerCountPolicy.class);

    private final RateCardRepository rateCards;

    public PlayerCountPolicy(RateCardRepository rateCards) {
        this.rateCards = rateCards;
    }

    /** Counts the resource's rate card is priced for (sorted, distinct, positive); [1] when the card lists none. */
    public List<Integer> allowedCounts(Resource resource) {
        RateCard card = resource.getRateCardId() == null ? null : rateCards.findById(resource.getRateCardId()).orElse(null);
        List<Integer> counts = card == null || card.getPlayerCounts() == null ? List.of()
                : card.getPlayerCounts().stream().filter(Objects::nonNull).filter(n -> n > 0).distinct().sorted().toList();
        return counts.isEmpty() ? List.of(1) : counts;
    }

    public boolean isPlayerPriced(Resource resource) {
        return allowedCounts(resource).size() > 1;
    }

    /** The player count to store for a new game, or 400 with a message staff can act on. */
    public int resolve(Resource resource, Object requested) {
        List<Integer> allowed = allowedCounts(resource);
        Integer count = parse(requested);
        if (requested != null && count == null) {
            throw bad("Number of players must be a whole number for " + resource.getName() + choices(allowed) + ".");
        }
        if (count == null) {
            if (allowed.size() == 1) return allowed.get(0);
            logger.warn("Start on {} rejected: no player count (rate card offers {})", resource.getName(), allowed);
            throw bad("Choose the number of players for " + resource.getName() + choices(allowed) + ".");
        }
        if (!allowed.contains(count)) {
            logger.warn("Start on {} rejected: {} player(s) not priced (rate card offers {})", resource.getName(), count, allowed);
            throw bad(resource.getName() + " has no price for " + count + " player" + (count == 1 ? "" : "s") + choices(allowed) + ".");
        }
        return count;
    }

    /** Accepts 2, 2.0 or "2"; anything else (blank, "two", 2.5) is null. */
    static Integer parse(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : null;
        }
        if (raw instanceof String s && s.trim().matches("-?\\d{1,9}")) return Integer.parseInt(s.trim());
        return null;
    }

    private static String choices(List<Integer> allowed) {
        if (allowed.size() == 1) return " (only " + allowed.get(0) + ")";
        boolean contiguous = allowed.get(allowed.size() - 1) - allowed.get(0) == allowed.size() - 1;
        return contiguous ? " (" + allowed.get(0) + "–" + allowed.get(allowed.size() - 1) + ")"
                : " (" + String.join(", ", allowed.stream().map(String::valueOf).toList()) + ")";
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
