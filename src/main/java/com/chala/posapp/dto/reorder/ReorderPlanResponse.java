package com.chala.posapp.dto.reorder;
import com.chala.posapp.entity.reorder.ReorderPlanStatus;
import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.List;
public record ReorderPlanResponse(Long id,String name,Long branchId,ReorderPlanStatus status,int forecastDays,int targetCoverDays,String createdByUsername,LocalDateTime createdAt,LocalDateTime submittedAt,LocalDateTime approvedAt,String approvedByUsername,String rejectReason,LocalDateTime convertedAt,String convertedByUsername,String handoffReference,String notes,List<Line> lines){
 public record Line(Long id,Long itemId,String itemName,String unit,Long supplierId,String supplierName,BigDecimal suggestedQty,BigDecimal approvedQty,long directDemandBase,long recipeDemandBase,long totalDemandBase,long suggestedQtyBase,long approvedQtyBase,BigDecimal unitCost,String confidence,boolean excluded,boolean manuallyEdited,String editNote){}
}
