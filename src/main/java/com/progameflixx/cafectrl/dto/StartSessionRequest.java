package com.progameflixx.cafectrl.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class StartSessionRequest {
    private String customerName;
    private String customerPhone = "";
    private String resourceId;
    private Instant startTime;
}
