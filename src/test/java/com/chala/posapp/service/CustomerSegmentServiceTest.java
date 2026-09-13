package com.chala.posapp.service;

import com.chala.posapp.dto.promotion.CustomerSegmentDto;
import com.chala.posapp.entity.CustomerSegment;
import com.chala.posapp.entity.CustomerSegmentMember;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.CustomerSegmentMemberRepository;
import com.chala.posapp.repository.CustomerSegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Segments are what a promotion means by "our best customers". These pin the two things that
 * would quietly cost a shop money: a rule set that can never match, and a membership list that
 * grows but never shrinks.
 */
class CustomerSegmentServiceTest {

    private CustomerSegmentRepository segmentRepository;
    private CustomerSegmentMemberRepository memberRepository;
    private CustomerSegmentService service;

    @BeforeEach
    void setUp() {
        segmentRepository = mock(CustomerSegmentRepository.class);
        memberRepository = mock(CustomerSegmentMemberRepository.class);
        service = new CustomerSegmentService(segmentRepository, memberRepository);
        when(segmentRepository.save(any())).thenAnswer(call -> {
            CustomerSegment segment = call.getArgument(0);
            if (segment.getId() == null) {
                segment.setId(1L);
            }
            return segment;
        });
        when(segmentRepository.findById(1L)).thenAnswer(call -> Optional.of(saved));
    }

    /** Whatever the service last saved, so findById after save returns it. */
    private CustomerSegment saved = CustomerSegment.builder().id(1L).name("Test").build();

    private CustomerSegmentDto dto() {
        return CustomerSegmentDto.builder().name("Big spenders").active(true).build();
    }

    @Test
    @DisplayName("a segment with no rules is refused. It would match the entire customer list")
    void noRules() {
        assertThatThrownBy(() -> service.create(dto()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("match every customer");
    }

    @Test
    @DisplayName("a rule set nobody can satisfy is refused rather than silently never firing")
    void contradictoryWindows() {
        CustomerSegmentDto request = dto();
        request.setPurchasedWithinDays(30);
        request.setInactiveForDays(60);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not bought for 60 days");
    }

    @Test
    @DisplayName("bought recently and lapsed on a longer horizon is a legitimate pair")
    void compatibleWindows() {
        CustomerSegmentDto request = dto();
        request.setPurchasedWithinDays(90);
        request.setInactiveForDays(30);
        when(segmentRepository.matchingCustomerIds(any(), anyInt(), any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(List.of(7L));

        assertThat(service.create(request)).isNotNull();
    }

    @Test
    @DisplayName("zero and negative thresholds are dropped rather than stored as conditions")
    void thresholdsNormalised() {
        CustomerSegmentDto request = dto();
        request.setMinTotalSpend(BigDecimal.ZERO);
        request.setMinOrderCount(0);
        request.setMinAvgOrderValue(BigDecimal.valueOf(-5));
        request.setPurchasedWithinDays(30);
        when(segmentRepository.matchingCustomerIds(any(), anyInt(), any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(List.of());

        service.create(request);

        ArgumentCaptor<CustomerSegment> captor = ArgumentCaptor.forClass(CustomerSegment.class);
        verify(segmentRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        CustomerSegment stored = captor.getAllValues().get(0);
        assertThat(stored.getMinTotalSpend()).isNull();
        assertThat(stored.getMinOrderCount()).isNull();
        assertThat(stored.getMinAvgOrderValue()).isNull();
        assertThat(stored.getPurchasedWithinDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("recompute replaces the membership and records the count")
    void recomputeReplaces() {
        // Replacing rather than merging is the point: a customer who has dropped below the
        // threshold must leave, or a promotion goes on discounting for someone who no longer
        // qualifies, and nobody would notice.
        saved = CustomerSegment.builder().id(1L).name("Big spenders")
                .minTotalSpend(BigDecimal.valueOf(50_000)).active(true).build();
        when(segmentRepository.findById(1L)).thenReturn(Optional.of(saved));
        when(segmentRepository.matchingCustomerIds(any(), anyInt(), any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(List.of(7L, 8L, 9L));

        CustomerSegmentDto result = service.recompute(1L);

        verify(memberRepository).deleteBySegmentId(1L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CustomerSegmentMember>> members = ArgumentCaptor.forClass(List.class);
        verify(memberRepository).saveAll(members.capture());
        assertThat(members.getValue()).extracting(CustomerSegmentMember::getCustomerId)
                .containsExactly(7L, 8L, 9L);
        assertThat(result.getMemberCount()).isEqualTo(3);
        assertThat(saved.getLastEvaluatedAt()).isNotNull().isBefore(LocalDateTime.now().plusMinutes(1));
    }

    @Test
    @DisplayName("a customer with no segments gets an empty set rather than a query")
    void noCustomer() {
        assertThat(service.segmentIdsForCustomer(null)).isEmpty();
        assertThat(service.segmentIdsForCustomer(0L)).isEmpty();
        verify(memberRepository, org.mockito.Mockito.never()).findSegmentIdsForCustomer(any());
    }
}
