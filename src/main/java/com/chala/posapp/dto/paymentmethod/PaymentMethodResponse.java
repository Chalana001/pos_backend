package com.chala.posapp.dto.paymentmethod;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PaymentMethodResponse {
    private Long id;
    private String code;
    private String displayName;
    private String category;
    private String referencePolicy;
    private String referenceLabel;
    private boolean includeInExpectedCash;
    private boolean allowsCredit;
    private String iconKey;
    private boolean active;
    private boolean system;
    private int sortOrder;
    private LocalDateTime createdAt;
}
