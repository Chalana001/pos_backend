package com.chala.posapp.dto.rbac;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReauthenticationResponse {
    private String challenge;
    private long expiresInSeconds;
}
