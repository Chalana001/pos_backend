package com.chala.posapp.controller;

import com.chala.posapp.dto.AuthResponse;
import com.chala.posapp.dto.LoginRequest;
import com.chala.posapp.dto.RegisterRequest;
import com.chala.posapp.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication endpoints - No authentication required")
public class AuthController {

    private final AuthService authService;

    // Only to create first admin
    @PostMapping("/register-admin")
    @Operation(summary = "Register Admin", description = "Create the first admin user (public endpoint)")
    @SecurityRequirements // This endpoint doesn't require authentication
    public ResponseEntity<?> registerAdmin(@Valid @RequestBody RegisterRequest request){
        authService.registerAdmin(request);
        return ResponseEntity.ok("Admin created");
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Authenticate user and receive JWT token")
    @SecurityRequirements // This endpoint doesn't require authentication
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request){
        return ResponseEntity.ok(authService.login(request));
    }
}

