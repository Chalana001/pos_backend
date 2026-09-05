package com.chala.posapp.service;

import com.chala.posapp.config.CacheConfig;
import com.chala.posapp.promotion.engine.PromotionSnapshot;
import com.chala.posapp.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The set of promotions that could price a sale, cached per tenant.
 *
 * <p>Checkout and every cart preview used to hit the database for this list, with its targets,
 * on every call — a cart of twenty lines being edited fired twenty of them. The set only changes
 * when someone edits a promotion, and every write path in {@code PromotionService} evicts.
 *
 * <p>Date window and branch are <em>not</em> part of what is cached. A list of "what is live
 * right now" is stale the moment a start date passes; the caller filters both in memory, which
 * on a list this size is free.
 *
 * <p>A separate bean rather than a method on {@code PromotionService} because {@code @Cacheable}
 * is applied by a proxy: a bean calling its own annotated method bypasses it, and
 * {@code preview()} would have gone straight to the database every time.
 *
 * <p>Snapshots, not entities. A cached entity is detached, and the first request to touch an
 * association it did not load would throw. Snapshots are plain values and cannot.
 */
@Component
@RequiredArgsConstructor
public class PromotionSnapshotCache {

    private final PromotionRepository promotionRepository;

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.CACHE_ACTIVE_PROMOTIONS,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key('candidates')")
    public List<PromotionSnapshot> candidates() {
        return promotionRepository.findByActiveTrueAndDeletedAtIsNullOrderByPriorityDescIdDesc().stream()
                .map(PromotionSnapshot::from)
                .toList();
    }

    /** Call after any promotion write. Tenant-scoped: another shop's cache is left alone. */
    @CacheEvict(value = CacheConfig.CACHE_ACTIVE_PROMOTIONS,
                key = "T(com.chala.posapp.util.CacheKeyUtils).key('candidates')")
    public void evict() {
        // eviction only
    }
}
