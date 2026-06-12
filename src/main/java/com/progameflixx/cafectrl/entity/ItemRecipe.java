package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.util.UUID;

@Data
@Entity
@Table(name = "item_recipes")
public class ItemRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // The finished product (e.g., Spicy Aloo Tikki Burger)
    @ManyToOne
    @JoinColumn(name = "menu_item_id", nullable = false)
    @JsonIgnore
    private InventoryItem menuItem;

    // The raw material to deduct (e.g., Aloo Tikki Patty)
    @ManyToOne
    @JoinColumn(name = "raw_material_id", nullable = false)
    @JsonIgnoreProperties("ingredients")
    private InventoryItem rawMaterial;

    // How many to deduct (e.g., 1 patty, or 1.5 scoops of coffee)
    @Column(name = "quantity_required", nullable = false)
    private Double quantityRequired = 1.0;

    // ... Getters and Setters ...
}
