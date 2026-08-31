package com.chala.posapp.service;

import com.chala.posapp.dto.report.*;
import com.chala.posapp.entity.*;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ReportScheduleRepository;
import com.chala.posapp.util.SecurityUtils;
import com.chala.posapp.util.StorageClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportScheduleService {
    private final ReportScheduleRepository repository;
    private final ReportExportJobService exportJobService;
    private final SecurityUtils securityUtils;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReportScheduleResponse create(ReportScheduleRequest request) {
        if (request == null || request.report() == null || request.frequency() == null || request.nextRunAt() == null) {
            throw new BadRequestException("report, frequency and nextRunAt are required");
        }
        if (request.emailTo() != null && !request.emailTo().isBlank()
                && !request.emailTo().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BadRequestException("Invalid emailTo");
        }
        exportJobService.validateRequest(request.report());
        User user = securityUtils.getCurrentUser();
        try {
            return toResponse(repository.save(ReportSchedule.builder()
                    .requestedByUserId(user.getId()).requestedByUsername(user.getUsername())
                    .reportType(request.report().reportType().trim().toUpperCase())
                    .parametersJson(objectMapper.writeValueAsString(request.report()))
                    .frequency(request.frequency()).emailTo(request.emailTo())
                    .enabled(true).nextRunAt(StorageClock.toStorage(request.nextRunAt())).build()));
        } catch (JsonProcessingException error) {
            throw new BadRequestException("Invalid schedule parameters");
        }
    }

    @Transactional(readOnly = true)
    public List<ReportScheduleResponse> list() {
        return repository.findByRequestedByUserIdOrderByCreatedAtDesc(securityUtils.getCurrentUser().getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public ReportScheduleResponse setEnabled(Long id, boolean enabled) {
        ReportSchedule schedule = owned(id);
        schedule.setEnabled(enabled);
        return toResponse(repository.save(schedule));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(owned(id));
    }

    @Transactional
    public void enqueueDueSchedules() {
        LocalDateTime now = StorageClock.now();
        for (ReportSchedule schedule : repository.findTop20ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now)) {
            exportJobService.createScheduled(schedule);
            schedule.setLastRunAt(now);
            schedule.setNextRunAt(nextRun(schedule.getFrequency(), schedule.getNextRunAt(), now));
            repository.save(schedule);
        }
    }

    private LocalDateTime nextRun(ReportScheduleFrequency frequency, LocalDateTime previous, LocalDateTime now) {
        LocalDateTime next = switch (frequency) {
            case DAILY -> previous.plusDays(1);
            case WEEKLY -> previous.plusWeeks(1);
            case MONTHLY -> previous.plusMonths(1);
        };
        while (!next.isAfter(now)) next = switch (frequency) {
            case DAILY -> next.plusDays(1);
            case WEEKLY -> next.plusWeeks(1);
            case MONTHLY -> next.plusMonths(1);
        };
        return next;
    }

    private ReportSchedule owned(Long id) {
        return repository.findByIdAndRequestedByUserId(id, securityUtils.getCurrentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Report schedule not found"));
    }

    private ReportScheduleResponse toResponse(ReportSchedule schedule) {
        return new ReportScheduleResponse(schedule.getId(), schedule.getReportType(), schedule.getFrequency(),
                schedule.getEmailTo(), schedule.isEnabled(), schedule.getNextRunAt(), schedule.getLastRunAt(), schedule.getCreatedAt());
    }
}
