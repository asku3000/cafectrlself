package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "resources")
public class Resource {
    @Id
    private String id = UUID.randomUUID().toString();

    private String cafeId;
    private String name;
    private String gameTypeId;
    private String rateCardId;

    private boolean isActive = true;

    private Instant createdAt = Instant.now();
}