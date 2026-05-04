package com.chala.posapp.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class OfflinePinRequest {
    private String currentPin;

    @NotBlank
    @Pattern(regexp = "\\d{4,8}", message = "PIN must be 4 to 8 digits")
    private String newPin;
}
