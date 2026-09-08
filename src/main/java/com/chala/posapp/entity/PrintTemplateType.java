package com.chala.posapp.entity;

public enum PrintTemplateType {
    THERMAL,
    A4,
    KOT,
    /**
     * The slip a return prints. Its own type rather than a flag on THERMAL: a shop laying out
     * its sale receipt is not laying out its return receipt, and the two share almost no lines.
     * Settings are keyed (branch, template type), so this needs no schema change.
     */
    RETURN
}
