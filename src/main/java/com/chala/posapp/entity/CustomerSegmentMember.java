package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One customer's membership of a segment, as of the last recompute. */
@Entity
@Table(
        name = "customer_segment_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_segment_member", columnNames = {"segment_id", "customer_id"}),
        indexes = @Index(name = "idx_segment_member_customer", columnList = "customer_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerSegmentMember extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "segment_id", nullable = false)
    private Long segmentId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;
}
