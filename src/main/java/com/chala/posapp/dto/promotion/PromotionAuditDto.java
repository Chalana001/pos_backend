package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.PromotionAudit;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class PromotionAuditDto {
    private Long id;
    private String action;
    private Long userId;
    private String username;
    private String note;
    private LocalDateTime at;

    public static PromotionAuditDto from(PromotionAudit audit) {
        return PromotionAuditDto.builder()
                .id(audit.getId())
                .action(audit.getAction().name())
                .userId(audit.getUserId())
                .username(audit.getUsername())
                .note(audit.getNote())
                .at(audit.getAt())
                .build();
    }
}
