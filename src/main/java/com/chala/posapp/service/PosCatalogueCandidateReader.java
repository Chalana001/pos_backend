package com.chala.posapp.service;

import com.chala.posapp.entity.Item;
import com.chala.posapp.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Tenant-local reader for the complete global item candidate set used by the POS catalogue.
 *
 * <p>The reader deliberately stops at repository candidate retrieval. Active/POS-visible
 * checks, item-type configuration, branch service assignment, stock history, and the
 * recipe/service exceptions belong to the future catalogue assembler and are not applied
 * here. The repository query is global to the current tenant and does not bind a branch or
 * load stock, recipe, processing, configuration, or plan data.</p>
 *
 * <p>Items are returned as managed entities because the assembler needs the complete scalar
 * Item contract already consumed by {@code ItemService.mapToResponse}. The repository entity
 * graph initializes only {@code subCategory} and {@code subCategory.category}; no collection
 * relationship is fetched. This preserves the legacy null behavior for optional category
 * relationships while preventing category mapping from issuing per-item lazy queries.</p>
 */
@Service
@RequiredArgsConstructor
public class PosCatalogueCandidateReader {

    private final ItemRepository itemRepository;

    /**
     * Loads every non-soft-deleted item in the current tenant's item master.
     *
     * <p>No ordering or pagination is imposed. The result therefore preserves the database
     * order exposed by the repository, matching the absence of a top-level ordering guarantee
     * in the legacy empty-name POS candidate query.</p>
     */
    public List<Item> loadCandidates() {
        return itemRepository.findAllPosCatalogueCandidates();
    }
}
