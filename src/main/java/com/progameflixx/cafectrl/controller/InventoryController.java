package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.dto.InventoryRequestDTO;
import com.progameflixx.cafectrl.entity.InventoryItem;
import com.progameflixx.cafectrl.entity.ItemRecipe;
import com.progameflixx.cafectrl.repository.InventoryRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "${frontend.url}", allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
public class InventoryController {
    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private UserRepository userRepository;

    private String getCafeId(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"))
                .getCafeId();
    }

    @GetMapping
    public List<InventoryItem> listInventory(Authentication auth) {
        return inventoryRepository.findByCafeId(getCafeId(auth));
    }

    @PostMapping
    public InventoryItem createInventory(@RequestBody InventoryRequestDTO request, Authentication auth) {
        try {
            logger.info("Inventory Item: {}", request);
            // 1. Create the base item
            InventoryItem newItem = new InventoryItem();
            newItem.setName(request.getName());
            newItem.setCategory(request.getCategory());
            newItem.setPrice(request.getPrice());
            newItem.setIsTrackable(request.getIsTrackable());
            newItem.setStock(request.getStock());
            newItem.setCafeId(getCafeId(auth));

            // 2. Handle the Recipe / Ingredients if it's untrackable
            if (Boolean.FALSE.equals(request.getIsTrackable()) && request.getIngredients() != null) {

                List<ItemRecipe> recipes = new ArrayList<>();

                for (InventoryRequestDTO.IngredientDTO ing : request.getIngredients()) {
                    // Fetch the actual raw material from the DB
                    InventoryItem rawMaterial = inventoryRepository.findById(ing.getRawMaterialId().toString())
                            .orElseThrow(() -> new RuntimeException("Raw material not found!"));

                    ItemRecipe recipe = new ItemRecipe();
                    recipe.setRawMaterial(rawMaterial);
                    recipe.setQuantityRequired(ing.getQuantityRequired());

                    // CRITICAL STEP: Tell the recipe who its parent is!
                    recipe.setMenuItem(newItem);

                    recipes.add(recipe);
                }

                // Attach the list of recipes to the burger
                newItem.setIngredients(recipes);
            }

            // 3. Save to database (CascadeType.ALL will automatically save the recipes too!)
            inventoryRepository.save(newItem);

            return newItem;
        } catch (Exception e) {
            logger.error("Error occurred", e);
            return null;
        }
    }

    @PutMapping("/{iid}")
    public ResponseEntity<?> updateInventory(@PathVariable String iid, @RequestBody InventoryItem updates, Authentication auth) {
        InventoryItem existing = inventoryRepository.findById(iid).orElse(null);
        if (existing == null || !existing.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }

        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
        if (updates.getPrice() != null) existing.setPrice(updates.getPrice());
        if (updates.getStock() != null) existing.setStock(updates.getStock());

        return ResponseEntity.ok(inventoryRepository.save(existing));
    }

    @DeleteMapping("/{iid}")
    public ResponseEntity<?> deleteInventory(@PathVariable String iid, Authentication auth) {
        InventoryItem existing = inventoryRepository.findById(iid).orElse(null);
        if (existing == null || !existing.getCafeId().equals(getCafeId(auth))) {
            return ResponseEntity.notFound().build();
        }
        inventoryRepository.delete(existing);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}