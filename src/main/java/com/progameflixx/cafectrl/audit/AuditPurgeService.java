package com.progameflixx.cafectrl.audit;

import com.progameflixx.cafectrl.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;
import java.util.UUID;

/**
 * Deletes audit entries older than the retention period. It ONLY ever touches the {@code audit_events} table
 * (the table name is a constant below; bills, sessions, payments, cards and the membership ledger are never
 * referenced here).
 *
 * <ul>
 *   <li>Cutoff keeps whole IST days: with retention N, everything before 00:00 IST N days ago is removed.</li>
 *   <li>Deletes in small batches, each committed on its own, with a pause between batches, so the table is never
 *       locked for long and the app stays responsive.</li>
 *   <li>Guarded by a database lock row ({@code job_locks.audit_purge}): overlapping runs are skipped, and a crashed
 *       run's lock expires on its own. Running it twice is harmless.</li>
 *   <li>Every run (and dry run) writes a SYSTEM audit entry with the cutoff, count, duration and outcome.</li>
 * </ul>
 */
@Service
public class AuditPurgeService {

    private static final Logger logger = LoggerFactory.getLogger(AuditPurgeService.class);

    /** The only table this service deletes from. */
    static final String AUDIT_TABLE = "audit_events";
    static final String LOCK_NAME = "audit_purge";
    static final Duration LOCK_TTL = Duration.ofMinutes(30);

    public enum Trigger { SCHEDULED, STARTUP, MANUAL }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AuditService audit;
    private final Clock clock;
    private final int retentionDays;
    private final int batchSize;
    private final long batchPauseMs;

    public AuditPurgeService(JdbcTemplate jdbc, AuditService audit, Clock clock,
                             @Value("${audit.retention-days:5}") int retentionDays,
                             @Value("${audit.purge.batch-size:1000}") int batchSize,
                             @Value("${audit.purge.batch-pause-ms:200}") long batchPauseMs) {
        if (retentionDays < 1) throw new IllegalArgumentException("audit.retention-days must be at least 1");
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.audit = audit;
        this.clock = clock;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
        this.batchPauseMs = Math.max(0, batchPauseMs);
    }

    public int retentionDays() {
        return retentionDays;
    }

    /** Result of one run. {@code cafeId} null = all cafes. */
    public record Result(boolean dryRun, String cafeId, Trigger trigger, LocalDateTime cutoffIst, LocalDateTime cutoffUtc,
                         long deleted, long wouldDelete, int batches, long durationMs, boolean success,
                         boolean skipped, String error) {}

    /** Start of the oldest IST day that is kept. */
    public LocalDateTime cutoffIst() {
        return LocalDate.now(clock.withZone(AuditQueryService.IST)).minusDays(retentionDays).atStartOfDay();
    }

