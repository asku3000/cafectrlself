package com.progameflixx.cafectrl.audit;

import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.User;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What happened, described by the caller. Who / where / when (actor, IP, device, request id, UTC time) are
 * filled in by {@link AuditService}, so call sites stay one short chain:
 * <pre>
 *   audit.record(AuditRecord.of(AuditActions.SESSION_STARTED)
 *       .entity(AuditActions.ENTITY_SESSION, session.getId())
 *       .description("Started session for Ravi on PS5-2"));
 * </pre>
 */
public final class AuditRecord {

    final String action;
    String entityType;
    String entityId;
    String cafeId;
    String description;
    Map<String, Object> changes;
    final Map<String, Object> details = new LinkedHashMap<>();
    AuditEvent.Outcome outcome = AuditEvent.Outcome.SUCCESS;
    String reason;

    // Actor overrides (default: the logged-in user of the current request)
    User actor;
    boolean system;
    String anonymousLabel;

    private AuditRecord(String action) {
        this.action = action;
    }

    public static AuditRecord of(String action) {
        return new AuditRecord(action);
    }

    public AuditRecord entity(String type, String id) {
        this.entityType = type;
        this.entityId = id;
        return this;
    }

    /** Only needed when there is no logged-in user to take the cafe from (system jobs, failed logins). */
    public AuditRecord cafe(String cafeId) {
        this.cafeId = cafeId;
        return this;
    }

    public AuditRecord description(String description) {
        this.description = description;
        return this;
    }

    /** Changed fields only, as produced by {@link AuditDiff#between}. */
    public AuditRecord changes(Map<String, Object> changes) {
        this.changes = changes;
        return this;
    }

    /** Shortcut for {@code changes(AuditDiff.between(before, after))}. */
    public AuditRecord diff(Map<String, ?> before, Map<String, ?> after) {
        this.changes = AuditDiff.between(before, after);
        return this;
    }

    public AuditRecord detail(String key, Object value) {
        if (value != null) this.details.put(key, value);
        return this;
    }

    public AuditRecord details(Map<String, ?> values) {
        if (values != null) values.forEach(this::detail);
        return this;
    }

    public AuditRecord reason(String reason) {
        this.reason = reason;
        return this;
    }

    /** Marks the action as attempted but rejected / failed; {@code message} is stored as the reason. */
    public AuditRecord failure(String message) {
        this.outcome = AuditEvent.Outcome.FAILURE;
        if (message != null) this.reason = message;
        return this;
    }

    /** Use this user as the actor instead of the request's logged-in user. */
    public AuditRecord actor(User user) {
        this.actor = user;
        return this;
    }

    /** Scheduled jobs and other non-user work: actor is recorded as SYSTEM. */
    public AuditRecord system() {
        this.system = true;
        return this;
    }

    /** No authenticated user (e.g. failed login): record who it claimed to be, e.g. the email typed. */
    public AuditRecord anonymous(String label) {
        this.anonymousLabel = label;
        return this;
    }

    public String action() {
        return action;
    }
}
