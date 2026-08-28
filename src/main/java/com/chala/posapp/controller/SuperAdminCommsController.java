package com.chala.posapp.controller;

import com.chala.posapp.dto.saas.LifecycleDtos.AnnouncementRequest;
import com.chala.posapp.entity.Announcement;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.service.AnnouncementService;
import com.chala.posapp.service.PlatformSettingsService;
import com.chala.posapp.service.SuperAdminAuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Announcements, per-shop maintenance mode, and platform settings.
 */
@RestController
@RequestMapping("/api/saas/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminCommsController {

    private final AnnouncementService announcementService;
    private final PlatformSettingsService settingsService;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SuperAdminAuditService auditService;

    // ------------------------------------------------------- announcements

    @GetMapping("/announcements")
    public ResponseEntity<List<AnnouncementService.AnnouncementSummary>> announcements() {
        return ResponseEntity.ok(announcementService.list());
    }

    @GetMapping("/announcements/{id}")
    public ResponseEntity<Announcement> announcement(@PathVariable Long id) {
        return ResponseEntity.ok(announcementService.get(id));
    }

    @PostMapping("/announcements")
    public ResponseEntity<Announcement> createAnnouncement(@Valid @RequestBody AnnouncementRequest request) {
        return ResponseEntity.ok(announcementService.create(request));
    }

    @PutMapping("/announcements/{id}")
    public ResponseEntity<Announcement> updateAnnouncement(@PathVariable Long id,
                                                           @Valid @RequestBody AnnouncementRequest request) {
        return ResponseEntity.ok(announcementService.update(id, request));
    }

    @DeleteMapping("/announcements/{id}")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable Long id) {
        announcementService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Preview exactly what a given shop would see — the way to check targeting before publishing. */
    @GetMapping("/shops/{tenantId}/announcements")
    public ResponseEntity<List<AnnouncementService.ShopAnnouncement>> announcementsForShop(
            @PathVariable String tenantId) {
        return ResponseEntity.ok(announcementService.forTenant(tenantId));
    }

    // --------------------------------------------------- maintenance mode

    @PatchMapping("/shops/{tenantId}/maintenance")
    @Transactional
    public ResponseEntity<Map<String, Object>> setMaintenance(@PathVariable String tenantId,
                                                              @RequestBody Map<String, Object> body) {
        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + tenantId));

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String message = body.get("message") == null ? null : String.valueOf(body.get("message"));

        subscription.setMaintenanceMode(enabled);
        subscription.setMaintenanceMessage(enabled ? message : null);
        tenantSubscriptionRepository.save(subscription);

        auditService.record(currentActor(),
                enabled ? "MAINTENANCE_ENABLED" : "MAINTENANCE_DISABLED",
                SuperAdminAuditService.TARGET_SHOP, tenantId,
                subscription.getShopName() + (enabled
                        ? " put into maintenance mode" + (message != null ? " — " + message : "")
                        : " taken out of maintenance mode"));

        return ResponseEntity.ok(Map.of(
                "tenantId", tenantId,
                "maintenanceMode", enabled,
                "maintenanceMessage", message == null ? "" : message));
    }

    // ------------------------------------------------------------ settings

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> settings() {
        return ResponseEntity.ok(Map.of(
                "values", settingsService.all(),
                "catalog", settingsService.catalog()));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, String>> updateSettings(@RequestBody Map<String, String> values) {
        return ResponseEntity.ok(settingsService.update(values));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}
