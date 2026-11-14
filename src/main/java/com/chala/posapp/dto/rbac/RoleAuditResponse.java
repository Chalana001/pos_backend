package com.chala.posapp.dto.rbac;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class RoleAuditResponse {
    Long id;
    Long actorUserId;
    Long targetUserId;
    Long roleId;
    String action;
    String beforeState;
    String afterState;
    String reason;
    LocalDateTime createdAt;
}
