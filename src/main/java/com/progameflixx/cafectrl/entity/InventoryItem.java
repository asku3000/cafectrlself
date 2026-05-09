package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "inventory")
public class InventoryItem {
    @Id
    private String id = UUID.randomUUID().toString();

    private String cafeId;

    private String name;

    // snack | drink | other
    private String category = "snack";

    private Double price;

    private Integer stock = 0;

    private Instant createdAt = Instant.now();
}
