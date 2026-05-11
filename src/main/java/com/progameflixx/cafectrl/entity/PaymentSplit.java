package com.progameflixx.cafectrl.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSplit {
    private String mode;   // "cash", "upi", "card"
    private Double amount;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CustomerSession customerSession;
}