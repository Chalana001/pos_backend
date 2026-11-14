package com.chala.posapp.dto.rbac;

import com.chala.posapp.rbac.TenantOwnershipState;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TenantOwnershipResponse {
    Long ownerUserId;
    String status;
    long version;

    public static TenantOwnershipResponse from(TenantOwnershipState state) {
        return TenantOwnershipResponse.builder()
                .ownerUserId(state.ownerUserId())
                .status(state.status().name())
                .version(state.version())
                .build();
    }
}
