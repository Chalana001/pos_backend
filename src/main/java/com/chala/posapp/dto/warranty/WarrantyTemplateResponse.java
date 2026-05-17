package com.chala.posapp.dto.warranty;

import com.chala.posapp.entity.WarrantyPeriodUnit;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WarrantyTemplateResponse {
    private Long id;
    private String label;
    private int periodValue;
    private WarrantyPeriodUnit periodUnit;
    private boolean active;
    private LocalDateTime createdAt;
}
