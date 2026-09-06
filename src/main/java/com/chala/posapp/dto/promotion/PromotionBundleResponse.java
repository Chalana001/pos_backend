package com.chala.posapp.dto.promotion;

import com.chala.posapp.promotion.engine.PromotionSnapshot;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything an offline till needs to price a cart the way the server would.
 *
 * <p>The promotions are the same snapshots the server's own engine evaluates, so the till runs
 * the same rules, not a summary of them. Code-gated promotions are left out — a code cannot be
 * validated or consumed without the server — and so are promotions already at their cap.
 * {@code version} is a hash of the contents; a sale made against it carries the version back
 * on import, so a stale bundle is visible rather than silently corrected.
 */
@Data
@Builder
public class PromotionBundleResponse {
    private String version;
    private LocalDateTime generatedAt;
    private Long branchId;
    private List<PromotionSnapshot> promotions;
    /** Names of promotions withheld from the till because they need a code. */
    private List<String> onlineOnly;
}
