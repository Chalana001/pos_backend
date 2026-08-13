package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExceptionCenterResponse {
    private long totalExceptions;
    private long criticalExceptions;
    private List<ExceptionItem> items;
    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExceptionItem {
        private String type;
        private String severity;
        private String title;
        private String detail;
        private double amount;
        private String path;
    }
}
