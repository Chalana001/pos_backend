package com.chala.posapp.dto.branch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BranchCreateRequest {

    @NotBlank
    @Size(min = 2, max = 80)
    private String code;

    @NotBlank
    @Size(min = 2, max = 120)
    private String name;

    @Size(max = 255)
    private String address;

    @Size(max = 30)
    private String phone;

    private String logo;
}
