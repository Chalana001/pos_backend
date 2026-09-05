package com.chala.posapp.service;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Promotion;
import com.chala.posapp.entity.PromotionAudit;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.PromotionSettings;
import com.chala.posapp.entity.PromotionStatus;
import com.chala.posapp.entity.PromotionTarget;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.PromotionAuditRepository;
import com.chala.posapp.repository.PromotionSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stored half of the lifecycle: who may move a promotion where, and when a second admin
 * has to look first.
 */
class PromotionLifecycleServiceTest {

    private PromotionSettingsRepository settingsRepository;
    private PromotionAuditRepository auditRepository;
    private PromotionLifecycleService service;

    private final User owner = User.builder().id(1L).username("owner").role(Role.ADMIN).build();
    private final User secondAdmin = User.builder().id(2L).username("partner").role(Role.ADMIN).build();
    private final User manager = User.builder().id(3L).username("manager").role(Role.MANAGER).build();

    @BeforeEach
    void setUp() {
        settingsRepository = mock(PromotionSettingsRepository.class);
        auditRepository = mock(PromotionAuditRepository.class);
        service = new PromotionLifecycleService(settingsRepository, auditRepository, mock(PromotionSnapshotCache.class));
    }

    private void approvalAbove(double percent) {
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(PromotionSettings.builder()
                .approvalRequired(true).approvalThresholdPercent(BigDecimal.valueOf(percent)).build()));
    }

    private void noApproval() {
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
    }

    private Promotion percentOff(double percent) {
        Promotion p = Promotion.builder().id(10L).name("Test").scope(PromotionScope.ITEM)
                .discountType(DiscountType.PERCENT).discountValue(BigDecimal.valueOf(percent))
                .status(PromotionStatus.DRAFT).active(false).build();
        p.getTargets().add(PromotionTarget.builder().promotion(p).itemId(7L).build());
        return p;
    }

    @Nested
    @DisplayName("creating and submitting")
    class Submitting {

        @Test
        @DisplayName("a promotion the client did not switch on starts as a draft the engine never sees")
        void draft() {
            noApproval();
            Promotion p = percentOff(10);

            service.initialise(p, false, owner, id -> null);

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.DRAFT);
            assertThat(p.isActive()).isFalse();
            assertThat(p.getCreatedBy()).isEqualTo(1L);
        }

        @Test
        @DisplayName("with approval off, switching on goes straight to active")
        void straightToActive() {
            noApproval();
            Promotion p = percentOff(40);

            service.initialise(p, true, owner, id -> null);

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
            assertThat(p.isActive()).isTrue();
        }

        @Test
        @DisplayName("above the threshold it waits for approval and is not live")
        void pendingAboveThreshold() {
            approvalAbove(30);
            Promotion p = percentOff(40);

            service.initialise(p, true, owner, id -> null);

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.PENDING_APPROVAL);
            assertThat(p.isActive()).isFalse();
            assertThat(p.getSubmittedBy()).isEqualTo(1L);
        }

        @Test
        @DisplayName("below the threshold it does not")
        void activeBelowThreshold() {
            approvalAbove(30);
            Promotion p = percentOff(10);

            service.initialise(p, true, owner, id -> null);

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        }

        @Test
        @DisplayName("a per-item offer price deeper than the headline rate is what gets measured")
        void offerPriceCountsAsDeepest() {
            approvalAbove(30);
            Promotion p = percentOff(5);
            p.getTargets().get(0).setOfferPrice(BigDecimal.valueOf(50)); // list 100 -> 50% off

            service.initialise(p, true, owner, id -> BigDecimal.valueOf(100));

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("buy two get one free is a 33% cut for approval purposes")
        void bogoDepth() {
            Promotion p = percentOff(0);
            p.setEffectType(PromotionEffectType.BUY_X_GET_Y_FREE);
            p.setBuyQty(BigDecimal.valueOf(2));
            p.setGetQty(BigDecimal.ONE);

            assertThat(service.deepestDiscountPercent(p, id -> null)).isEqualByComparingTo("33.33");
        }
    }

    @Nested
    @DisplayName("approving")
    class Approving {

        private Promotion pending() {
            approvalAbove(30);
            Promotion p = percentOff(40);
            service.initialise(p, true, owner, id -> null);
            return p;
        }

        @Test
        @DisplayName("a different admin approves and it goes live")
        void secondAdminApproves() {
            Promotion p = pending();

            service.approve(p, secondAdmin, "Fine for December");

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
            assertThat(p.isActive()).isTrue();
            assertThat(p.getApprovedBy()).isEqualTo(2L);
            assertThat(p.getApprovalNote()).isEqualTo("Fine for December");
        }

        @Test
        @DisplayName("the person who submitted it cannot approve it")
        void notTheSubmitter() {
            Promotion p = pending();

            assertThatThrownBy(() -> service.approve(p, owner, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("someone other than");
            assertThat(p.getStatus()).isEqualTo(PromotionStatus.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("a manager cannot approve")
        void managerCannotApprove() {
            Promotion p = pending();

            assertThatThrownBy(() -> service.approve(p, manager, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Only an admin");
        }

        @Test
        @DisplayName("rejecting sends it back to draft with the note kept")
        void reject() {
            Promotion p = pending();

            service.reject(p, secondAdmin, "Too deep for a weekday");

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.DRAFT);
            assertThat(p.isActive()).isFalse();
            assertThat(p.getApprovalNote()).isEqualTo("Too deep for a weekday");
        }

        @Test
        @DisplayName("approving something that is not pending is refused")
        void onlyPendingCanBeApproved() {
            noApproval();
            Promotion p = percentOff(10);
            service.initialise(p, true, owner, id -> null);

            assertThatThrownBy(() -> service.approve(p, secondAdmin, null))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("editing an approved promotion")
    class Editing {

        @Test
        @DisplayName("changing the terms knocks it back to pending; changing the name does not")
        void termsVersusName() {
            approvalAbove(30);
            Promotion p = percentOff(40);
            service.initialise(p, true, owner, id -> null);
            service.approve(p, secondAdmin, null);
            String before = service.termsFingerprint(p);

            p.setName("Renamed");
            service.onUpdated(p, !before.equals(service.termsFingerprint(p)), true, owner, id -> null);
            assertThat(p.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
            assertThat(p.getApprovedBy()).isEqualTo(2L);

            p.setDiscountValue(BigDecimal.valueOf(60));
            service.onUpdated(p, !before.equals(service.termsFingerprint(p)), true, owner, id -> null);
            assertThat(p.getStatus()).isEqualTo(PromotionStatus.PENDING_APPROVAL);
            assertThat(p.isActive()).isFalse();
            assertThat(p.getApprovedBy()).isNull();
        }

        @Test
        @DisplayName("unticking active on an edit pauses it")
        void untickPauses() {
            noApproval();
            Promotion p = percentOff(10);
            service.initialise(p, true, owner, id -> null);

            service.onUpdated(p, false, false, owner, id -> null);

            assertThat(p.getStatus()).isEqualTo(PromotionStatus.PAUSED);
            assertThat(p.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("pause and resume")
    class PauseResume {

        @Test
        @DisplayName("a resumed promotion whose terms were approved does not queue again")
        void resumeKeepsApproval() {
            approvalAbove(30);
            Promotion p = percentOff(40);
            service.initialise(p, true, owner, id -> null);
            service.approve(p, secondAdmin, null);

            service.pause(p, manager, "Stock ran out");
            assertThat(p.getStatus()).isEqualTo(PromotionStatus.PAUSED);

            service.resume(p, manager, id -> null);
            assertThat(p.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        }

        @Test
        @DisplayName("the legacy on/off switch maps onto the same transitions")
        void legacySwitch() {
            noApproval();
            Promotion p = percentOff(10);
            service.initialise(p, true, owner, id -> null);

            service.setActive(p, false, manager, id -> null);
            assertThat(p.getStatus()).isEqualTo(PromotionStatus.PAUSED);
            service.setActive(p, true, manager, id -> null);
            assertThat(p.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        }

        @Test
        @DisplayName("every transition leaves an audit row naming who did it")
        void auditRows() {
            noApproval();
            Promotion p = percentOff(10);
            service.initialise(p, true, owner, id -> null);
            service.pause(p, manager, "Lunch");

            ArgumentCaptor<PromotionAudit> rows = ArgumentCaptor.forClass(PromotionAudit.class);
            verify(auditRepository, atLeastOnce()).save(rows.capture());
            assertThat(rows.getAllValues())
                    .extracting(PromotionAudit::getAction, PromotionAudit::getUsername)
                    .contains(
                            org.assertj.core.groups.Tuple.tuple(PromotionAudit.Action.ACTIVATED, "owner"),
                            org.assertj.core.groups.Tuple.tuple(PromotionAudit.Action.PAUSED, "manager"));
        }
    }
}
