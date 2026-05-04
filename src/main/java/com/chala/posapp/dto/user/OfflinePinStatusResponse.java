package com.chala.posapp.dto.user;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OfflinePinStatusResponse {
    private boolean hasOfflinePin;
}
