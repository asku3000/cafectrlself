package com.progameflixx.cafectrl.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Create/update body for a membership plan. Money in paise; PERCENT bonus in basis points (1000 = 10%). */
@Data
public class MembershipPlanRequest {
    private String name;

    @JsonProperty("load_amount_paise")
    private Long loadAmountPaise;

    /** "FIXED" or "PERCENT" */
    @JsonProperty("bonus_type")
    private String bonusType;

    @JsonProperty("bonus_value")
    private Long bonusValue;

    @JsonProperty("validity_days")
    private Integer validityDays;

    @JsonProperty("is_active")
    private Boolean isActive;

    private String color;
    private String badge;

    @JsonProperty("sort_order")
    private Integer sortOrder;

    private String description;
}
