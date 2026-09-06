package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.CustomerSegment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A customer segment and, on the way out, how many people are currently in it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSegmentDto {
    private Long id;
    private String name;
    private String description;
    private BigDecimal minTotalSpend;
    private Integer minOrderCount;
    private BigDecimal minAvgOrderValue;
    private Integer purchasedWithinDays;
    private Integer inactiveForDays;
    @Builder.Default
    private boolean active = true;
    private int memberCount;
    private LocalDateTime lastEvaluatedAt;

    public static CustomerSegmentDto from(CustomerSegment segment) {
        return CustomerSegmentDto.builder()
                .id(segment.getId())
                .name(segment.getName())
                .description(segment.getDescription())
                .minTotalSpend(segment.getMinTotalSpend())
                .minOrderCount(segment.getMinOrderCount())
                .minAvgOrderValue(segment.getMinAvgOrderValue())
                .purchasedWithinDays(segment.getPurchasedWithinDays())
                .inactiveForDays(segment.getInactiveForDays())
                .active(segment.isActive())
                .memberCount(segment.getMemberCount())
                .lastEvaluatedAt(segment.getLastEvaluatedAt())
                .build();
    }
}
