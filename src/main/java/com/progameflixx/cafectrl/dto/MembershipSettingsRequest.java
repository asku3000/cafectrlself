package com.progameflixx.cafectrl.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Update body for membership settings. Null fields are left unchanged. */
@Data
public class MembershipSettingsRequest {
    /** "PAID_FIRST" or "BONUS_FIRST" */
    @JsonProperty("deduction_order")
    private String deductionOrder;

    @JsonProperty("low_balance_threshold_paise")
    private Long lowBalanceThresholdPaise;

    @JsonProperty("card_number_prefix")
    private String cardNumberPrefix;
}
