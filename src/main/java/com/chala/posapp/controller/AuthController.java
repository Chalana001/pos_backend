package com.chala.posapp.controller;

import com.chala.posapp.dto.AuthResponse;
import com.chala.posapp.dto.LoginRequest;
import com.chala.posapp.dto.RegisterRequest;
import com.chala.posapp.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // Only to create first admin
    @PostMapping("/register-admin")
    public ResponseEntity<?> registerAdmin(@Valid @RequestBody RegisterRequest request){
        authService.registerAdmin(request);
        return ResponseEntity.ok("Admin created");
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request){
        return ResponseEntity.ok(authService.login(request));
    }
}

