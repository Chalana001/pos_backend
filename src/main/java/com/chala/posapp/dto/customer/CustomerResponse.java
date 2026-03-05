package com.chala.posapp.dto.customer;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CustomerResponse {
    private Long id;
    private String name;
    private String phone;
    private String address;
    private double dueAmount;
    private Double creditLimit;
    private boolean active;
    private LocalDateTime createdAt;
}
