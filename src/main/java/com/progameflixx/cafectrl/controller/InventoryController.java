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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

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
    public List<InventoryItem> listInventory(@RequestParam(value = "notInclude", required = false) String notIncludeCategories, Authentication auth) {
        logger.info("categories not to include {}", notIncludeCategories);
        if (notIncludeCategories == null || notIncludeCategories.isBlank()) {
            return getSortedBasedOnCategory(inventoryRepository.findByCafeId(getCafeId(auth)));
        } else {
            Collection<String> notInclude = List.of(notIncludeCategories.split(","));
            logger.info("notinclude list {}", notInclude);
            return getSortedBasedOnCategory(inventoryRepository.findByCafeIdAndCategoryNotIn(getCafeId(auth), notInclude));
        }
    }

    public List<InventoryItem> getSortedBasedOnCategory(List<InventoryItem> list){
        // Create a custom Comparator to sort by the last word of the name
        Comparator<InventoryItem> byLastWordCategory = Comparator.comparing(item -> {
            String name = item.getName();
            if (name == null || name.trim().isEmpty()) {
                return ""; // Safe fallback for empty names
            }

            // Split by one or more spaces
            String[] words = name.trim().split("\\s+");

            // Return the very last word, converted to lowercase for accurate sorting
            return words[words.length - 1].toLowerCase();
        });

        //  Sort the stream.
        // We also chain .thenComparing() so if two items have the same last word
        // (e.g., "Chicken Burger" and "Veg Burger"), they sort alphabetically!
        return list.stream()
                .sorted(byLastWordCategory.thenComparing(item ->
                        item.getName() == null ? "" : item.getName().toLowerCase()
                ))
                .collect(Collectors.toList());
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

        // 1. Map the basic fields
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
        if (updates.getPrice() != null) existing.setPrice(updates.getPrice());
        if (updates.getStock() != null) existing.setStock(updates.getStock());

        // 2. Map the boolean toggle! (Make sure your entity uses 'Boolean' wrapper, not 'boolean' primitive)
        if (updates.getIsTrackable() != null) {
            existing.setIsTrackable(updates.getIsTrackable());
        }

        // 3. The JPA-Safe way to update an ingredients list
        if (updates.getIngredients() != null) {
            existing.getIngredients().clear();

            for (ItemRecipe incomingIngredient : updates.getIngredients()) {
                // A. Link the recipe to the parent (Aloo Tikki Burger)
                incomingIngredient.setMenuItem(existing);

                // B. THE FIX: Fetch the actual Raw Material (Burger Bun) from the DB
                // using the transient ID we caught from the React JSON
                if (incomingIngredient.getRawMaterialId() != null) {
                    InventoryItem rawItem = inventoryRepository.findById(incomingIngredient.getRawMaterialId())
                            .orElseThrow(() -> new RuntimeException("Raw material not found!"));

                    // Attach the true database object to the recipe
                    incomingIngredient.setRawMaterial(rawItem);
                }

                existing.getIngredients().add(incomingIngredient);
            }
        }

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