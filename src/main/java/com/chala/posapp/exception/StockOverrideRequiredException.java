package com.chala.posapp.exception;

import com.chala.posapp.dto.order.StockShortageIssue;
import lombok.Getter;

import java.util.List;

@Getter
public class StockOverrideRequiredException extends RuntimeException {

    private final List<StockShortageIssue> shortages;
    private final boolean overrideAvailable;

    public StockOverrideRequiredException(String message, List<StockShortageIssue> shortages, boolean overrideAvailable) {
        super(message);
        this.shortages = shortages;
        this.overrideAvailable = overrideAvailable;
    }
}
