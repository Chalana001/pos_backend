package com.chala.posapp.service;

import com.chala.posapp.dto.promotion.CustomerSegmentDto;
import com.chala.posapp.entity.CustomerSegment;
import com.chala.posapp.entity.CustomerSegmentMember;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CustomerSegmentMemberRepository;
import com.chala.posapp.repository.CustomerSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rule-based customer segments: who a promotion means when it says "our best customers".
 *
 * <p>Membership is recomputed rather than evaluated live. The rules are aggregates over every
 * completed order, which is not something to run inside a checkout, so the answer is stored and
 * refreshed, on demand from the panel, and after a recompute the promotion cache is dropped
 * because a segment-targeted promotion now means a different set of people.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSegmentService {

    /** "Not a condition." Native queries bind this rather than a null, which needs typing. */
    private static final int NO_INT = -1;
    private static final BigDecimal NO_AMOUNT = BigDecimal.valueOf(-1);

    private final CustomerSegmentRepository segmentRepository;
    private final CustomerSegmentMemberRepository memberRepository;

    @Transactional(readOnly = true)
    public List<CustomerSegmentDto> list() {
        return segmentRepository.findAllByOrderByNameAsc().stream().map(CustomerSegmentDto::from).toList();
    }

    @Transactional(readOnly = true)
    public Set<Long> segmentIdsForCustomer(Long customerId) {
        if (customerId == null || customerId <= 0) {
            return Set.of();
        }
        return Set.copyOf(memberRepository.findSegmentIdsForCustomer(customerId));
    }

    @Transactional
    public CustomerSegmentDto create(CustomerSegmentDto request) {
        CustomerSegment segment = new CustomerSegment();
        apply(segment, request);
        CustomerSegment saved = segmentRepository.save(segment);
        recompute(saved.getId());
        return CustomerSegmentDto.from(segmentRepository.findById(saved.getId()).orElseThrow());
    }

    @Transactional
    public CustomerSegmentDto update(Long id, CustomerSegmentDto request) {
        CustomerSegment segment = segmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Segment not found"));
        apply(segment, request);
        segmentRepository.save(segment);
        recompute(id);
        return CustomerSegmentDto.from(segmentRepository.findById(id).orElseThrow());
    }

    @Transactional
    public void delete(Long id) {
        CustomerSegment segment = segmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Segment not found"));
        memberRepository.deleteBySegmentId(id);
        segmentRepository.delete(segment);
    }

    /**
     * Rebuilds one segment's membership from the current order history.
     *
     * <p>Replaces the list rather than merging: a segment is a statement about who qualifies
     * now, and a customer who has dropped below the threshold must leave it. Anyone whose
     * membership was the reason a promotion applied stops matching from the next sale.
     */
    @Transactional
    public CustomerSegmentDto recompute(Long id) {
        CustomerSegment segment = segmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Segment not found"));
        LocalDateTime now = LocalDateTime.now();

        List<Long> matching = segmentRepository.matchingCustomerIds(
                segment.getMinTotalSpend() == null ? NO_AMOUNT : segment.getMinTotalSpend(),
                segment.getMinOrderCount() == null ? NO_INT : segment.getMinOrderCount(),
                segment.getMinAvgOrderValue() == null ? NO_AMOUNT : segment.getMinAvgOrderValue(),
                segment.getPurchasedWithinDays() == null ? NO_INT : segment.getPurchasedWithinDays(),
                now.minusDays(segment.getPurchasedWithinDays() == null ? 0 : segment.getPurchasedWithinDays()),
                segment.getInactiveForDays() == null ? NO_INT : segment.getInactiveForDays(),
                now.minusDays(segment.getInactiveForDays() == null ? 0 : segment.getInactiveForDays()));

        memberRepository.deleteBySegmentId(id);
        List<CustomerSegmentMember> members = new ArrayList<>(matching.size());
        for (Long customerId : matching) {
            members.add(CustomerSegmentMember.builder()
                    .segmentId(id).customerId(customerId).addedAt(now).build());
        }
        memberRepository.saveAll(members);

        segment.setMemberCount(members.size());
        segment.setLastEvaluatedAt(now);
        segmentRepository.save(segment);
        log.info("Segment '{}' recomputed: {} member(s)", segment.getName(), members.size());
        return CustomerSegmentDto.from(segment);
    }

    /** Refreshes every active segment. Called from the panel, not from a sale. */
    @Transactional
    public List<CustomerSegmentDto> recomputeAll() {
        return segmentRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(segment -> recompute(segment.getId()))
                .toList();
    }

    private void apply(CustomerSegment segment, CustomerSegmentDto request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Segment name is required");
        }
        segment.setName(request.getName().trim());
        segment.setDescription(request.getDescription());
        segment.setMinTotalSpend(positiveOrNull(request.getMinTotalSpend()));
        segment.setMinOrderCount(positiveOrNull(request.getMinOrderCount()));
        segment.setMinAvgOrderValue(positiveOrNull(request.getMinAvgOrderValue()));
        segment.setPurchasedWithinDays(positiveOrNull(request.getPurchasedWithinDays()));
        segment.setInactiveForDays(positiveOrNull(request.getInactiveForDays()));
        segment.setActive(request.isActive());

        if (segment.hasNoRules()) {
            throw new BadRequestException("Set at least one rule, or the segment would match every customer");
        }
        // "Bought in the last 30 days" and "has not bought for 60" can both hold; the same two
        // numbers the other way round can never be true at once, and a segment that can never
        // have a member is a promotion that silently never fires.
        if (segment.getPurchasedWithinDays() != null && segment.getInactiveForDays() != null
                && segment.getInactiveForDays() >= segment.getPurchasedWithinDays()) {
            throw new BadRequestException(
                    "Nobody can have bought within " + segment.getPurchasedWithinDays()
                            + " days and also not bought for " + segment.getInactiveForDays() + " days");
        }
    }

    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }
}
