package com.progameflixx.cafectrl.dto;

import java.time.LocalDateTime;

public interface CustomerProfileView {
    String getName();
    String getPhone();
    Long getTotalVisits();
    LocalDateTime getLastVisited();
}
