package com.chala.posapp.entity;

/**
 * The states of a promotion that need a human decision. Whether an ACTIVE promotion is live
 * right now, scheduled, ended or exhausted is derived from its dates and counters, not stored.
 */
public enum PromotionStatus {
    /** Being written; the engine never sees it. */
    DRAFT,
    /** Terms exceed the shop's approval threshold; waiting for a second admin. */
    PENDING_APPROVAL,
    /** Switched on. Live, scheduled, ended or exhausted depending on dates and counters. */
    ACTIVE,
    /** Switched off by a person; can be resumed. */
    PAUSED
}
