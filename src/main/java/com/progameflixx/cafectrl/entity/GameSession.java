package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "game_sessions")
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // THE LINK BACK TO THE CUSTOMER SESSION (Hidden from JSON to prevent infinite loop)
    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CustomerSession customerSession;

    // Changed to Set to prevent MultipleBagFetchException
    @JsonManagedReference
    @OneToMany(mappedBy = "gameSession", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<GameSessionItem> items = new LinkedHashSet<>();

    @Column(name = "resource_id")
    @JsonProperty("resource_id")
    private String resourceId;

    @Column(name = "resource_name")
    @JsonProperty("resource_name")
    private String resourceName;

    @Column(name = "game_type_id")
    @JsonProperty("game_type_id")
    private String gameTypeId;

    // FIX: Transient means it lives in memory for React, but Hibernate won't look for a DB column
    @Transient
    @JsonProperty("game_type_name")
    private String gameTypeName;

    @Column(name = "rate_card_id")
    @JsonProperty("rate_card_id")
    private String rateCardId;

    // FIX: Added relationship so BillingService can call getRateCard()
    // insertable=false, updatable=false means rateCardId still controls the database column
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rate_card_id", insertable = false, updatable = false)
    @JsonIgnore // We ignore this in JSON so we only send the rate_card_id to React
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private RateCard rateCard;

    @Column(name = "start_time")
    @JsonProperty("start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    @JsonProperty("end_time")
    private LocalDateTime endTime;

    private String status = "active"; // "active" | "soft_closed" | "billed"

    @Column(name = "player_count")
    @JsonProperty("player_count")
    private Integer playerCount = 1;

    // FIX: Added total so the React UI modal can display the calculated game bill
    @Transient
    private Double total;

    // Helper method to keep JPA synchronization perfect
    public void addItem(GameSessionItem item) {
        items.add(item);
        item.setGameSession(this);
    }
}