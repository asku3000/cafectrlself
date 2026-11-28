package com.progameflixx.cafectrl.audit;

import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.logging.LogContext;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one place audit entries are written. Two ways to write:
 *
 * <ul>
 *   <li>{@link #recordInTransaction} for money actions (billing, payments, card balance changes): the entry is
 *       inserted in the caller's transaction, so the change and its audit record commit or roll back together.
 *       Must be called inside an active transaction.</li>
 *   <li>{@link #record} for everything else: best-effort. Written after the caller's transaction commits (only
 *       committed changes are logged), in its own transaction; any error is logged and swallowed so auditing
 *       can never break or slow down the main action. {@link #recordFailure} writes a FAILURE entry
 *       immediately in its own transaction (the caller's work is being rolled back).</li>
 * </ul>
 *
 * Actor, cafe, IP, device and request id come from the current request; time is stored in UTC; every
 * changes/details map is converted to JSON values and passed through {@link AuditSanitizer} (secrets masked).
 */
@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);
    private static final String REQUEST_ACTOR_CACHE = AuditService.class.getName() + ".actor";
    static final String SYSTEM_NAME = "SYSTEM";

    private final AuditEventRepository repository;
    private final UserRepository userRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;

    public AuditService(AuditEventRepository repository, UserRepository userRepository, JsonMapper jsonMapper,
                        Clock clock, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(txManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // =====================================================================
    // Writing
    // =====================================================================

    /** Money actions: same transaction as the change. Throws if there is no active transaction. */
    public AuditEvent recordInTransaction(AuditRecord record) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("recordInTransaction(" + record.action() + ") needs an active transaction");
        }
        return repository.save(build(record));
    }

    /** Non-critical actions: best effort, after the surrounding transaction (if any) commits. */
    public void record(AuditRecord record) {
        final AuditEvent event;
        try {
            event = build(record); // capture actor / request info now, on the request thread
        } catch (RuntimeException e) {
            logger.error("Audit '{}' could not be prepared: {}", record.action(), e.toString(), e);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeOwnTransaction(event);
                }
            });
        } else {
            writeOwnTransaction(event);
        }
    }

    /** A rejected / failed attempt: written now, in its own transaction, even if the caller rolls back. */
    public void recordFailure(AuditRecord record, String message) {
        record.failure(message);
        try {
            writeOwnTransaction(build(record));
        } catch (RuntimeException e) {
            logger.error("Audit failure entry '{}' could not be prepared: {}", record.action(), e.toString(), e);
        }
    }

    private void writeOwnTransaction(AuditEvent event) {
        try {
            ownTransaction.executeWithoutResult(tx -> repository.save(event));
        } catch (RuntimeException e) {
            logger.error("Audit '{}' on {} {} was NOT saved: {}", event.getAction(), event.getEntityType(),
                    event.getEntityId(), e.toString(), e);
        }
    }

    /**
     * Bridge for the original {@code log(auth, action, target, meta)} calls; writes to the new audit table.
     * Callers are being moved to specific actions with {@link #record} / {@link #recordInTransaction}.
     */
    public void log(Authentication auth, String action, String target, Map<String, Object> meta) {
        record(AuditRecord.of(action).entity(legacyEntityType(action), target).details(meta));
    }

    static String legacyEntityType(String action) {
        if (action == null) return null;
        if (action.startsWith("MEMBERSHIP_CARD")) return AuditActions.ENTITY_MEMBERSHIP_CARD;
        if (action.startsWith("MEMBERSHIP_PLAN")) return AuditActions.ENTITY_MEMBERSHIP_PLAN;
        if (action.startsWith("MEMBERSHIP_SETTINGS")) return AuditActions.ENTITY_MEMBERSHIP_SETTINGS;
        if (action.startsWith("SESSION")) return AuditActions.ENTITY_SESSION;
        return null;
    }

    // =====================================================================
    // Building
    // =====================================================================

    AuditEvent build(AuditRecord r) {
        AuditEvent e = new AuditEvent();
        e.setOccurredAt(clock.instant());
        e.setAction(r.action);
        e.setEntityType(r.entityType);
        e.setEntityId(truncate(r.entityId, 64));
        e.setDescription(truncate(r.description, 1000));
        e.setOutcome(r.outcome);
        e.setReason(truncate(r.reason, 500));
        e.setChanges(r.changes == null || r.changes.isEmpty() ? null : AuditSanitizer.sanitize(toJsonLike(r.changes)));
        e.setDetails(r.details.isEmpty() ? null : AuditSanitizer.sanitize(toJsonLike(r.details)));

        if (r.system) {
            e.setActorType(AuditEvent.ActorType.SYSTEM);
            e.setActorName(SYSTEM_NAME);
            e.setActorRole(SYSTEM_NAME);
        } else {
            e.setActorType(AuditEvent.ActorType.USER);
            User actor = r.actor != null ? r.actor : currentUser().orElse(null);
            if (actor != null) {
                e.setActorUserId(actor.getId());
                e.setActorName(actor.getName() != null && !actor.getName().isBlank() ? actor.getName() : actor.getEmail());
                e.setActorRole(actor.getRole());
                if (r.cafeId == null) e.setCafeId(actor.getCafeId());
            } else {
                e.setActorName(truncate(r.anonymousLabel != null ? r.anonymousLabel : "anonymous", 255));
            }
        }
        if (r.cafeId != null) e.setCafeId(r.cafeId);

        HttpServletRequest req = currentRequest();
        if (req != null) {
            e.setIpAddress(truncate(clientIp(req), 64));
            e.setUserAgent(truncate(req.getHeader("User-Agent"), 255));
        }
        e.setRequestId(truncate(MDC.get(LogContext.REQUEST_ID), 64));
        return e;
    }

    /** Turns any objects (entities, DTOs, dates) into plain JSON values via the app's Jackson mapper. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toJsonLike(Map<String, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> en : in.entrySet()) {
            Object v = en.getValue();
            if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
                out.put(en.getKey(), v);
                continue;
            }
            try {
                out.put(en.getKey(), jsonMapper.convertValue(v, Object.class));
            } catch (RuntimeException ex) {
                out.put(en.getKey(), String.valueOf(v));
            }
        }
        return (Map<String, Object>) (Map<?, ?>) out;
    }

    // =====================================================================
    // Request context
    // =====================================================================

    /** The logged-in user of the current request (looked up once per request), if any. */
    private Optional<User> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return Optional.empty();
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(REQUEST_ACTOR_CACHE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof User u && auth.getName().equals(u.getEmail())) return Optional.of(u);
        }
        Optional<User> user = userRepository.findByEmail(auth.getName());
        if (attrs != null) user.ifPresent(u -> attrs.setAttribute(REQUEST_ACTOR_CACHE, u, RequestAttributes.SCOPE_REQUEST));
        return user;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    /** First address in X-Forwarded-For (when behind a proxy), else the socket address. */
    static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String first = fwd.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        return req.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
