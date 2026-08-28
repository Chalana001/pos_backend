package com.chala.posapp.controller;

import com.chala.posapp.dto.saas.PublicPlanResponse;
import com.chala.posapp.dto.saas.SupportInfoResponse;
import com.chala.posapp.dto.saas.module.MyModulesResponse;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.service.AnnouncementService;
import com.chala.posapp.service.PlatformSettingsService;
import com.chala.posapp.service.SubscriptionService;
import com.chala.posapp.service.SuperAdminModuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/saas")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SuperAdminModuleService moduleService;
    private final AnnouncementService announcementService;
    private final PlatformSettingsService settingsService;

    @GetMapping("/plans")
    public ResponseEntity<List<PublicPlanResponse>> getAllPlans() {
        return ResponseEntity.ok(subscriptionService.getAllPlans());
    }

    /**
     * How this shop reaches the platform operator, plus the platform's own name.
     *
     * <p>Served under {@code /api/saas}, which is subscription-exempt — a shop whose
     * subscription has lapsed is precisely the one that needs to see how to get in touch.
     */
    @GetMapping("/support-info")
    public ResponseEntity<SupportInfoResponse> getSupportInfo() {
        return ResponseEntity.ok(new SupportInfoResponse(
                settingsService.getFromMaster("platform.name"),
                settingsService.getFromMaster("platform.support_email"),
                settingsService.getFromMaster("platform.support_phone"),
                settingsService.getFromMaster("platform.currency_prefix")));
    }

    @GetMapping("/my-subscription")
    public ResponseEntity<TenantSubscription> getMySubscription() {
        return ResponseEntity.ok(subscriptionService.getMySubscription());
    }

    /**
     * The module set this shop may use, fetched by the POS app at login.
     *
     * <p>Replaces the frontend's hardcoded {@code PLAN_FEATURES} matrix, which failed open for
     * any unrecognised plan name. Scoped to the caller's own tenant by {@code TenantContext},
     * so any authenticated shop user may call it — there is nothing here they cannot already
     * infer from which menu items work.
     */
    @GetMapping("/my-modules")
    public ResponseEntity<MyModulesResponse> getMyModules() {
        return ResponseEntity.ok(moduleService.getMyModules());
    }

    /**
     * Platform notices this shop should see. Scoped to the caller's own tenant by
     * TenantContext, and already filtered to what has not been dismissed.
     */
    @GetMapping("/my-announcements")
    public ResponseEntity<List<AnnouncementService.ShopAnnouncement>> getMyAnnouncements() {
        return ResponseEntity.ok(announcementService.forCurrentTenant());
    }

    /** Closes a dismissible notice for this shop. */
    @PostMapping("/my-announcements/{id}/dismiss")
    public ResponseEntity<Void> dismissAnnouncement(@PathVariable Long id) {
        announcementService.dismiss(id);
        return ResponseEntity.noContent().build();
    }
}
