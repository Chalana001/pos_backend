package com.chala.posapp.entity.reorder;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
@Entity @Table(name="reorder_plan_lines") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReorderPlanLine extends TenantEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="plan_id") private ReorderPlan plan;
 @Column(name="item_id") private Long itemId; @Column(name="item_name") private String itemName; private String unit;
 @Enumerated(EnumType.STRING) @Column(name="item_type") private com.chala.posapp.entity.ItemType itemType;
 @Column(name="supplier_id") private Long supplierId; @Column(name="supplier_name") private String supplierName;
 @Column(name="suggested_qty") private BigDecimal suggestedQty; @Column(name="approved_qty") private BigDecimal approvedQty;
 @Column(name="direct_demand_base") private long directDemandBase; @Column(name="recipe_demand_base") private long recipeDemandBase;
 @Column(name="total_demand_base") private long totalDemandBase; @Column(name="suggested_qty_base") private long suggestedQtyBase;
 @Column(name="approved_qty_base") private long approvedQtyBase;
 @Column(name="unit_cost") private BigDecimal unitCost; private String confidence; private boolean excluded;
 @Column(name="manually_edited") private boolean manuallyEdited; @Column(name="edit_note") private String editNote;
}
