package com.progameflixx.cafectrl.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSplit {
    private String mode;   // "cash", "upi", "card"
    private Double amount;
}