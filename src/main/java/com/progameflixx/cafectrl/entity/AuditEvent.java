package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * One audit / activity record. Append-only: Hibernate treats it as {@link Immutable} (updates are ignored) and
 * the app exposes no update or delete; only the purge job removes old rows.
 * {@code occurredAt} is an {@link Instant}, which Hibernate stores as UTC (the raw DB value is real UTC, whatever
 * the JVM or connection time zone); it is serialised with a trailing "Z" so the UI can show it in IST.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    public enum ActorType { USER, SYSTEM }
    public enum Outcome { SUCCESS, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored as UTC. */
    @Column(name = "occurred_at", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Instant occurredAt;

    @Column(name = "cafe_id")
    @JsonProperty("cafe_id")
    private String cafeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    @JsonProperty("actor_type")
    private ActorType actorType;

    @Column(name = "actor_user_id")
    @JsonProperty("actor_user_id")
    private String actorUserId;

    @Column(name = "actor_name")
    @JsonProperty("actor_name")
    private String actorName;

    @Column(name = "actor_role", length = 30)
    @JsonProperty("actor_role")
    private String actorRole;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", length = 40)
    @JsonProperty("entity_type")
    private String entityType;

    @Column(name = "entity_id", length = 64)
    @JsonProperty("entity_id")
    private String entityId;

    @Column(length = 1000)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> changes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Outcome outcome;

    @Column(length = 500)
    private String reason;

    @Column(name = "ip_address", length = 64)
    @JsonProperty("ip_address")
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    @JsonProperty("user_agent")
    private String userAgent;

    @Column(name = "request_id", length = 64)
    @JsonProperty("request_id")
    private String requestId;

    /** ISO-8601 instant, e.g. "2026-11-27T07:30:00.123Z". */
    @JsonProperty("occurred_at")
    public String getOccurredAtUtc() {
        return occurredAt == null ? null : occurredAt.toString();
    }
}
