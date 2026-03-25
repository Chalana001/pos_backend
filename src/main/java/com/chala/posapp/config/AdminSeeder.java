package com.chala.posapp.config;

import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {

        String superAdminTenant = "MASTER";
        String superAdminUsername = "chala_admin";

        TenantContext.setTenant(superAdminTenant);

        try {
            Optional<User> existingAdmin = userRepository.findByUsernameAndTenantId(superAdminUsername, superAdminTenant);

            if (existingAdmin.isEmpty()) {
                log.info("Creating Super Admin account...");

                User superAdmin = User.builder()
                        .username(superAdminUsername)
                        .passwordHash(passwordEncoder.encode("superSecretPassword123"))
                        .role(Role.SUPER_ADMIN)
                        .enabled(true)
                        .branchId(null)
                        .build();

                userRepository.save(superAdmin);
                log.info("✅ Super Admin account created successfully! Username: {}", superAdminUsername);
            } else {
                log.info("👍 Super Admin account already exists. Skipping creation.");
            }
        } finally {
            TenantContext.clear();
        }
    }
}