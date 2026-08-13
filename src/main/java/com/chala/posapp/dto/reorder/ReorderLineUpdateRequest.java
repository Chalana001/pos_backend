package com.chala.posapp.dto.reorder;
import java.math.BigDecimal;
public record ReorderLineUpdateRequest(BigDecimal approvedQty, Long supplierId, boolean excluded, String editNote) {}
