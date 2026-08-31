package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(columnNames = {"username"})
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder

public class User extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled;

    private Long branchId;

    @Column(name = "offline_pin_hash", length = 255)
    private String offlinePinHash;

    /**
     * Tokens issued before this moment are rejected by {@code JwtAuthFilter}. Bumped on a
     * password change and by "sign out of all devices"; NULL means never invalidated.
     */
    @Column(name = "token_valid_from")
    private LocalDateTime tokenValidFrom;

    private LocalDateTime createdAt;

    private LocalDateTime offlinePinUpdatedAt;

    @PrePersist
    void onCreate(){
        createdAt = LocalDateTime.now();
    }
}
