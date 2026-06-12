package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "inventory")
public class InventoryItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "cafe_id")
    @JsonProperty("cafe_id")
    private String cafeId;

    private String name;
    private String category; // "snack" | "drink" | "other"
    private Double price;
    private Integer stock;
    @JsonProperty("is_trackable")
    @Column(name = "is_trackable", nullable = false)
    private Boolean isTrackable;
    @OneToMany(mappedBy = "menuItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemRecipe> ingredients;

    private Instant createdAt = Instant.now();
}
