package com.chala.posapp.dto.item;

import java.util.List;

/** Paged promotion item results; cursor metadata is also present for typed searches. */
public record PromotionItemSearchPage(
        List<PromotionItemSearchResponse> items,
        Long nextCursor,
        boolean hasMore) {
}
