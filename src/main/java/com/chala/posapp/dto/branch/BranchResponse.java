package com.chala.posapp.dto.branch;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BranchResponse {
    private Long id;
    private String code;
    private String name;
    private String address;
    private String phone;
    private String logo;
    private boolean active;
    private LocalDateTime createdAt;
}
