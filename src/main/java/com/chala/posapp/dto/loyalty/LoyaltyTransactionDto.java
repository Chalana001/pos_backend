package com.chala.posapp.dto.loyalty;

import com.chala.posapp.entity.LoyaltyTransaction;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LoyaltyTransactionDto {
    private Long id;
    private Long orderId;
    private String type;
    private int points;
    private int balanceAfter;
    private String note;
    private LocalDateTime at;
    private boolean reversed;

    public static LoyaltyTransactionDto from(LoyaltyTransaction txn) {
        return LoyaltyTransactionDto.builder()
                .id(txn.getId())
                .orderId(txn.getOrderId())
                .type(txn.getType().name())
                .points(txn.getPoints())
                .balanceAfter(txn.getBalanceAfter())
                .note(txn.getNote())
                .at(txn.getAt())
                .reversed(txn.getReversedAt() != null)
                .build();
    }
}
