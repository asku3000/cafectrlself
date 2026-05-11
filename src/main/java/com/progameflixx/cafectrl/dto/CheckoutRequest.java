package com.progameflixx.cafectrl.dto;

import com.progameflixx.cafectrl.entity.PaymentSplit;
import lombok.Data;
import java.util.List;
import java.util.Set;

@Data
public class CheckoutRequest {
    private Double adjustment;
    private List<PaymentSplit> payments;
    private String notes;
}