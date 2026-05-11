package com.progameflixx.cafectrl.controller;

import com.progameflixx.cafectrl.entity.InventoryItem;
import com.progameflixx.cafectrl.repository.InventoryRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"}, allowCredentials = "true", maxAge = 3600)
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyRole('CAFE_ADMIN', 'OPERATOR')")
public class InventoryController {

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
    public InventoryItem createInventory(@RequestBody InventoryItem item, Authentication auth) {
        item.setCafeId(getCafeId(auth));
        return inventoryRepository.save(item);
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