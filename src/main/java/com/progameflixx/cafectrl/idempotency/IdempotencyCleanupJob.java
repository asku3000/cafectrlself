package com.progameflixx.cafectrl.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily removal of idempotency keys older than 24 hours (default 03:30 IST). Deletes only from idempotency_keys,
 * in small batches.
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    static final int BATCH = 1000;

    private final IdempotencyStore store;
    private final boolean enabled;

    public IdempotencyCleanupJob(IdempotencyStore store, @Value("${idempotency.cleanup.enabled:true}") boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${idempotency.cleanup.cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        if (enabled) run();
    }

    public int run() {
        int total = 0;
        try {
            int n;
            do {
                n = store.deleteExpired(BATCH);
                total += n;
            } while (n == BATCH);
            if (total > 0) logger.info("Idempotency cleanup removed {} expired key(s)", total);
        } catch (RuntimeException e) {
            logger.error("Idempotency cleanup failed after {} key(s): {}", total, e.toString(), e);
        }
        return total;
    }
}
