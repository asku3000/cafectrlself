package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "game_sessions")
public class GameSession {
    @Id
    private String id = UUID.randomUUID().toString();

    // The back-reference linking it to the parent CustomerSession
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_session_id")
    private CustomerSession customerSession;

    private String resourceId;
    private String resourceName;
    private String gameTypeId;
    private String rateCardId;
    private Instant startTime;
    private Instant endTime;
    private String status = "active";
    private String gameTypeName;
    // Links to the Items table
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "gameSession")
    private List<GameSessionItem> items = new ArrayList<>();
}