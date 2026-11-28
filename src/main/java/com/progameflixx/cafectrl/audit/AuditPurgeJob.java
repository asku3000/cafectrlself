package com.progameflixx.cafectrl.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Runs the audit purge for all cafes daily (default 03:00 IST, set with audit.purge.cron / AUDIT_PURGE_CRON),
 * using the app's existing scheduler. It runs inside the backend process, so it keeps working as long as the
 * backend is running (e.g. as a service or a Docker container with a restart policy). If the server was off at
 * the scheduled time, it catches up at the next startup.
 */
@Component
public class AuditPurgeJob {

    private static final Logger logger = LoggerFactory.getLogger(AuditPurgeJob.class);
    static final Duration CATCH_UP_AFTER = Duration.ofHours(26);

    private final AuditPurgeService purge;
    private final Clock clock;
    private final boolean enabled;

    public AuditPurgeJob(AuditPurgeService purge, Clock clock, @Value("${audit.purge.enabled:true}") boolean enabled) {
        this.purge = purge;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${audit.purge.cron:0 0 3 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        if (!enabled) return;
        run(AuditPurgeService.Trigger.SCHEDULED);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled) return;
        LocalDateTime last = purge.lastSuccessfulFullPurgeUtc();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (last == null || last.isBefore(now.minus(CATCH_UP_AFTER))) {
            logger.info("Audit purge catch-up at startup (last full purge: {})", last == null ? "never" : last + " UTC");
            run(AuditPurgeService.Trigger.STARTUP);
        }
    }

    private void run(AuditPurgeService.Trigger trigger) {
        try {
            purge.purge(null, trigger, null);
        } catch (RuntimeException e) {
            logger.error("Audit purge ({}) crashed: {}", trigger, e.toString(), e);
        }
    }
}
