package com.chala.posapp.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponse {
    private Long userId;
    private String username;
    private String role;
    private Long branchId;
    private String shopName;
    private boolean hasOfflinePin;
}
