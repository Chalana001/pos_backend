package com.chala.posapp.service;

import com.chala.posapp.dto.report.ReportExportOperationsResponse;
import com.chala.posapp.entity.ReportExportStatus;
import com.chala.posapp.repository.ReportExportJobRepository;
import com.chala.posapp.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class ReportExportOperationsService {
    private final TenantDatabaseRegistry tenantDatabaseRegistry;
    private final EntityManagerFactory entityManagerFactory;
    private final ReportExportJobRepository repository;
    @Value("${app.report-exports.stale-after-minutes:30}") private long staleAfterMinutes;

    public ReportExportOperationsResponse summary() {
        var tenantStatuses = new ArrayList<ReportExportOperationsResponse.TenantStatus>();
        for (String tenantId : tenantDatabaseRegistry.getActiveTenantIds()) {
            try {
                tenantStatuses.add(readTenant(tenantId));
            } catch (Exception error) {
                tenantStatuses.add(new ReportExportOperationsResponse.TenantStatus(tenantId, "DOWN", 0, 0, 0, error.getMessage()));
            }
        }
        long failed = tenantStatuses.stream().mapToLong(ReportExportOperationsResponse.TenantStatus::failed).sum();
        long stale = tenantStatuses.stream().mapToLong(ReportExportOperationsResponse.TenantStatus::stale).sum();
        long queued = tenantStatuses.stream().mapToLong(ReportExportOperationsResponse.TenantStatus::queued).sum();
        String status = tenantStatuses.stream().anyMatch(item -> "DOWN".equals(item.status())) || stale > 0 ? "DOWN" : failed > 0 ? "DEGRADED" : "UP";
        return new ReportExportOperationsResponse(status, failed, stale, queued, tenantStatuses);
    }

    private ReportExportOperationsResponse.TenantStatus readTenant(String tenantId) {
        EntityManager entityManager = entityManagerFactory.unwrap(SessionFactory.class)
                .withOptions().tenantIdentifier(tenantId).openSession();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
        try {
            return TenantContext.callWith(tenantId, () -> {
                long failed = repository.countByStatus(ReportExportStatus.FAILED);
                long stale = repository.countByStatusAndStartedAtBefore(ReportExportStatus.PROCESSING,
                        LocalDateTime.now().minusMinutes(staleAfterMinutes));
                long queued = repository.countByStatus(ReportExportStatus.QUEUED);
                return new ReportExportOperationsResponse.TenantStatus(tenantId,
                        stale > 0 ? "DOWN" : failed > 0 ? "DEGRADED" : "UP", failed, stale, queued, null);
            });
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            entityManager.close();
        }
    }
}
