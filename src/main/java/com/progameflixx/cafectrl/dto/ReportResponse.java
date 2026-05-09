package com.progameflixx.cafectrl.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ReportResponse {
    private String date;
    private Double total;
    private Long count;
    private Map<String, Double> byMode;
    private Map<String, Double> byGameType;
    private Map<String, ItemStat> byItem;
    private List<Map<String, Object>> itemsTimeline;

    @Data
    public static class ItemStat {
        private Double revenue;
        private Integer qty;
        private String type;
    }
}
