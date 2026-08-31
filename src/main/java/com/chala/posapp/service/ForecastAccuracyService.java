package com.chala.posapp.service;

import com.chala.posapp.dto.report.DemandForecastResponse;
import com.chala.posapp.dto.report.ForecastAccuracyResponse;
import com.chala.posapp.entity.*;
import com.chala.posapp.repository.ForecastSnapshotRepository;
import com.chala.posapp.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class ForecastAccuracyService {
    private final ForecastSnapshotRepository repository;
    private final ReportRepository reportRepository;
    @Value("${app.report-exports.forecast-snapshot-retention-days:365}") private long retentionDays;

    @Transactional
    public void capture(ReportExportJob job, Long branchId, int forecastDays, DemandForecastResponse forecast) {
        if (repository.existsByExportJobId(job.getId())) return;
        LocalDateTime start = LocalDateTime.now();
        ForecastSnapshot snapshot = ForecastSnapshot.builder().exportJobId(job.getId()).branchId(branchId)
                .forecastDays(forecastDays).windowStart(start).windowEnd(start.plusDays(forecastDays)).build();
        for (DemandForecastResponse.ItemForecast item : forecast.getItems()) {
            ForecastSnapshotItem row = ForecastSnapshotItem.builder().snapshot(snapshot).itemId(item.getItemId())
                    .itemName(item.getItemName()).unit(item.getUnit()).predictedQty(decimal(item.getProjectedDemand()))
                    .confidence(item.getConfidence()).scored(false).build();
            snapshot.getItems().add(row);
        }
        try { repository.saveAndFlush(snapshot); }
        catch (DataIntegrityViolationException duplicate) {
            if (!repository.existsByExportJobId(job.getId())) throw duplicate;
        }
    }

    @Transactional
    public void evaluateMatured() {
        for (ForecastSnapshot snapshot : repository.findTop20ByEvaluatedAtIsNullAndWindowEndLessThanEqualOrderByWindowEndAsc(LocalDateTime.now())) {
            long branch = snapshot.getBranchId() == null ? 0 : snapshot.getBranchId();
            Map<Long, BigDecimal> actual = new HashMap<>();
            for (Object[] row : reportRepository.itemDemandBetweenRaw(branch, snapshot.getWindowStart(), snapshot.getWindowEnd())) {
                actual.put(((Number) row[0]).longValue(), decimal(((Number) row[1]).doubleValue()));
            }
            for (ForecastSnapshotItem item : snapshot.getItems()) {
                BigDecimal actualQty = actual.getOrDefault(item.getItemId(), BigDecimal.ZERO);
                item.setActualQty(actualQty);
                item.setAbsoluteError(item.getPredictedQty().subtract(actualQty).abs());
                item.setScored(!"INSUFFICIENT".equals(item.getConfidence()) && actualQty.signum() > 0);
            }
            snapshot.setEvaluatedAt(LocalDateTime.now());
            repository.save(snapshot);
        }
    }

    @Transactional(readOnly = true)
    public ForecastAccuracyResponse summary() {
        List<ForecastSnapshot> allEvaluated = repository.findByEvaluatedAtIsNotNull();
        List<ForecastAccuracyResponse.SnapshotAccuracy> allAccuracy = allEvaluated.stream().map(this::toAccuracy).toList();
        List<ForecastAccuracyResponse.SnapshotAccuracy> history = repository.findTop20ByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc().stream().map(this::toAccuracy).toList();
        double actual = allAccuracy.stream().mapToDouble(ForecastAccuracyResponse.SnapshotAccuracy::actualQty).sum();
        double error = allAccuracy.stream().mapToDouble(ForecastAccuracyResponse.SnapshotAccuracy::absoluteError).sum();
        return new ForecastAccuracyResponse(Math.toIntExact(repository.countByEvaluatedAtIsNotNull()),
                Math.toIntExact(repository.countByEvaluatedAtIsNullAndWindowEndAfter(LocalDateTime.now())),
                actual > 0 ? error / actual * 100 : null, history);
    }

    @Transactional
    public void cleanup() { repository.deleteAll(repository.findByCreatedAtBefore(LocalDateTime.now().minusDays(retentionDays))); }

    private ForecastAccuracyResponse.SnapshotAccuracy toAccuracy(ForecastSnapshot snapshot) {
        List<ForecastSnapshotItem> scored = snapshot.getItems().stream().filter(ForecastSnapshotItem::isScored).toList();
        double predicted = scored.stream().map(ForecastSnapshotItem::getPredictedQty).mapToDouble(BigDecimal::doubleValue).sum();
        double actual = scored.stream().map(ForecastSnapshotItem::getActualQty).mapToDouble(BigDecimal::doubleValue).sum();
        double error = scored.stream().map(ForecastSnapshotItem::getAbsoluteError).mapToDouble(BigDecimal::doubleValue).sum();
        return new ForecastAccuracyResponse.SnapshotAccuracy(snapshot.getId(), snapshot.getExportJobId(), snapshot.getBranchId(), snapshot.getForecastDays(),
                snapshot.getWindowStart(), snapshot.getWindowEnd(), snapshot.getEvaluatedAt(), scored.size(), snapshot.getItems().size(),
                predicted, actual, error, actual > 0 ? error / actual * 100 : null);
    }

    private BigDecimal decimal(double value) { return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP); }
}
