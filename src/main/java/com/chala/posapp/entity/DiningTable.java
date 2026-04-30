package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "dining_tables",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "branch_id", "table_name"})
        },
        indexes = {
                @Index(name = "idx_tenant_branch_table", columnList = "tenant_id, branch_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiningTable extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "table_name", nullable = false, length = 120)
    private String tableName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DiningTableStatus status = DiningTableStatus.AVAILABLE;
}
