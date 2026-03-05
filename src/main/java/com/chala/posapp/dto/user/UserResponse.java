package com.chala.posapp.dto.user;

import com.chala.posapp.entity.Role;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse {
    private Long id;
    private String username;
    private Role role;
    private boolean enabled;
    private Long branchId;
    private LocalDateTime createdAt;
}
