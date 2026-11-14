package com.chala.posapp.dto.rbac;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class RoleResponse {
    Long id;
    String roleKey;
    String displayName;
    String roleType;
    boolean system;
    boolean protectedRole;
    boolean active;
    String legacyCompatibility;
    Long templateRoleId;
    long version;
    List<String> permissionKeys;
}
