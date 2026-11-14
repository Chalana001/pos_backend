package com.chala.posapp.controller;

import com.chala.posapp.dto.rbac.ReauthenticationRequest;
import com.chala.posapp.dto.rbac.ReauthenticationResponse;
import com.chala.posapp.rbac.TenantReauthenticationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/tenant/reauthentication", "/api/tenant/reauthentication"})
@RequiredArgsConstructor
public class TenantReauthenticationController {
    private final TenantReauthenticationService service;

    @PostMapping("/owner-transfer")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReauthenticationResponse> ownerTransfer(@Valid @RequestBody ReauthenticationRequest request) {
        var challenge = service.issueOwnerTransferChallenge(request.getPassword());
        return ResponseEntity.ok(new ReauthenticationResponse(challenge.challenge(), challenge.expiresInSeconds()));
    }
}
