package com.chala.posapp.service;

import com.chala.posapp.dto.saas.LifecycleDtos.AnnouncementRequest;
import com.chala.posapp.entity.Announcement;
import com.chala.posapp.entity.AnnouncementDismissal;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.repository.AnnouncementDismissalRepository;
import com.chala.posapp.repository.AnnouncementRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Announcements shown inside shops' POS apps.
 *
 * <p>Two sides: the panel manages them, and each shop asks what applies to it. The shop-facing
 * read resolves targeting per tenant — including MODULE targeting, which needs the shop's
 * effective module set and therefore cannot be a SQL filter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementDismissalRepository dismissalRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final ModuleAccessService moduleAccessService;
    private final SuperAdminAuditService auditService;
    private final PlatformTransactionManager transactionManager;

    /** What a shop receives. Trimmed of everything the shop has no business seeing. */
    public record ShopAnnouncement(
            Long id,
            String title,
            String body,
            String severity,
            boolean dismissible,
            String linkUrl,
            String linkLabel,
            LocalDateTime activeUntil
    ) {
    }

    public record AnnouncementSummary(
            Announcement announcement,
            String status,
            long dismissedBy,
            int reaches
    ) {
    }

    // ------------------------------------------------------------ panel side

    @Transactional(readOnly = true)
    public List<AnnouncementSummary> list() {
        return announcementRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(announcement -> new AnnouncementSummary(
                        announcement,
                        announcement.getStatus(),
                        dismissalRepository.countByAnnouncementId(announcement.getId()),
                        countAudience(announcement)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Announcement get(Long id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found"));
    }

    @Transactional
    public Announcement create(AnnouncementRequest request) {
        Announcement incoming = new Announcement();
        apply(incoming, request);
        incoming.setCreatedBy(currentActor());
        Announcement saved = announcementRepository.save(incoming);

        auditService.record(currentActor(), "ANNOUNCEMENT_CREATED", SuperAdminAuditService.TARGET_SYSTEM,
                String.valueOf(saved.getId()),
                "Created " + saved.getSeverity() + " announcement \"" + saved.getTitle()
                        + "\" for " + describeAudience(saved)
                        + (saved.isPublished() ? " (published)" : " (draft)"));
        return saved;
    }

    @Transactional
    public Announcement update(Long id, AnnouncementRequest request) {
        Announcement existing = get(id);
        boolean wasPublished = existing.isPublished();

        apply(existing, request);
        Announcement saved = announcementRepository.save(existing);

        if (!wasPublished && saved.isPublished()) {
            auditService.record(currentActor(), "ANNOUNCEMENT_PUBLISHED",
                    SuperAdminAuditService.TARGET_SYSTEM, String.valueOf(id),
                    "Published \"" + saved.getTitle() + "\" to " + describeAudience(saved)
                            + " (" + countAudience(saved) + " shop(s))");
        } else {
            auditService.record(currentActor(), "ANNOUNCEMENT_UPDATED",
                    SuperAdminAuditService.TARGET_SYSTEM, String.valueOf(id),
                    "Updated announcement \"" + saved.getTitle() + "\"");
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Announcement announcement = get(id);
        announcementRepository.delete(announcement);
        auditService.record(currentActor(), "ANNOUNCEMENT_DELETED", SuperAdminAuditService.TARGET_SYSTEM,
                String.valueOf(id), "Deleted announcement \"" + announcement.getTitle() + "\"");
    }

    /** Copies the request onto the entity and validates the result. */
    private void apply(Announcement entity, AnnouncementRequest request) {
        entity.setTitle(request.title() == null ? null : request.title().trim());
        entity.setBody(request.body());
        entity.setSeverity(Announcement.Severity.valueOf(
                request.severity() == null ? "INFO" : request.severity().trim().toUpperCase(Locale.ROOT)));
        entity.setAudience(Announcement.Audience.valueOf(
                request.audience() == null ? "ALL" : request.audience().trim().toUpperCase(Locale.ROOT)));
        entity.setAudienceValue(trimToNull(request.audienceValue()));
        entity.setActiveFrom(request.activeFrom());
        entity.setActiveUntil(request.activeUntil());
        entity.setDismissible(request.dismissible() == null || request.dismissible());
        entity.setPublished(Boolean.TRUE.equals(request.published()));
        entity.setLinkUrl(trimToNull(request.linkUrl()));
        entity.setLinkLabel(trimToNull(request.linkLabel()));
        validate(entity);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validate(Announcement announcement) {
        if (announcement.getTitle() == null || announcement.getTitle().isBlank()) {
            throw new BadRequestException("Title is required");
        }
        if (announcement.getBody() == null || announcement.getBody().isBlank()) {
            throw new BadRequestException("Message body is required");
        }
        if (announcement.getAudience() != Announcement.Audience.ALL
                && (announcement.getAudienceValue() == null || announcement.getAudienceValue().isBlank())) {
            throw new BadRequestException(
                    "Targeting " + announcement.getAudience() + " needs a value to target.");
        }
        if (announcement.getAudience() == Announcement.Audience.MODULE
                && !ModuleCatalog.exists(announcement.getAudienceValue())) {
            throw new BadRequestException("Unknown module: " + announcement.getAudienceValue());
        }
        if (announcement.getActiveFrom() != null && announcement.getActiveUntil() != null
                && announcement.getActiveUntil().isBefore(announcement.getActiveFrom())) {
            throw new BadRequestException("The end date cannot be before the start date");
        }
    }

    /** How many shops this announcement would currently reach. */
    private int countAudience(Announcement announcement) {
        return (int) tenantSubscriptionRepository.findAll().stream()
                .filter(subscription -> targets(announcement, subscription))
                .count();
    }

    private String describeAudience(Announcement announcement) {
        return switch (announcement.getAudience()) {
            case ALL -> "every shop";
            case PLAN -> "plan " + announcement.getAudienceValue();
            case TENANT -> "shop " + announcement.getAudienceValue();
            case MODULE -> "shops with " + announcement.getAudienceValue();
        };
    }

    private boolean targets(Announcement announcement, TenantSubscription subscription) {
        return switch (announcement.getAudience()) {
            case ALL -> true;
            case TENANT -> subscription.getTenantId().equalsIgnoreCase(announcement.getAudienceValue());
            case PLAN -> subscription.getPlan() != null
                    && String.valueOf(subscription.getPlan().getId()).equals(announcement.getAudienceValue());
            case MODULE -> moduleAccessService.snapshotFor(subscription.getTenantId())
                    .isEnabled(announcement.getAudienceValue());
        };
    }

    // ------------------------------------------------------------- shop side

    /**
     * What this shop should see right now. Runs against the control plane regardless of the
     * caller's tenant context, so a shop asking about itself does not need MASTER access.
     */
    @Transactional(readOnly = true)
    public List<ShopAnnouncement> forCurrentTenant() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            throw new BadRequestException("No active shop context found!");
        }
        return forTenant(tenantId);
    }

    public List<ShopAnnouncement> forTenant(String tenantId) {
        return inMaster(() -> {
            TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);
            if (subscription == null) {
                return List.<ShopAnnouncement>of();
            }
            Set<Long> dismissed = Set.copyOf(dismissalRepository.dismissedIdsFor(tenantId));

            return announcementRepository.findLive(LocalDateTime.now()).stream()
                    .filter(announcement -> targets(announcement, subscription))
                    // A dismissible notice the shop already closed stays closed; a
                    // non-dismissible one keeps showing however often they reload.
                    .filter(announcement -> !announcement.isDismissible() || !dismissed.contains(announcement.getId()))
                    .sorted(Comparator
                            .comparingInt((Announcement a) -> switch (a.getSeverity()) {
                                case CRITICAL -> 0;
                                case WARNING -> 1;
                                case INFO -> 2;
                            })
                            .thenComparing(Announcement::getCreatedAt, Comparator.reverseOrder()))
                    .map(announcement -> new ShopAnnouncement(
                            announcement.getId(), announcement.getTitle(), announcement.getBody(),
                            announcement.getSeverity().name(), announcement.isDismissible(),
                            announcement.getLinkUrl(), announcement.getLinkLabel(),
                            announcement.getActiveUntil()))
                    .toList();
        });
    }

    /**
     * Called by a shop closing a banner. Silently ignores a non-dismissible one.
     *
     * <p>The tenant id is read from the caller's context, but every database touch below runs
     * against MASTER: announcements and their dismissals live in the control plane, and a
     * shop's own catalog has no such tables.
     */
    public void dismiss(Long announcementId) {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            throw new BadRequestException("No active shop context found!");
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TenantContext.runWith("MASTER", () -> tx.executeWithoutResult(status -> {
            Announcement announcement = announcementRepository.findById(announcementId)
                    .orElseThrow(() -> new ResourceNotFoundException("Announcement not found"));
            if (!announcement.isDismissible()) {
                return;
            }
            if (dismissalRepository.existsByAnnouncementIdAndTenantId(announcementId, tenantId)) {
                return;
            }
            dismissalRepository.save(AnnouncementDismissal.builder()
                    .announcementId(announcementId)
                    .tenantId(tenantId)
                    .build());
        }));
    }

    private <T> T inMaster(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return TenantContext.callWith("MASTER", () -> tx.execute(status -> work.get()));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}
