package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "rate_cards")
public class RateCard {
    @Id
    private String id = UUID.randomUUID().toString();

    private String cafeId;
    private String name;
    private String gameTypeId;

    private Integer billingIntervalMinutes = 30;
    private Integer graceMinutes = 5;

    // We store your complex Python dicts/slabs as a JSON string in MySQL
    @Column(columnDefinition = "TEXT")
    private String weekdayPrices;

    private Instant createdAt = Instant.now();
}
