package com.progameflixx.cafectrl.dto;

import com.progameflixx.cafectrl.entity.PaymentSplit;
import lombok.Data;

import java.util.List;

@Data
public class DebtClearRequest {
    private List<PaymentSplit> payments;
    private String notes;
}