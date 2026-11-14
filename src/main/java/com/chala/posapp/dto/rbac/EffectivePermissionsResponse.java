package com.chala.posapp.dto.rbac;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class EffectivePermissionsResponse {
    Long userId;
    Long roleId;
    long rbacVersion;
    boolean owner;
    Set<String> permissionKeys;
    Set<String> ownerPermissionKeys;
}
