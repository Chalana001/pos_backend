package com.chala.posapp.entity.reorder;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;
@Entity @Table(name="reorder_plans") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReorderPlan extends TenantEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @Version private long version;
 private String name; @Column(name="branch_id") private Long branchId; @Enumerated(EnumType.STRING) private ReorderPlanStatus status;
 @Column(name="forecast_days") private int forecastDays; @Column(name="target_cover_days") private int targetCoverDays;
 @Column(name="created_by_user_id") private Long createdByUserId; @Column(name="created_by_username") private String createdByUsername;
 @Column(name="created_at") private LocalDateTime createdAt; @Column(name="submitted_at") private LocalDateTime submittedAt;
 @Column(name="approved_at") private LocalDateTime approvedAt; @Column(name="approved_by_username") private String approvedByUsername;
 @Column(name="rejected_at") private LocalDateTime rejectedAt; @Column(name="reject_reason") private String rejectReason;
 @Column(name="converted_at") private LocalDateTime convertedAt; @Column(name="converted_by_username") private String convertedByUsername;
 @Column(name="handoff_reference") private String handoffReference; private String notes;
 @OneToMany(mappedBy="plan",cascade=CascadeType.ALL,orphanRemoval=true) @Builder.Default private List<ReorderPlanLine> lines=new ArrayList<>();
 @PrePersist void persist(){if(createdAt==null)createdAt=LocalDateTime.now();if(status==null)status=ReorderPlanStatus.DRAFT;}
}
