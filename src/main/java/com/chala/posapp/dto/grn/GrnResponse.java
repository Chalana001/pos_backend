package com.chala.posapp.dto.grn;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class GrnResponse {
    private Long id;
    private String grnNo;
    private String supplierName;
    private String branchName;
    private BigDecimal totalAmount;
    private LocalDateTime receivedAt;
    private String note;
    private List<GrnItemResponse> items;
}