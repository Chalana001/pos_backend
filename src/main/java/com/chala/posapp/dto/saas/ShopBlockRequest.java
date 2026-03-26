package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShopBlockRequest {
    private boolean blocked;

    @Size(max = 255)
    private String reason;
}
