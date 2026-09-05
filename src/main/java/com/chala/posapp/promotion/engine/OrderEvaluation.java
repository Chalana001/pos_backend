package com.chala.posapp.promotion.engine;

import java.util.List;

/** A priced bill plus the reasoning that produced it. */
public record OrderEvaluation(PromotionOrderApplication application, List<LineDecision> decisions) {
}
