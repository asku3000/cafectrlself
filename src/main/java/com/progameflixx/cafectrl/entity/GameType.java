package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "game_types")
public class GameType {
    @Id
    private String id = UUID.randomUUID().toString();

    private String cafeId;
    private String name;
    private String icon;

    private Instant createdAt = Instant.now();
}
