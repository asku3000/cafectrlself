package com.progameflixx.cafectrl.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Data
public class InventoryRequestDTO {
    private String name;
    private String category;
    private Double price;
    private Integer stock;

    @JsonProperty("is_trackable")
    private Boolean isTrackable;

    private List<IngredientDTO> ingredients;

    // Getters and Setters for all of the above
    // ...
    @Data
    public static class IngredientDTO {
        @JsonProperty("raw_material_id")
        private UUID rawMaterialId;

        @JsonProperty("quantity_required")
        private Double quantityRequired;

        // Getters and Setters
        // ...
    }
}
