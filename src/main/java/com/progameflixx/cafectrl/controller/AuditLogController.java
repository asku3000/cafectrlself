package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditPurgeService;
import com.progameflixx.cafectrl.audit.AuditQueryService;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.service.CurrentUserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Audit log viewer API. Admin only: a cafe admin sees their own cafe; a super admin sees every cafe (or one,
 * with cafe_id). Read-only by design: there are no update or delete endpoints. CSV exports are themselves audited.
 */
@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/audit-log")
@PreAuthorize("hasAnyRole('CAFE_ADMIN', 'SUPER_ADMIN')")
public class AuditLogController {

    private final AuditQueryService queries;
    private final AuditService audit;
    private final CurrentUserService currentUser;
    private final AuditPurgeService purge;
    private final String purgeCron;

    public AuditLogController(AuditQueryService queries, AuditService audit, CurrentUserService currentUser,
                              AuditPurgeService purge,
                              @org.springframework.beans.factory.annotation.Value("${audit.purge.cron:0 0 3 * * *}") String purgeCron) {
        this.queries = queries;
        this.audit = audit;
        this.currentUser = currentUser;
        this.purge = purge;
        this.purgeCron = purgeCron;
    }

    // =====================================================================
    // Purge (retention). A cafe admin's manual run / dry run covers only their own cafe;
    // the nightly job and a super admin's run (without cafe_id) cover all cafes.
    // =====================================================================

    @GetMapping("/purge")
    public Map<String, Object> purgeInfo(@RequestParam(name = "cafe_id", required = false) String cafeId, Authentication auth) {
        User user = currentUser.require(auth);
        String scope = scopeCafe(user, cafeId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("retention_days", purge.retentionDays());
        body.put("schedule_cron", purgeCron);
        body.put("schedule_zone", "Asia/Kolkata");
        body.put("cutoff_ist", purge.cutoffIst().toString());
        java.time.LocalDateTime last = purge.lastSuccessfulFullPurgeUtc();
        body.put("last_full_purge_at", last == null ? null : last.atOffset(java.time.ZoneOffset.UTC).toInstant().toString());
        body.put("scope", scope == null ? "ALL_CAFES" : "CAFE");
        return body;
    }

    @PostMapping("/purge")
    public ResponseEntity<Map<String, Object>> runPurge(@RequestParam(name = "dry_run", defaultValue = "true") boolean dryRun,
                                                        @RequestParam(name = "cafe_id", required = false) String cafeId,
                                                        Authentication auth) {
        User user = currentUser.require(auth);
        String scope = scopeCafe(user, cafeId);
        AuditPurgeService.Result r = dryRun
                ? purge.dryRun(scope, AuditPurgeService.Trigger.MANUAL, user)
                : purge.purge(scope, AuditPurgeService.Trigger.MANUAL, user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dry_run", r.dryRun());
        body.put("scope", r.cafeId() == null ? "ALL_CAFES" : "CAFE");
        body.put("cutoff_ist", r.cutoffIst().toString());
        body.put("deleted", r.deleted());
        body.put("would_delete", r.wouldDelete());
        body.put("batches", r.batches());
        body.put("duration_ms", r.durationMs());
        body.put("success", r.success());
        body.put("skipped", r.skipped());
        body.put("error", r.error());
        HttpStatus status = r.skipped() ? HttpStatus.CONFLICT : r.success() ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(name = "actor_user_id", required = false) String actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(name = "entity_type", required = false) String entityType,
            @RequestParam(name = "entity_id", required = false) String entityId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String outcome,
            @RequestParam(name = "cafe_id", required = false) String cafeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        AuditQueryService.Filter f = filter(auth, from, to, actorUserId, action, entityType, entityId, q, outcome, cafeId);
        AuditQueryService.PageResult r = queries.search(f, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", r.items());
        body.put("page", r.page());
        body.put("size", r.size());
        body.put("total", r.total());
        body.put("total_pages", r.totalPages());
        return body;
    }

    @GetMapping("/{id:\\d+}")
    public AuditEvent detail(@PathVariable Long id, Authentication auth) {
        return queries.get(scopeCafe(currentUser.require(auth), null), id);
    }

    @GetMapping("/facets")
    public Map<String, Object> facets(@RequestParam(name = "cafe_id", required = false) String cafeId, Authentication auth) {
        return queries.facets(scopeCafe(currentUser.require(auth), cafeId));
    }

    /** CSV of everything matching the filters (newest first, capped). The export is recorded in the audit log. */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(name = "actor_user_id", required = false) String actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(name = "entity_type", required = false) String entityType,
            @RequestParam(name = "entity_id", required = false) String entityId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String outcome,
            @RequestParam(name = "cafe_id", required = false) String cafeId,
            Authentication auth) {
        AuditQueryService.Filter f = filter(auth, from, to, actorUserId, action, entityType, entityId, q, outcome, cafeId);
        AuditQueryService.ExportResult r = queries.export(f);
        byte[] csv = queries.toCsv(r.rows()).getBytes(StandardCharsets.UTF_8);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("from_ist", from);
        filters.put("to_ist", to);
        filters.put("actor_user_id", actorUserId);
        filters.put("action", action);
        filters.put("entity_type", entityType);
        filters.put("entity_id", entityId);
        filters.put("q", q);
        filters.put("outcome", outcome);
        filters.values().removeIf(Objects::isNull);
        audit.record(AuditRecord.of(AuditActions.AUDIT_EXPORTED).entity(AuditActions.ENTITY_AUDIT_LOG, null)
                .description("Exported " + r.rows().size() + " audit entr" + (r.rows().size() == 1 ? "y" : "ies") + " to CSV"
                        + (r.truncated() ? " (first " + AuditQueryService.MAX_EXPORT_ROWS + " of " + r.total() + ")" : ""))
                .detail("filters", filters)
                .detail("rows", r.rows().size())
                .detail("truncated", r.truncated()));

        String name = "audit-log-" + LocalDateTime.now(AuditQueryService.IST).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header("X-Export-Truncated", String.valueOf(r.truncated()))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // =====================================================================

    private AuditQueryService.Filter filter(Authentication auth, LocalDateTime from, LocalDateTime to, String actorUserId,
                                            String action, String entityType, String entityId, String q, String outcome,
                                            String cafeId) {
        User user = currentUser.require(auth);
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must be after 'from'");
        }
        AuditEvent.Outcome oc = null;
        if (outcome != null && !outcome.isBlank()) {
            try {
                oc = AuditEvent.Outcome.valueOf(outcome.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outcome must be SUCCESS or FAILURE");
            }
        }
        List<String> actions = action == null || action.isBlank() ? List.of()
                : Arrays.stream(action.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new AuditQueryService.Filter(scopeCafe(user, cafeId), from, to, actorUserId, actions, entityType, entityId, q, oc);
    }

    /** Cafe admins are always limited to their own cafe; a super admin may pick one or see all (null). */
    private static String scopeCafe(User user, String requestedCafeId) {
        if ("SUPER_ADMIN".equalsIgnoreCase(user.getRole())) {
            return requestedCafeId == null || requestedCafeId.isBlank() ? null : requestedCafeId;
        }
        return user.getCafeId();
    }
}
