package com.progameflixx.cafectrl.dto;

import lombok.Data;

@Data
public class AddItemRequest {
    private String type; // "inventory" or "accessory"
    private String refId;
    private Integer qty = 1;
}