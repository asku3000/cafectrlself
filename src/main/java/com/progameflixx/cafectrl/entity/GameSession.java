package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "game_sessions")
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // THE LINK BACK TO THE CUSTOMER SESSION (Hidden from JSON)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_session_id")
    private CustomerSession customerSession;

    // THE LINK FORWARD TO ITEMS
    @OneToMany(mappedBy = "gameSession", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GameSessionItem> items = new ArrayList<>();

    @Column(name = "resource_id")
    @JsonProperty("resource_id")
    private String resourceId;

    @Column(name = "resource_name")
    @JsonProperty("resource_name")
    private String resourceName;

    @Column(name = "game_type_id")
    @JsonProperty("game_type_id")
    private String gameTypeId;

    @Column(name = "game_type_name")
    @JsonProperty("game_type_name")
    private String gameTypeName;

    @Column(name = "rate_card_id")
    @JsonProperty("rate_card_id")
    private String rateCardId;

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

    // Helper method to keep JPA synchronization perfect
    public void addItem(GameSessionItem item) {
        items.add(item);
        item.setGameSession(this);
    }

}