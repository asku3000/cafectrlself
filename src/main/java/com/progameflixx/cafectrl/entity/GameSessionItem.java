package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "game_session_items")
public class GameSessionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GameSession gameSession;

    private String type; // "inventory" | "accessory"

    @Column(name = "ref_id")
    @JsonProperty("ref_id")
    private String refId;

    private String name;

    @Column(name = "unit_price")
    @JsonProperty("unit_price")
    private Double unitPrice;

    private Integer qty = 1;
    private Double total;

    @Column(name = "added_at")
    @JsonProperty("added_at")
    private LocalDateTime addedAt;

}