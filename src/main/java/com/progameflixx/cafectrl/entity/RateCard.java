package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "rate_cards") // Make sure this matches your MySQL table name
public class RateCard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "cafe_id")
    @JsonProperty("cafe_id")
    private String cafeId;

    private String name;

    // 1. FIXING THE GAME TYPE ID
    @Column(name = "game_type_id")
    @JsonProperty("game_type_id")
    private String gameTypeId;

    // 2. FIXING THE WEEKDAY PRICES JSON
    // This tells Hibernate to treat this Map as a raw JSON column in MySQL
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weekday_prices", columnDefinition = "json")
    @JsonProperty("weekday_prices")
    private Map<String, Object> weekdayPrices;

    // 3. DON'T FORGET THESE FIELDS!
    // They also use snake_case in React and will fail without @JsonProperty
    @Column(name = "billing_interval_minutes")
    @JsonProperty("billing_interval_minutes")
    private Integer billingIntervalMinutes;

    @Column(name = "grace_minutes")
    @JsonProperty("grace_minutes")
    private Integer graceMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "player_counts", columnDefinition = "json")
    @JsonProperty("player_counts")
    private List<Integer> playerCounts;


    private Instant createdAt = Instant.now();

    // ==========================================
    // Getters and Setters (Important for JSON parsing!)
    // ==========================================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCafeId() {
        return cafeId;
    }

    public void setCafeId(String cafeId) {
        this.cafeId = cafeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGameTypeId() {
        return gameTypeId;
    }

    public void setGameTypeId(String gameTypeId) {
        this.gameTypeId = gameTypeId;
    }

    public Map<String, Object> getWeekdayPrices() {
        return weekdayPrices;
    }

    public void setWeekdayPrices(Map<String, Object> weekdayPrices) {
        this.weekdayPrices = weekdayPrices;
    }

    public Integer getBillingIntervalMinutes() {
        return billingIntervalMinutes;
    }

    public void setBillingIntervalMinutes(Integer billingIntervalMinutes) {
        this.billingIntervalMinutes = billingIntervalMinutes;
    }

    public Integer getGraceMinutes() {
        return graceMinutes;
    }

    public void setGraceMinutes(Integer graceMinutes) {
        this.graceMinutes = graceMinutes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    // Add getters and setters!
    public List<Integer> getPlayerCounts() {
        return playerCounts;
    }

    public void setPlayerCounts(List<Integer> playerCounts) {
        this.playerCounts = playerCounts;
    }
}