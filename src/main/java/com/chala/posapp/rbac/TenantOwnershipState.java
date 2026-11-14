package com.chala.posapp.rbac;

public record TenantOwnershipState(
        Long id,
        Long ownerUserId,
        TenantOwnershipStatus status,
        long version
) {
    public boolean resolved() {
        return status == TenantOwnershipStatus.RESOLVED && ownerUserId != null;
    }
}
