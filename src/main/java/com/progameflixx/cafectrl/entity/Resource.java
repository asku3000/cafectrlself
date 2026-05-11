package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "resources")
public class Resource {
    @Id
    private String id = UUID.randomUUID().toString();

    @JsonProperty("cafe_id")
    private String cafeId;

    private String name;

    @JsonProperty("game_type_id")
    private String gameTypeId;

    @JsonProperty("rate_card_id")
    private String rateCardId;

    @Column(name = "is_active")
    @JsonProperty("is_active")
    private Boolean isActive = true;

    @JsonProperty("created_at")
    private Instant createdAt = Instant.now();
}