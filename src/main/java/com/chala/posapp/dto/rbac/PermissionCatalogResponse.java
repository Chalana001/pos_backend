package com.chala.posapp.dto.rbac;

import com.chala.posapp.rbac.PermissionDefinition;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PermissionCatalogResponse {
    String key;
    String label;
    String description;
    String category;
    String risk;
    boolean tenantGrantable;
    boolean individualAllowOverride;
    boolean protectedPermission;

    public static PermissionCatalogResponse from(PermissionDefinition value) {
        return PermissionCatalogResponse.builder().key(value.key()).label(value.label())
                .description(value.description()).category(value.category().name()).risk(value.risk().name())
                .tenantGrantable(value.tenantGrantable()).individualAllowOverride(value.individualAllowOverride())
                .protectedPermission(value.protectedPermission()).build();
    }
}
