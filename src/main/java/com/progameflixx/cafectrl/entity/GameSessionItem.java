package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "game_session_items")
public class GameSessionItem {
    @Id
    private String id = UUID.randomUUID().toString();

    // The back-reference linking it to the parent GameSession
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_session_id")
    private GameSession gameSession;

    private String type;
    private String refId;
    private String name;
    private Double unitPrice;
    private Integer qty = 1;
    private Double total;
    private Instant addedAt = Instant.now();
}