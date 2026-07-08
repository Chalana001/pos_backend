package com.chala.posapp.config;

import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    @Value("${app.bootstrap.super-admin.username:}")
    private String superAdminUsername;
    @Value("${app.bootstrap.super-admin.password:}")
    private String superAdminPassword;

    @Override
    public void run(String... args) throws Exception {

        String superAdminTenant = "MASTER";

        if (superAdminUsername == null || superAdminUsername.isBlank()
                || superAdminPassword == null || superAdminPassword.isBlank()) {
            log.warn("Skipping bootstrap super admin seeding because credentials are not configured.");
            return;
        }

        TenantContext.runWith(superAdminTenant, () -> {
            Optional<User> existingAdmin = userRepository.findByUsername(superAdminUsername);

            if (existingAdmin.isEmpty()) {
                log.info("Creating Super Admin account...");

                User superAdmin = User.builder()
                        .username(superAdminUsername)
                        .passwordHash(passwordEncoder.encode(superAdminPassword))
                        .role(Role.SUPER_ADMIN)
                        .enabled(true)
                        .branchId(null)
                        .build();

                userRepository.save(superAdmin);
                log.info("✅ Super Admin account created successfully! Username: {}", superAdminUsername);
            } else {
                log.info("👍 Super Admin account already exists. Skipping creation.");
            }
        });
    }
}
