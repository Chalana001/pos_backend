package com.chala.posapp.service;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.saas.AuditEntryResponse;
import com.chala.posapp.entity.SuperAdminAuditLog;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.SuperAdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read side of the audit trail. Kept separate from {@link SuperAdminAuditService} so the write
 * path stays a tiny, dependency-light thing that any service can call without dragging paging
 * and specification machinery along with it.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminAuditQueryService {

    private final SuperAdminAuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public PageResponse<AuditEntryResponse> search(int page, int size, String search, String action,
                                                   String targetType, String targetId, String from, String to) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<SuperAdminAuditLog> specification = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("summary")), term),
                        cb.like(cb.lower(root.get("actor")), term),
                        cb.like(cb.lower(root.get("targetId")), term)));
            }
            if (notBlank(action)) {
                predicates.add(cb.equal(root.get("action"), action.trim().toUpperCase(Locale.ROOT)));
            }
            if (notBlank(targetType)) {
                predicates.add(cb.equal(root.get("targetType"), targetType.trim().toUpperCase(Locale.ROOT)));
            }
            if (notBlank(targetId)) {
                predicates.add(cb.equal(root.get("targetId"), targetId.trim()));
            }
            if (notBlank(from)) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), parse(from).atStartOfDay()));
            }
            if (notBlank(to)) {
                predicates.add(cb.lessThan(root.get("createdAt"), parse(to).plusDays(1).atStartOfDay()));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<SuperAdminAuditLog> results = auditLogRepository.findAll(specification, pageable);

        return PageResponse.<AuditEntryResponse>builder()
                .items(results.getContent().stream().map(this::toResponse).toList())
                .page(results.getNumber())
                .size(results.getSize())
                .totalElements(results.getTotalElements())
                .totalPages(results.getTotalPages())
                .first(results.isFirst())
                .last(results.isLast())
                .build();
    }

    /** The timeline shown on a single shop's detail page. */
    @Transactional(readOnly = true)
    public List<AuditEntryResponse> forShop(String tenantId) {
        return auditLogRepository
                .findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc(SuperAdminAuditService.TARGET_SHOP, tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** Recent activity strip on the panel dashboard. */
    @Transactional(readOnly = true)
    public List<AuditEntryResponse> recent() {
        return auditLogRepository.findTop15ByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditEntryResponse toResponse(SuperAdminAuditLog entry) {
        return new AuditEntryResponse(
                entry.getId(), entry.getActor(), entry.getAction(), entry.getTargetType(),
                entry.getTargetId(), entry.getSummary(), entry.getDetails(), entry.getIpAddress(),
                entry.getCreatedAt());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank() && !"all".equalsIgnoreCase(value.trim());
    }

    private LocalDate parse(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception exception) {
            throw new BadRequestException("Invalid date (expected yyyy-MM-dd): " + raw);
        }
    }
}
