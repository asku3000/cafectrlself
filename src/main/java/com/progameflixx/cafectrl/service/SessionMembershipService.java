package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.dto.MembershipCardView;
import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.MembershipCard;
import com.progameflixx.cafectrl.repository.MembershipCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Links membership cards to customer sessions. Tagging is optional and changes nothing about how a session
 * is billed until checkout (Phase 4). One card may be tagged to several sessions at once.
 */
@Service
public class SessionMembershipService {

    private static final Logger logger = LoggerFactory.getLogger(SessionMembershipService.class);
    static final int LOOKUP_LIMIT = 20;

    private final MembershipCardRepository cardRepository;
    private final MembershipCardService cardService;

    public SessionMembershipService(MembershipCardRepository cardRepository, MembershipCardService cardService) {
        this.cardRepository = cardRepository;
        this.cardService = cardService;
    }

    /**
     * Validates that a card can be tagged to a session right now. Blocks cards that can't pay a bill
     * (expired, suspended, closed, or not started yet). Low or zero balance is allowed; the UI warns.
     */
    @Transactional(readOnly = true)
    public MembershipCard requireTaggable(String cafeId, String cardId) {
        MembershipCard card = cardRepository.findByIdAndCafeId(cardId, cafeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Membership card not found"));
        MembershipCard.Status status = card.effectiveStatus(cardService.today());
        String problem = switch (status) {
            case ACTIVE -> null;
            case EXPIRED -> "Card " + card.getCardNumber() + " expired on " + card.getExpiryDate();
            case SUSPENDED -> "Card " + card.getCardNumber() + " is suspended";
            case CLOSED -> "Card " + card.getCardNumber() + " is closed";
            case SCHEDULED -> "Card " + card.getCardNumber() + " starts on " + card.getStartDate() + " and can't be used yet";
        };
        if (problem != null) {
            logger.warn("Tag rejected for card {}: {}", cardId, problem);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }
        return card;
    }

    /** Fills the transient membership_card view on each session that has a card tagged (one query). */
    @Transactional(readOnly = true)
    public <T extends Collection<CustomerSession>> T attachCards(T sessions) {
        Set<String> ids = sessions.stream().map(CustomerSession::getMembershipCardId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return sessions;
        Map<String, MembershipCard> byId = cardRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MembershipCard::getId, c -> c));
        for (CustomerSession s : sessions) {
            MembershipCard card = s.getMembershipCardId() == null ? null : byId.get(s.getMembershipCardId());
            if (card != null && card.getCafeId().equals(s.getCafeId())) s.setMembershipCard(cardService.toView(card));
        }
        return sessions;
    }

    public CustomerSession attachCard(CustomerSession session) {
        attachCards(List.of(session));
        return session;
    }

    /** Card search for the session screens: name / phone / card number, most relevant (usable) first. */
    @Transactional(readOnly = true)
    public List<MembershipCardView> lookup(String cafeId, String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return List.of();
        return cardService.list(cafeId, q).stream()
                .filter(v -> !"CLOSED".equals(v.status()))
                .sorted(Comparator.comparing((MembershipCardView v) -> !v.usable()))
                .limit(LOOKUP_LIMIT)
                .toList();
    }
}
