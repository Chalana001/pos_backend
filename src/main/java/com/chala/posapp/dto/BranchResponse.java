package com.chala.posapp.dto;

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
    private boolean active;
    private LocalDateTime createdAt;
}
