package com.progameflixx.cafectrl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records expiry for lapsed membership cards (status -> EXPIRED, remaining balance -> EXPIRY_FORFEIT entry).
 * Card status is also derived from dates on every read and every balance change, so correctness never
 * depends on this job; it only keeps stored status and the liability figures current.
 * Runs at 00:05 IST nightly and once at startup (covers nights the server was off).
 */
@Component
public class MembershipExpiryJob {

    private static final Logger logger = LoggerFactory.getLogger(MembershipExpiryJob.class);

    private final MembershipCardService cardService;
    private final boolean enabled;

    public MembershipExpiryJob(MembershipCardService cardService,
                               @Value("${membership.expiry-job.enabled:true}") boolean enabled) {
        this.cardService = cardService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${membership.expiry-job.cron:0 5 0 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        run("nightly");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run("startup");
    }

    private void run(String trigger) {
        if (!enabled) return;
        try {
            int n = cardService.expireDueCards();
            logger.info("Membership expiry job ({}) finished: {} card(s) expired", trigger, n);
        } catch (RuntimeException e) {
            logger.error("Membership expiry job ({}) failed: {}", trigger, e.toString(), e);
        }
    }
}
