package com.chala.posapp.entity;

import com.chala.posapp.tenant.TenantContext;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@Getter
@Setter
@MappedSuperclass

@FilterDef(
        name = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = String.class)
)

@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")

public abstract class TenantEntity {

    @Column(name = "tenant_id", nullable = false)
    protected String tenantId;

    @PrePersist
    public void setTenant() {
        if (tenantId == null) {
            String tenant = TenantContext.getTenant();
            if (tenant == null) {
                throw new RuntimeException("Tenant not found");
            }
            this.tenantId = tenant;
        }
    }
}