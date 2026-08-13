package com.chala.posapp.dto.reorder;
import java.math.BigDecimal; import java.util.List;
public record PurchaseDraftResponse(Long supplierId,String supplierName,Long branchId,List<Item> items,BigDecimal estimatedTotal){
 public record Item(Long itemId,String itemName,String unit,BigDecimal qty,BigDecimal costPrice){}
}
