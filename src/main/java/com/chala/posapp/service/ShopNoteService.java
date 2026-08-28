package com.chala.posapp.service;

import com.chala.posapp.dto.saas.support.SupportDtos.NoteRequest;
import com.chala.posapp.dto.saas.support.SupportDtos.NoteResponse;
import com.chala.posapp.entity.ShopNote;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ShopNoteRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Support notes against a shop.
 *
 * <p>Notes are editable and deletable by design — they record a conversation, and a
 * conversation gets corrected. The permanent record of what the platform actually did lives in
 * the audit trail, which nothing here can touch.
 */
@Service
@RequiredArgsConstructor
public class ShopNoteService {

    private static final Set<String> CATEGORIES =
            Set.of("GENERAL", "BILLING", "TECHNICAL", "COMPLAINT", "FOLLOW_UP");

    private final ShopNoteRepository noteRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public List<NoteResponse> list(String tenantId) {
        return noteRepository.findByTenantIdOrderByPinnedDescCreatedAtDesc(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countsByTenant() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : noteRepository.countGroupedByTenant()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Transactional
    public NoteResponse create(String tenantId, NoteRequest request) {
        if (!tenantSubscriptionRepository.existsByTenantId(tenantId)) {
            throw new ResourceNotFoundException("Shop not found: " + tenantId);
        }
        ShopNote note = ShopNote.builder()
                .tenantId(tenantId)
                .body(request.body().trim())
                .category(normaliseCategory(request.category()))
                .pinned(Boolean.TRUE.equals(request.pinned()))
                .author(currentActor())
                .build();
        return toResponse(noteRepository.save(note));
    }

    @Transactional
    public NoteResponse update(String tenantId, Long noteId, NoteRequest request) {
        ShopNote note = require(tenantId, noteId);
        note.setBody(request.body().trim());
        note.setCategory(normaliseCategory(request.category()));
        if (request.pinned() != null) {
            note.setPinned(request.pinned());
        }
        return toResponse(noteRepository.save(note));
    }

    @Transactional
    public void delete(String tenantId, Long noteId) {
        noteRepository.delete(require(tenantId, noteId));
    }

    private ShopNote require(String tenantId, Long noteId) {
        ShopNote note = noteRepository.findById(noteId)
                .orElseThrow(() -> new ResourceNotFoundException("Note not found"));
        // Guards against a note id from one shop being edited through another shop's URL.
        if (!note.getTenantId().equals(tenantId)) {
            throw new BadRequestException("That note belongs to a different shop.");
        }
        return note;
    }

    private String normaliseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "GENERAL";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(upper) ? upper : "GENERAL";
    }

    private NoteResponse toResponse(ShopNote note) {
        return new NoteResponse(
                note.getId(), note.getTenantId(), note.getBody(), note.getCategory(),
                note.isPinned(), note.getAuthor(), note.getCreatedAt(), note.getUpdatedAt());
    }

    private String currentActor() {
        try {
            return authService.getLoggedUser().getUsername();
        } catch (Exception exception) {
            return "system";
        }
    }
}
