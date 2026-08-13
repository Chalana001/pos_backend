package com.chala.posapp.service;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.report.ReportExportJobRequest;
import com.chala.posapp.dto.report.ReportExportJobResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ReportExportJobRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportExportJobService {
    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final ReportExportJobRepository repository;
    private final ReportService reportService;
    private final SecurityUtils securityUtils;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ReportExportStorage storage;
    private final ObjectProvider<ReportExportEmailService> emailService;
    private final TransactionTemplate transactionTemplate;
    private final ReportExportMetrics metrics;

    @Value("${app.report-exports.max-attempts:3}") private int maxAttempts;
    @Value("${app.report-exports.retry-delay-seconds:60}") private long retryDelaySeconds;
    @Value("${app.report-exports.stale-after-minutes:30}") private long staleAfterMinutes;
    @Value("${app.report-exports.retention-days:30}") private long retentionDays;

    @Transactional
    public ReportExportJobResponse create(ReportExportJobRequest request) {
        validateRequest(request);
        if (request.emailTo() != null && !request.emailTo().isBlank()) throw new BadRequestException("Email delivery is only available for scheduled reports");
        User user = securityUtils.getCurrentUser();
        return toResponse(saveJob(request, user.getId(), user.getUsername(), null, request.emailTo()));
    }

    @Transactional
    public ReportExportJob createScheduled(ReportSchedule schedule) {
        try {
            ReportExportJobRequest request = objectMapper.readValue(schedule.getParametersJson(), ReportExportJobRequest.class);
            return saveJob(request, schedule.getRequestedByUserId(), schedule.getRequestedByUsername(), schedule.getId(), schedule.getEmailTo());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid scheduled report parameters", error);
        }
    }

    private ReportExportJob saveJob(ReportExportJobRequest request, Long userId, String username, Long scheduleId, String emailTo) {
        try {
            return repository.save(ReportExportJob.builder().requestedByUserId(userId).requestedByUsername(username)
                    .reportType(request.reportType().trim().toUpperCase()).status(ReportExportStatus.QUEUED)
                    .parametersJson(objectMapper.writeValueAsString(request)).emailTo(blankToNull(emailTo))
                    .maxAttempts(maxAttempts).nextAttemptAt(LocalDateTime.now()).scheduleId(scheduleId).build());
        } catch (JsonProcessingException error) {
            throw new BadRequestException("Invalid export parameters");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportExportJobResponse> list(int page, int size) {
        Page<ReportExportJob> jobs = repository.findByRequestedByUserIdOrderByCreatedAtDesc(securityUtils.getCurrentUser().getId(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
        return PageResponse.<ReportExportJobResponse>builder().items(jobs.getContent().stream().map(this::toResponse).toList())
                .page(jobs.getNumber()).size(jobs.getSize()).totalElements(jobs.getTotalElements())
                .totalPages(jobs.getTotalPages()).first(jobs.isFirst()).last(jobs.isLast()).build();
    }

    @Transactional(readOnly = true)
    public ReportExportJob getOwnedJob(Long id) {
        return repository.findByIdAndRequestedByUserId(id, securityUtils.getCurrentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Report export job not found"));
    }

    public byte[] download(Long id) {
        ReportExportJob job = getOwnedJob(id);
        if (job.getStatus() != ReportExportStatus.COMPLETED || (job.getStorageKey() == null && job.getFilePath() == null)) throw new BadRequestException("Report export is not ready for download");
        if (job.getStorageKey() != null) return storage.read(job.getStorageKey());
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(job.getFilePath()));
        } catch (java.io.IOException error) {
            throw new ResourceNotFoundException("Report export file is no longer available");
        }
    }

    @Transactional
    public ReportExportJobResponse cancel(Long id) {
        ReportExportJob job = getOwnedJob(id);
        if (job.getStatus() != ReportExportStatus.QUEUED) throw new BadRequestException("Only queued exports can be cancelled");
        job.setStatus(ReportExportStatus.CANCELLED);
        job.setCompletedAt(LocalDateTime.now());
        ReportExportJob saved = repository.save(job);
        metrics.increment("cancelled");
        return toResponse(saved);
    }

    @Transactional
    public ReportExportJobResponse retry(Long id) {
        ReportExportJob job = getOwnedJob(id);
        if (job.getStatus() != ReportExportStatus.FAILED) throw new BadRequestException("Only failed exports can be retried");
        job.setStatus(ReportExportStatus.QUEUED); job.setAttemptCount(0); job.setErrorMessage(null);
        job.setStartedAt(null); job.setCompletedAt(null); job.setNextAttemptAt(LocalDateTime.now());
        ReportExportJob saved = repository.save(job);
        metrics.increment("manual_retry");
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        ReportExportJob job = getOwnedJob(id);
        if (job.getStatus() == ReportExportStatus.PROCESSING) throw new BadRequestException("Processing exports cannot be deleted");
        deleteStoredFile(job);
        repository.delete(job);
        metrics.increment("deleted");
    }

    public void processQueuedJobs() {
        var jobs = repository.findTop5ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(ReportExportStatus.QUEUED, LocalDateTime.now());
        if (!jobs.isEmpty()) log.info("Processing {} report export job(s) for tenant {}", jobs.size(), TenantContext.getTenant());
        jobs.forEach(this::process);
    }

    @Transactional
    public void recoverStaleJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleAfterMinutes);
        for (ReportExportJob job : repository.findByStatusAndStartedAtBefore(ReportExportStatus.PROCESSING, cutoff)) {
            if (repository.recover(job.getId(), ReportExportStatus.PROCESSING, ReportExportStatus.QUEUED,
                    cutoff, LocalDateTime.now(), "Recovered after worker timeout") == 1) metrics.increment("recovered");
        }
    }

    public void cleanupExpiredJobs() {
        for (ReportExportJob job : repository.findByCompletedAtBefore(LocalDateTime.now().minusDays(retentionDays))) {
            if (job.getStatus() == ReportExportStatus.PROCESSING || job.getStatus() == ReportExportStatus.QUEUED) continue;
            deleteStoredFile(job);
            repository.delete(job);
            metrics.increment("retention_deleted");
        }
    }

    private void process(ReportExportJob job) {
        Long jobId = job.getId();
        if (!Boolean.TRUE.equals(transactionTemplate.execute(status -> repository.claim(jobId, ReportExportStatus.QUEUED,
                ReportExportStatus.PROCESSING, LocalDateTime.now()) == 1))) return;
        job = repository.findById(jobId).orElseThrow();
        var previousContext = SecurityContextHolder.getContext();
        try {
            User requester = userRepository.findById(job.getRequestedByUserId()).orElseThrow(() -> new ResourceNotFoundException("Export requester no longer exists"));
            var workerContext = SecurityContextHolder.createEmptyContext();
            workerContext.setAuthentication(new UsernamePasswordAuthenticationToken(requester.getUsername(), null,
                    java.util.List.of(new SimpleGrantedAuthority("ROLE_" + requester.getRole().name()))));
            SecurityContextHolder.setContext(workerContext);
            ReportExportJobRequest request = objectMapper.readValue(job.getParametersJson(), ReportExportJobRequest.class);
            byte[] bytes = reportService.exportPerformanceReport(request.reportType(), request.branchId(), request.from(), request.to(),
                    request.itemType(), request.orderType(), request.sortBy(), request.sortDirection());
            String fileName = request.reportType().toLowerCase() + "-report-job-" + job.getId() + ".xlsx";
            String tenant = TenantContext.getTenant() == null ? "unknown" : TenantContext.getTenant();
            job.setStorageKey(storage.store(tenant, fileName, bytes)); job.setFilePath(null);
            job.setFileName(fileName); job.setContentType(XLSX_CONTENT_TYPE); job.setFileSize((long) bytes.length);
            if (job.getEmailTo() != null) {
                ReportExportEmailService sender = emailService.getIfAvailable();
                if (sender == null) throw new IllegalStateException("Report email delivery is not configured");
                sender.send(job.getEmailTo(), fileName, bytes); job.setEmailDeliveredAt(LocalDateTime.now());
                metrics.increment("emailed");
            }
            job.setStatus(ReportExportStatus.COMPLETED); job.setCompletedAt(LocalDateTime.now());
            metrics.increment("completed");
        } catch (Exception error) {
            String message = error.getMessage() == null ? "Export generation failed" : error.getMessage();
            job.setErrorMessage(message.substring(0, Math.min(message.length(), 500)));
            if (job.getAttemptCount() < job.getMaxAttempts()) {
                job.setStatus(ReportExportStatus.QUEUED); job.setStartedAt(null);
                job.setNextAttemptAt(LocalDateTime.now().plus(Duration.ofSeconds(retryDelaySeconds * job.getAttemptCount())));
                metrics.increment("automatic_retry");
            } else {
                job.setStatus(ReportExportStatus.FAILED); job.setCompletedAt(LocalDateTime.now());
                metrics.increment("failed");
            }
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
        repository.save(job);
    }

    private void deleteStoredFile(ReportExportJob job) {
        if (job.getStorageKey() != null) storage.delete(job.getStorageKey());
        else if (job.getFilePath() != null) {
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(job.getFilePath()));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Could not delete report export", error);
            }
        }
    }

    public void validateRequest(ReportExportJobRequest request) {
        if (request == null || request.reportType() == null) throw new BadRequestException("reportType is required");
        String type = request.reportType().trim().toUpperCase();
        if (!type.matches("SALES|PRODUCT|CUSTOMER|SUPPLIER")) throw new BadRequestException("Invalid export reportType: " + request.reportType());
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) throw new BadRequestException("from must be on or before to");
        if (request.emailTo() != null && !request.emailTo().isBlank() && !request.emailTo().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new BadRequestException("Invalid emailTo");
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public ReportExportJobResponse toResponse(ReportExportJob job) {
        return new ReportExportJobResponse(job.getId(), job.getReportType(), job.getStatus(), job.getFileName(), job.getFileSize(),
                job.getErrorMessage(), job.getEmailTo(), job.getEmailDeliveredAt(), job.getAttemptCount(), job.getMaxAttempts(),
                job.getNextAttemptAt(), job.getScheduleId(), job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt(),
                job.getStatus() == ReportExportStatus.COMPLETED && (job.getStorageKey() != null || job.getFilePath() != null));
    }
}
