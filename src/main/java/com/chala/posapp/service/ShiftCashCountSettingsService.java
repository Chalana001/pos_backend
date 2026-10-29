package com.chala.posapp.service;

import com.chala.posapp.dto.shift.ShiftCashCountSettingsRequest;
import com.chala.posapp.dto.shift.ShiftCashCountSettingsResponse;
import com.chala.posapp.entity.ShiftCashCountSettings;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.ShiftCashCountSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftCashCountSettingsService {
    private final ShiftCashCountSettingsRepository repository;

    public ShiftCashCountSettingsResponse getSettings() {
        return toResponse(repository.findById(ShiftCashCountSettings.SINGLETON_ID)
                .orElseGet(ShiftCashCountSettings::new));
    }

    @Transactional
    public ShiftCashCountSettingsResponse updateSettings(ShiftCashCountSettingsRequest request) {
        if (request.getDenominations() == null || request.getDenominations().isEmpty()) {
            throw new BadRequestException("Add at least one cash denomination");
        }
        if (request.getDenominations().size() > 30) {
            throw new BadRequestException("A maximum of 30 denominations is allowed");
        }
        TreeSet<Integer> sorted = new TreeSet<>((a, b) -> Integer.compare(b, a));
        for (Integer denomination : request.getDenominations()) {
            if (denomination == null || denomination <= 0 || denomination > 1_000_000) {
                throw new BadRequestException("Denominations must be whole amounts between 1 and 1,000,000");
            }
            if (!sorted.add(denomination)) {
                throw new BadRequestException("Cash denominations must be unique");
            }
        }

        ShiftCashCountSettings settings = repository.findById(ShiftCashCountSettings.SINGLETON_ID)
                .orElseGet(this::createDefault);
        settings.setEnabled(request.isEnabled());
        settings.setDenominationsCsv(String.join(",", sorted.stream().map(String::valueOf).toList()));
        settings.setUpdatedAt(LocalDateTime.now());
        return toResponse(repository.save(settings));
    }

    private ShiftCashCountSettings createDefault() {
        return repository.save(new ShiftCashCountSettings());
    }

    private ShiftCashCountSettingsResponse toResponse(ShiftCashCountSettings settings) {
        List<Integer> denominations = Arrays.stream(settings.getDenominationsCsv().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .sorted((a, b) -> Integer.compare(b, a))
                .toList();
        return ShiftCashCountSettingsResponse.builder()
                .enabled(settings.isEnabled())
                .denominations(denominations)
                .build();
    }
}
