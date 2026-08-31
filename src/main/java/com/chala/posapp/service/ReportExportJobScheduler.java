package com.chala.posapp.service;

import com.chala.posapp.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Component
@RequiredArgsConstructor
@Slf4j
public class ReportExportJobScheduler {
    private final TenantDatabaseRegistry tenantDatabaseRegistry;
    private final ReportExportJobService exportJobService;
    private final ReportScheduleService reportScheduleService;
    private final ForecastAccuracyService forecastAccuracyService;
    private final EntityManagerFactory entityManagerFactory;

    @Scheduled(fixedDelayString = "${app.report-exports.poll-delay-ms:5000}")
    public void processQueue() {
        var workers = tenantDatabaseRegistry.getActiveTenantIds().stream()
                .map(tenantId -> Thread.ofVirtual().start(() -> {
                    try {
                        processTenantNow(tenantId);
                    } catch (Exception error) {
                        log.error("Failed to process report export queue for tenant {}", tenantId, error);
                    }
                }))
                .toList();
        workers.forEach(worker -> {
            try {
                worker.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public void processTenantNow(String tenantId) {
        EntityManager entityManager = entityManagerFactory.unwrap(SessionFactory.class)
                .withOptions().tenantIdentifier(tenantId).openSession();
        EntityManagerHolder holder = new EntityManagerHolder(entityManager);
        TransactionSynchronizationManager.bindResource(entityManagerFactory, holder);
        try {
            TenantContext.runWith(tenantId, () -> {
                exportJobService.recoverStaleJobs();
                reportScheduleService.enqueueDueSchedules();
                exportJobService.processQueuedJobs();
                forecastAccuracyService.evaluateMatured();
            });
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            entityManager.close();
        }
    }

    @Scheduled(cron = "${app.report-exports.cleanup-cron:0 30 2 * * *}")
    public void cleanupExpiredExports() {
        tenantDatabaseRegistry.getActiveTenantIds().forEach(tenantId -> {
            try {
                EntityManager entityManager = entityManagerFactory.unwrap(SessionFactory.class)
                        .withOptions().tenantIdentifier(tenantId).openSession();
                EntityManagerHolder holder = new EntityManagerHolder(entityManager);
                TransactionSynchronizationManager.bindResource(entityManagerFactory, holder);
                try {
                    TenantContext.runWith(tenantId, exportJobService::cleanupExpiredJobs);
                    TenantContext.runWith(tenantId, forecastAccuracyService::cleanup);
                } finally {
                    TransactionSynchronizationManager.unbindResource(entityManagerFactory);
                    entityManager.close();
                }
            } catch (Exception error) {
                log.error("Failed to clean report exports for tenant {}", tenantId, error);
            }
        });
    }
}
