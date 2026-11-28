package com.progameflixx.cafectrl.audit;

import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Read side of the audit log: filtered, paged queries for the viewer and CSV export.
 * Filter times are given in IST (what staff see) and converted to UTC (how rows are stored).
 * Read-only: there is deliberately no update or delete here.
 */
@Service
public class AuditQueryService {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_EXPORT_ROWS = 50_000;
    /** Entries not tied to one cafe that every cafe admin may see. */
    static final List<String> SYSTEM_WIDE_ACTIONS = List.of(AuditActions.AUDIT_PURGE_RUN, AuditActions.AUDIT_PURGE_DRY_RUN);
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditEventRepository repository;
    private final EntityManager em;
    private final JsonMapper jsonMapper;

    public AuditQueryService(AuditEventRepository repository, EntityManager em, JsonMapper jsonMapper) {
        this.repository = repository;
        this.em = em;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Viewer filters. {@code cafeId} null = all cafes (super admin only). Times are IST wall-clock, inclusive
     * from / exclusive to. {@code q} searches description, entity id, actor name and reason.
     */
    public record Filter(String cafeId, LocalDateTime fromIst, LocalDateTime toIst, String actorUserId,
                         List<String> actions, String entityType, String entityId, String q, AuditEvent.Outcome outcome) {}

    public record PageResult(List<AuditEvent> items, int page, int size, long total, int totalPages) {}

    @Transactional(readOnly = true)
    public PageResult search(Filter f, int page, int size) {
        int s = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int p = Math.max(page, 0);
        Page<AuditEvent> result = repository.findAll(spec(f), PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "id")));
        return new PageResult(result.getContent(), p, s, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AuditEvent get(String cafeId, Long id) {
        AuditEvent e = repository.findById(id).orElse(null);
        boolean systemWide = e != null && e.getCafeId() == null && SYSTEM_WIDE_ACTIONS.contains(e.getAction());
        if (e == null || (cafeId != null && !cafeId.equals(e.getCafeId()) && !systemWide)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit entry not found");
        }
        return e;
    }

    /** Newest first, at most {@link #MAX_EXPORT_ROWS}; {@code truncated} tells the caller rows were left out. */
    public record ExportResult(List<AuditEvent> rows, long total, boolean truncated) {}

    @Transactional(readOnly = true)
    public ExportResult export(Filter f) {
        Page<AuditEvent> result = repository.findAll(spec(f), PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "id")));
        return new ExportResult(result.getContent(), result.getTotalElements(), result.getTotalElements() > MAX_EXPORT_ROWS);
    }

    /** Values for the viewer's drop-downs: actions and entity types seen, and every actor seen (incl. deleted staff). */
    @Transactional(readOnly = true)
    public Map<String, Object> facets(String cafeId) {
        String where = cafeId == null ? "" : " WHERE e.cafeId = :cafe";
        var actions = em.createQuery("SELECT DISTINCT e.action FROM AuditEvent e" + where + " ORDER BY e.action", String.class);
        var types = em.createQuery("SELECT DISTINCT e.entityType FROM AuditEvent e" + where
                + (where.isEmpty() ? " WHERE" : " AND") + " e.entityType IS NOT NULL ORDER BY e.entityType", String.class);
        var actors = em.createQuery("SELECT e.actorUserId, MAX(e.actorName), MAX(e.actorRole) FROM AuditEvent e" + where
                + (where.isEmpty() ? " WHERE" : " AND") + " e.actorUserId IS NOT NULL GROUP BY e.actorUserId", Object[].class);
        if (cafeId != null) {
            actions.setParameter("cafe", cafeId);
            types.setParameter("cafe", cafeId);
            actors.setParameter("cafe", cafeId);
        }
        List<Map<String, Object>> actorList = new ArrayList<>();
        for (Object[] row : actors.getResultList()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", row[0]);
            a.put("name", row[1]);
            a.put("role", row[2]);
            actorList.add(a);
        }
        actorList.sort(Comparator.comparing(a -> String.valueOf(a.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actions", actions.getResultList());
        out.put("entity_types", types.getResultList());
        out.put("actors", actorList);
        return out;
    }

    // =====================================================================
    // Filtering
    // =====================================================================

    static java.time.Instant istToInstant(LocalDateTime ist) {
        return ist == null ? null : ist.atZone(IST).toInstant();
    }

    static LocalDateTime toIst(java.time.Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, IST);
    }

    private static Specification<AuditEvent> spec(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.cafeId() != null) {
                // the cafe's own entries, plus system-wide purge runs (which cover every cafe)
                ps.add(cb.or(cb.equal(root.get("cafeId"), f.cafeId()),
                        cb.and(cb.isNull(root.get("cafeId")), root.get("action").in(SYSTEM_WIDE_ACTIONS))));
            }
            if (f.fromIst() != null) ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), istToInstant(f.fromIst())));
            if (f.toIst() != null) ps.add(cb.lessThan(root.get("occurredAt"), istToInstant(f.toIst())));
            if (notBlank(f.actorUserId())) ps.add(cb.equal(root.get("actorUserId"), f.actorUserId()));
            if (f.actions() != null && !f.actions().isEmpty()) ps.add(root.get("action").in(f.actions()));
            if (notBlank(f.entityType())) ps.add(cb.equal(root.get("entityType"), f.entityType()));
            if (notBlank(f.entityId())) ps.add(cb.equal(root.get("entityId"), f.entityId().trim()));
            if (f.outcome() != null) ps.add(cb.equal(root.get("outcome"), f.outcome()));
            if (notBlank(f.q())) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("description")), like, '\\'),
                        cb.like(cb.lower(root.get("entityId")), like, '\\'),
                        cb.like(cb.lower(root.get("actorName")), like, '\\'),
                        cb.like(cb.lower(root.get("reason")), like, '\\')));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // =====================================================================
    // CSV
    // =====================================================================

    static final List<String> CSV_HEADER = List.of("id", "time_ist", "actor", "actor_role", "actor_type", "action",
            "entity_type", "entity_id", "outcome", "description", "reason", "changes", "details",
            "ip_address", "user_agent", "request_id");

    public String toCsv(List<AuditEvent> rows) {
        StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel opens ₹ and names correctly
        sb.append(String.join(",", CSV_HEADER)).append("\r\n");
        for (AuditEvent e : rows) {
            List<String> cells = List.of(
                    String.valueOf(e.getId()),
                    e.getOccurredAt() == null ? "" : toIst(e.getOccurredAt()).format(CSV_TIME),
                    nz(e.getActorName()), nz(e.getActorRole()), e.getActorType() == null ? "" : e.getActorType().name(),
                    nz(e.getAction()), nz(e.getEntityType()), nz(e.getEntityId()),
                    e.getOutcome() == null ? "" : e.getOutcome().name(),
                    nz(e.getDescription()), nz(e.getReason()),
                    json(e.getChanges()), json(e.getDetails()),
                    nz(e.getIpAddress()), nz(e.getUserAgent()), nz(e.getRequestId()));
            StringJoiner line = new StringJoiner(",");
            for (String c : cells) line.add(csvCell(c));
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    /** RFC 4180 quoting, plus a leading ' on values a spreadsheet would run as a formula (=, +, -, @). */
    static String csvCell(String raw) {
        String v = raw == null ? "" : raw;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        boolean quote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private String json(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return "";
        try {
            return jsonMapper.writeValueAsString(m);
        } catch (RuntimeException ex) {
            return String.valueOf(m);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