    static LocalDateTime toUtc(LocalDateTime ist) {
        return ist.atZone(AuditQueryService.IST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** How many entries a purge would delete right now (nothing is deleted). Also audited. */
    public Result dryRun(String cafeId, Trigger trigger, User requestedBy) {
        long started = System.nanoTime();
        LocalDateTime cutoffIst = cutoffIst();
        LocalDateTime cutoffUtc = toUtc(cutoffIst);
        long count = countOlderThan(cutoffUtc, cafeId);
        Result r = new Result(true, cafeId, trigger, cutoffIst, cutoffUtc, 0, count, 0, elapsedMs(started), true, false, null);
        record(r, requestedBy);
        return r;
    }

    /** Deletes entries older than the cutoff (for one cafe, or all when {@code cafeId} is null). */
    public Result purge(String cafeId, Trigger trigger, User requestedBy) {
        long started = System.nanoTime();
        LocalDateTime cutoffIst = cutoffIst();
        LocalDateTime cutoffUtc = toUtc(cutoffIst);
        String token = UUID.randomUUID().toString();

        if (!acquireLock(token)) {
            logger.warn("Audit purge ({}) skipped: another run holds the lock", trigger);
            Result r = new Result(false, cafeId, trigger, cutoffIst, cutoffUtc, 0, 0, 0, elapsedMs(started), false, true,
                    "Another purge is already running");
            record(r, requestedBy);
            return r;
        }

        long deleted = 0;
        int batches = 0;
        String error = null;
        try {
            while (true) {
                List<Long> ids = nextBatch(cutoffUtc, cafeId);
                if (ids.isEmpty()) break;
                int n = named.update("DELETE FROM " + AUDIT_TABLE + " WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
                deleted += n;
                batches++;
                extendLock(token);
                if (ids.size() < batchSize) break;
                pause();
            }
        } catch (RuntimeException e) {
            error = e.toString();
            logger.error("Audit purge ({}) failed after deleting {} entr(ies): {}", trigger, deleted, e.toString(), e);
        } finally {
            releaseLock(token);
        }

        Result r = new Result(false, cafeId, trigger, cutoffIst, cutoffUtc, deleted, 0, batches, elapsedMs(started),
                error == null, false, error);
        logger.info("Audit purge ({}) {}: deleted {} entr(ies) older than {} IST in {} batch(es), {} ms{}",
                trigger, r.success() ? "done" : "FAILED", deleted, cutoffIst, batches, r.durationMs(),
                cafeId == null ? "" : " for cafe " + cafeId);
        record(r, requestedBy);
        return r;
    }

    /** When the last successful all-cafe purge finished (UTC), from its own audit entry; null if never. */
    public LocalDateTime lastSuccessfulFullPurgeUtc() {
        List<LocalDateTime> rows = jdbc.query(
                "SELECT MAX(occurred_at) FROM " + AUDIT_TABLE + " WHERE action = ? AND outcome = 'SUCCESS' AND cafe_id IS NULL",
                (rs, i) -> rs.getObject(1, LocalDateTime.class),
                AuditActions.AUDIT_PURGE_RUN);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // =====================================================================

    private long countOlderThan(LocalDateTime cutoffUtc, String cafeId) {
        Long n = cafeId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM " + AUDIT_TABLE + " WHERE occurred_at < ?", Long.class, cutoffUtc)
                : jdbc.queryForObject("SELECT COUNT(*) FROM " + AUDIT_TABLE + " WHERE occurred_at < ? AND cafe_id = ?", Long.class, cutoffUtc, cafeId);
        return n == null ? 0 : n;
    }

    private List<Long> nextBatch(LocalDateTime cutoffUtc, String cafeId) {
        return cafeId == null
                ? jdbc.queryForList("SELECT id FROM " + AUDIT_TABLE + " WHERE occurred_at < ? ORDER BY id LIMIT " + batchSize,
                Long.class, cutoffUtc)
                : jdbc.queryForList("SELECT id FROM " + AUDIT_TABLE + " WHERE occurred_at < ? AND cafe_id = ? ORDER BY id LIMIT " + batchSize,
                Long.class, cutoffUtc, cafeId);
    }

    private boolean acquireLock(String token) {
        LocalDateTime now = nowUtc();
        int n = jdbc.update("UPDATE job_locks SET locked_until = ?, locked_by = ?, locked_at = ? "
                        + "WHERE name = ? AND (locked_until IS NULL OR locked_until < ?)",
                now.plus(LOCK_TTL), token, now, LOCK_NAME, now);
        return n == 1;
    }

    private void extendLock(String token) {
        jdbc.update("UPDATE job_locks SET locked_until = ? WHERE name = ? AND locked_by = ?",
                nowUtc().plus(LOCK_TTL), LOCK_NAME, token);
    }

    private void releaseLock(String token) {
        try {
            jdbc.update("UPDATE job_locks SET locked_until = NULL, locked_by = NULL WHERE name = ? AND locked_by = ?", LOCK_NAME, token);
        } catch (RuntimeException e) {
            logger.error("Could not release audit purge lock (it expires on its own in {}): {}", LOCK_TTL, e.toString());
        }
    }

    private void pause() {
        if (batchPauseMs <= 0) return;
        try {
            Thread.sleep(batchPauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Audit purge interrupted", e);
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** SYSTEM entry describing the run. Written after the deletes, so it is never removed by its own run. */
    private void record(Result r, User requestedBy) {
        String scope = r.cafeId() == null ? "all cafes" : "this cafe";
        String cutoff = AuditFormat.dateTime(r.cutoffIst()) + " IST";
        String desc;
        if (r.dryRun()) desc = "Dry run: " + r.wouldDelete() + " audit entr" + (r.wouldDelete() == 1 ? "y" : "ies")
                + " older than " + cutoff + " would be deleted (" + scope + ")";
        else if (r.skipped()) desc = "Audit purge skipped: another purge was already running";
        else if (r.success()) desc = "Deleted " + r.deleted() + " audit entr" + (r.deleted() == 1 ? "y" : "ies")
                + " older than " + cutoff + " (" + scope + ", retention " + retentionDays + " days)";
        else desc = "Audit purge failed after deleting " + r.deleted() + " entr" + (r.deleted() == 1 ? "y" : "ies");

        AuditRecord rec = AuditRecord.of(r.dryRun() ? AuditActions.AUDIT_PURGE_DRY_RUN : AuditActions.AUDIT_PURGE_RUN)
                .system().cafe(r.cafeId()).entity(AuditActions.ENTITY_AUDIT_LOG, null)
                .description(desc)
                .detail("trigger", r.trigger().name())
                .detail("requested_by", requestedBy == null ? null : requestedBy.getName())
                .detail("requested_by_user_id", requestedBy == null ? null : requestedBy.getId())
                .detail("scope", r.cafeId() == null ? "ALL_CAFES" : "CAFE")
                .detail("retention_days", retentionDays)
                .detail("cutoff_ist", r.cutoffIst().toString())
                .detail("cutoff_utc", r.cutoffUtc().toString())
                .detail("deleted", r.deleted())
                .detail("would_delete", r.dryRun() ? r.wouldDelete() : null)
                .detail("batches", r.batches())
                .detail("duration_ms", r.durationMs());
        if (!r.success()) {
            audit.recordFailure(rec, r.error());
        } else {
            audit.record(rec);
        }
    }
}
