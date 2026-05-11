package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
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

    private Instant createdAt = Instant.now();
}
