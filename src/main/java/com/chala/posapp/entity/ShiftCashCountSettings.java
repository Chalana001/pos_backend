package com.chala.posapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "shift_cash_count_settings")
@Getter
@Setter
@NoArgsConstructor
public class ShiftCashCountSettings extends TenantEntity {
    public static final long SINGLETON_ID = 1L;
    public static final String DEFAULT_DENOMINATIONS = "5000,2000,1000,500,100,50,20,10,5,2,1";

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "denominations_csv", nullable = false, length = 1000)
    private String denominationsCsv = DEFAULT_DENOMINATIONS;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
