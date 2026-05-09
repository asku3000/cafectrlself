package com.progameflixx.cafectrl.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "cafes")
public class Cafe {
    @Id
    private String id = UUID.randomUUID().toString();

    private String name;
    private String phone;
    private String address;
    private String ownerId;
    private boolean isSetupComplete;

    private Instant createdAt = Instant.now();
}
