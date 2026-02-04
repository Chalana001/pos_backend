package com.chala.posapp.service;

import com.chala.posapp.entity.CustomerNote;
import com.chala.posapp.repository.CustomerNoteRepository;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.dto.CustomerNoteCreateRequest;
import com.chala.posapp.dto.CustomerNoteResponse;
import com.chala.posapp.dto.CustomerNoteUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerNoteService {

    private final CustomerNoteRepository noteRepository;
    private final CustomerRepository customerRepository;

    public Page<CustomerNoteResponse> list(Long customerId, Pageable pageable) {

        customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        return noteRepository.findByCustomerId(customerId, pageable)
                .map(this::map);
    }

    public CustomerNoteResponse create(Long customerId, CustomerNoteCreateRequest request) {
        customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        CustomerNote note = CustomerNote.builder()
                .customerId(customerId)
                .note(request.getNote().trim())
                .pinned(false)
                .createdBy(null)
                .build();

        return map(noteRepository.save(note));
    }

    public CustomerNoteResponse update(Long noteId, CustomerNoteUpdateRequest request) {
        CustomerNote note = noteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));

        note.setNote(request.getNote().trim());
        return map(noteRepository.save(note));
    }

    public void delete(Long noteId) {
        if (!noteRepository.existsById(noteId))
            throw new RuntimeException("Note not found");
        noteRepository.deleteById(noteId);
    }

    private CustomerNoteResponse map(CustomerNote n) {
        return CustomerNoteResponse.builder()
                .id(n.getId())
                .customerId(n.getCustomerId())
                .note(n.getNote())
                .pinned(n.isPinned())
                .createdBy(n.getCreatedBy())
                .createdAt(n.getCreatedAt())
                .updatedAt(n.getUpdatedAt())
                .build();
    }
}
