package com.chala.posapp.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class GrnDetailDto {
    private Long grnId;
    private String grnNo;
    private String branchName;
    private BigDecimal totalAmount;
    private List<GrnItemResponse> items;
}