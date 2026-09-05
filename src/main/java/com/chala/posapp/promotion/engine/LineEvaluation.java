package com.chala.posapp.promotion.engine;

import java.util.List;

/** A priced line plus the reasoning that produced it. */
public record LineEvaluation(PromotionApplication application, List<LineDecision> decisions) {
}
