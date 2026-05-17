package com.chala.posapp.service;

import com.chala.posapp.dto.warranty.WarrantyTemplateRequest;
import com.chala.posapp.dto.warranty.WarrantyTemplateResponse;
import com.chala.posapp.entity.WarrantyTemplate;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.WarrantyTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarrantyTemplateService {

    private final WarrantyTemplateRepository warrantyTemplateRepository;

    public List<WarrantyTemplateResponse> listAll() {
        return warrantyTemplateRepository.findAllByOrderByPeriodValueAscCreatedAtAsc()
                .stream()
                .map(this::map)
                .toList();
    }

    public List<WarrantyTemplateResponse> listActive() {
        return warrantyTemplateRepository.findByActiveTrueOrderByPeriodValueAscCreatedAtAsc()
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional
    public WarrantyTemplateResponse create(WarrantyTemplateRequest request) {
        String label = normalizeLabel(request.getLabel());
        warrantyTemplateRepository.findByLabelIgnoreCase(label).ifPresent(existing -> {
            throw new AlreadyExistsException("Warranty template already exists");
        });

        WarrantyTemplate template = warrantyTemplateRepository.save(WarrantyTemplate.builder()
                .label(label)
                .periodValue(request.getPeriodValue())
                .periodUnit(request.getPeriodUnit())
                .active(request.isActive())
                .build());
        return map(template);
    }

    @Transactional
    public WarrantyTemplateResponse update(Long id, WarrantyTemplateRequest request) {
        WarrantyTemplate template = getEntity(id);
        String label = normalizeLabel(request.getLabel());
        warrantyTemplateRepository.findByLabelIgnoreCase(label)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new AlreadyExistsException("Warranty template already exists");
                });

        template.setLabel(label);
        template.setPeriodValue(request.getPeriodValue());
        template.setPeriodUnit(request.getPeriodUnit());
        template.setActive(request.isActive());
        return map(warrantyTemplateRepository.save(template));
    }

    @Transactional
    public void delete(Long id) {
        warrantyTemplateRepository.delete(getEntity(id));
    }

    private WarrantyTemplate getEntity(Long id) {
        return warrantyTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warranty template not found"));
    }

    private String normalizeLabel(String label) {
        return label == null ? "" : label.trim();
    }

    private WarrantyTemplateResponse map(WarrantyTemplate template) {
        return WarrantyTemplateResponse.builder()
                .id(template.getId())
                .label(template.getLabel())
                .periodValue(template.getPeriodValue())
                .periodUnit(template.getPeriodUnit())
                .active(template.isActive())
                .createdAt(template.getCreatedAt())
                .build();
    }
}
