package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "accessories")
public class Accessory {
    @Id
    private String id = UUID.randomUUID().toString();

    private String cafeId;
    private String name;
    private Double price;

    private Instant createdAt = Instant.now();
}
