package com.chala.posapp.service;

import com.chala.posapp.dto.loyalty.LoyaltyAccountDto;
import com.chala.posapp.dto.loyalty.LoyaltySettingsDto;
import com.chala.posapp.dto.loyalty.LoyaltyTierDto;
import com.chala.posapp.dto.loyalty.LoyaltyTransactionDto;
import com.chala.posapp.entity.LoyaltyAccount;
import com.chala.posapp.entity.LoyaltySettings;
import com.chala.posapp.entity.LoyaltyTier;
import com.chala.posapp.entity.LoyaltyTransaction;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.LoyaltyAccountRepository;
import com.chala.posapp.repository.LoyaltySettingsRepository;
import com.chala.posapp.repository.LoyaltyTierRepository;
import com.chala.posapp.repository.LoyaltyTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Points earned on a sale and spent against a later one.
 *
 * <p>Spending points is not a promotion and is deliberately not routed through the pricing
 * engine. A promotion decides a price from rules about the cart; points are a balance the
 * customer already owns, more like part-payment than a discount. Mixing the two would mean the
 * engine returning a "discount" that depends on who is standing at the counter and how much
 * they have saved up, which is not a rule anybody could preview or simulate.
 *
 * <p>So points are applied after promotions, against the bill they leave behind, and the ledger
 * that records them is separate from the promotion redemption ledger.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyService {

    private final LoyaltySettingsRepository settingsRepository;
    private final LoyaltyTierRepository tierRepository;
    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;

    /** What a redemption is worth, and what it is allowed to be. */
    public record Redemption(int points, BigDecimal amount) {
        public static final Redemption NONE = new Redemption(0, BigDecimal.ZERO);
        public boolean isNothing() { return points <= 0 || amount.signum() <= 0; }
    }

    /**
     * What a sale did to a balance, and what it left behind.
     *
     * <p>The balance is returned rather than looked up again later because the receipt has to
     * print the balance <em>as at this sale</em>. Read from the account at print time, a
     * reprint a month later would show today's figure beside a month-old invoice.
     */
    public record SaleOutcome(int earned, int balanceAfter) {
        public static final SaleOutcome NONE = new SaleOutcome(0, 0);
    }

    // ── settings and tiers ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LoyaltySettings settings() {
        return settingsRepository.findFirstByOrderByIdAsc().orElseGet(LoyaltySettings::defaults);
    }

    @Transactional(readOnly = true)
    public LoyaltySettingsDto settingsDto() {
        return LoyaltySettingsDto.from(settings());
    }

    @Transactional
    public LoyaltySettingsDto updateSettings(LoyaltySettingsDto request, User user) {
        if (request.getPointsPerCurrency() == null || request.getPointsPerCurrency().signum() < 0) {
            throw new BadRequestException("Points earned per unit of currency cannot be negative");
        }
        if (request.getCurrencyPerPoint() == null || request.getCurrencyPerPoint().signum() < 0) {
            throw new BadRequestException("The value of a point cannot be negative");
        }
        if (request.getMaxRedemptionPercent() != null
                && (request.getMaxRedemptionPercent().signum() < 0 || request.getMaxRedemptionPercent().doubleValue() > 100)) {
            throw new BadRequestException("Maximum redemption must be between 0 and 100 percent");
        }
        LoyaltySettings settings = settingsRepository.findFirstByOrderByIdAsc().orElseGet(LoyaltySettings::defaults);
        settings.setEnabled(request.isEnabled());
        settings.setPointsPerCurrency(request.getPointsPerCurrency());
        settings.setCurrencyPerPoint(request.getCurrencyPerPoint());
        settings.setMinRedemptionPoints(Math.max(0, request.getMinRedemptionPoints()));
        settings.setMaxRedemptionPercent(request.getMaxRedemptionPercent());
        settings.setRoundEarnedDown(request.isRoundEarnedDown());
        settings.setUpdatedBy(user == null ? null : user.getId());
        settings.setUpdatedAt(LocalDateTime.now());
        return LoyaltySettingsDto.from(settingsRepository.save(settings));
    }

    @Transactional(readOnly = true)
    public List<LoyaltyTierDto> tiers() {
        return tierRepository.findAllByOrderByMinLifetimePointsAsc().stream().map(LoyaltyTierDto::from).toList();
    }

    @Transactional
    public LoyaltyTierDto saveTier(Long id, LoyaltyTierDto request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Tier name is required");
        }
        if (request.getEarnMultiplier() == null || request.getEarnMultiplier().signum() <= 0) {
            throw new BadRequestException("Earn multiplier must be greater than 0");
        }
        LoyaltyTier tier = id == null
                ? new LoyaltyTier()
                : tierRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tier not found"));
        tier.setName(request.getName().trim());
        tier.setMinLifetimePoints(Math.max(0, request.getMinLifetimePoints()));
        tier.setEarnMultiplier(request.getEarnMultiplier());
        tier.setSortOrder(request.getSortOrder());
        tier.setActive(request.isActive());
        return LoyaltyTierDto.from(tierRepository.save(tier));
    }

    @Transactional
    public void deleteTier(Long id) {
        tierRepository.delete(tierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tier not found")));
    }

    // ── a customer's account ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LoyaltyAccountDto account(Long customerId) {
        LoyaltyAccount account = accountRepository.findByCustomerId(customerId).orElse(null);
        LoyaltySettings settings = settings();
        if (account == null) {
            return LoyaltyAccountDto.builder()
                    .customerId(customerId).pointsBalance(0).lifetimePoints(0)
                    .pointsValue(BigDecimal.ZERO).enabled(settings.isEnabled())
                    .minRedemptionPoints(settings.getMinRedemptionPoints())
                    .build();
        }
        LoyaltyTier tier = account.getTierId() == null ? null
                : tierRepository.findById(account.getTierId()).orElse(null);
        return LoyaltyAccountDto.builder()
                .customerId(customerId)
                .pointsBalance(account.getPointsBalance())
                .lifetimePoints(account.getLifetimePoints())
                .tierName(tier == null ? null : tier.getName())
                .earnMultiplier(tier == null ? BigDecimal.ONE : tier.getEarnMultiplier())
                .pointsValue(valueOf(account.getPointsBalance(), settings))
                .enabled(settings.isEnabled())
                .minRedemptionPoints(settings.getMinRedemptionPoints())
                .build();
    }

    @Transactional(readOnly = true)
    public List<LoyaltyTransactionDto> history(Long customerId) {
        return transactionRepository.findByCustomerIdOrderByAtDescIdDesc(customerId).stream()
                .map(LoyaltyTransactionDto::from)
                .toList();
    }

    // ── checkout ────────────────────────────────────────────────────────────────────────

    /**
     * What a requested redemption is actually worth on this bill, or {@link Redemption#NONE}.
     *
     * <p>Refuses rather than silently trimming when the customer does not have the points: a
     * cashier who typed 500 and gets 300 taken off has a conversation to have. Everything else
     * — the scheme's minimum, its ceiling on how much of a bill points may cover, and the bill
     * itself — clamps quietly, because those are the shop's own limits and not a mistake.
     */
    @Transactional(readOnly = true)
    public Redemption quoteRedemption(Long customerId, int requestedPoints, double billTotal) {
        LoyaltySettings settings = settings();
        if (!settings.isEnabled() || requestedPoints <= 0 || customerId == null || customerId <= 0) {
            return Redemption.NONE;
        }
        if (requestedPoints < settings.getMinRedemptionPoints()) {
            throw new BadRequestException("At least " + settings.getMinRedemptionPoints() + " points are needed to redeem");
        }
        LoyaltyAccount account = accountRepository.findByCustomerId(customerId).orElse(null);
        int balance = account == null ? 0 : account.getPointsBalance();
        if (requestedPoints > balance) {
            throw new BadRequestException("This customer has " + balance + " points, not " + requestedPoints);
        }

        BigDecimal ceiling = BigDecimal.valueOf(Math.max(0, billTotal));
        if (settings.getMaxRedemptionPercent() != null) {
            ceiling = ceiling.multiply(settings.getMaxRedemptionPercent()).movePointLeft(2);
        }
        BigDecimal value = valueOf(requestedPoints, settings).min(ceiling).setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            return Redemption.NONE;
        }
        // Charge only the points the capped amount actually used, rounded up so the shop never
        // gives away a fraction of a point's worth for free.
        int pointsUsed = settings.getCurrencyPerPoint().signum() <= 0
                ? requestedPoints
                : Math.min(requestedPoints,
                        value.divide(settings.getCurrencyPerPoint(), 0, RoundingMode.CEILING).intValue());
        return new Redemption(pointsUsed, value);
    }

    /**
     * Books what a completed sale did to a customer's points: spends what was redeemed, earns on
     * what they actually paid.
     *
     * <p>{@code MANDATORY} — this must run inside the order transaction, so a sale that fails
     * later never leaves points spent or awarded.
     *
     * <p>Earning is on the amount paid after every discount, points included. Earning on the
     * pre-discount total would pay points on money that never changed hands, and compound with
     * every promotion the shop runs.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SaleOutcome applyToSale(Long customerId, Long orderId, Long userId, int redeemedPoints, double paidTotal) {
        LoyaltySettings settings = settings();
        if (!settings.isEnabled() || customerId == null || customerId <= 0) {
            return SaleOutcome.NONE;
        }
        LoyaltyAccount account = accountRepository.findByCustomerId(customerId)
                .orElseGet(() -> LoyaltyAccount.builder()
                        .customerId(customerId).pointsBalance(0).lifetimePoints(0)
                        .updatedAt(LocalDateTime.now()).build());
        LocalDateTime now = LocalDateTime.now();

        if (redeemedPoints > 0) {
            if (redeemedPoints > account.getPointsBalance()) {
                throw new BadRequestException("This customer no longer has " + redeemedPoints + " points");
            }
            account.setPointsBalance(account.getPointsBalance() - redeemedPoints);
            transactionRepository.save(LoyaltyTransaction.builder()
                    .customerId(customerId).orderId(orderId).type(LoyaltyTransaction.Type.REDEEM)
                    .points(-redeemedPoints).balanceAfter(account.getPointsBalance())
                    .userId(userId).at(now).build());
        }

        int earned = pointsFor(paidTotal, account, settings);
        if (earned > 0) {
            account.setPointsBalance(account.getPointsBalance() + earned);
            account.setLifetimePoints(account.getLifetimePoints() + earned);
            transactionRepository.save(LoyaltyTransaction.builder()
                    .customerId(customerId).orderId(orderId).type(LoyaltyTransaction.Type.EARN)
                    .points(earned).balanceAfter(account.getPointsBalance())
                    .userId(userId).at(now).build());
        }

        account.setTierId(tierFor(account.getLifetimePoints()));
        account.setUpdatedAt(now);
        accountRepository.save(account);
        return new SaleOutcome(earned, account.getPointsBalance());
    }

    /**
     * Undoes a cancelled sale: points earned on it are taken back, points spent on it are
     * returned. The original rows stay and are marked reversed, with a REVERSAL row recording
     * the movement — a balance history that edits itself is not a history.
     *
     * <p>A balance can go negative here, and is left to: the customer earned points and spent
     * them before the sale was cancelled. Clamping at zero would silently hand them the
     * difference.
     */
    @Transactional
    public void reverseForOrder(Long orderId, Long userId) {
        List<LoyaltyTransaction> rows = transactionRepository.findByOrderIdAndReversedAtIsNull(orderId);
        if (rows.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (LoyaltyTransaction row : rows) {
            LoyaltyAccount account = accountRepository.findByCustomerId(row.getCustomerId()).orElse(null);
            if (account == null) {
                continue;
            }
            account.setPointsBalance(account.getPointsBalance() - row.getPoints());
            if (row.getType() == LoyaltyTransaction.Type.EARN) {
                account.setLifetimePoints(Math.max(0, account.getLifetimePoints() - row.getPoints()));
                account.setTierId(tierFor(account.getLifetimePoints()));
            }
            account.setUpdatedAt(now);
            accountRepository.save(account);

            row.setReversedAt(now);
            transactionRepository.save(row);
            transactionRepository.save(LoyaltyTransaction.builder()
                    .customerId(row.getCustomerId()).orderId(orderId).type(LoyaltyTransaction.Type.REVERSAL)
                    .points(-row.getPoints()).balanceAfter(account.getPointsBalance())
                    .note("Sale cancelled").userId(userId).at(now).build());
        }
        log.info("Reversed {} loyalty movement(s) on order {}", rows.size(), orderId);
    }

    /**
     * Takes back the points a partial return undid.
     *
     * <p>Only points <em>earned</em> on the sale, in proportion to the value returned. Points the
     * customer <em>spent</em> are left alone: they paid for goods, and the customer is keeping
     * some of them. Returning spent points as well as refunding the money would pay the return
     * twice. A full return is not this path — the caller sends it to {@link #reverseForOrder},
     * which undoes the sale entirely.
     *
     * <p>The balance may go negative, and is left to: the customer may already have spent what
     * this sale earned them, and clamping at zero would quietly hand them the difference.
     */
    @Transactional
    public void clawBackForReturn(Long orderId, Long userId, BigDecimal share) {
        if (share == null || share.signum() <= 0) {
            return;
        }
        List<LoyaltyTransaction> earned = transactionRepository.findByOrderIdAndReversedAtIsNull(orderId).stream()
                .filter(row -> row.getType() == LoyaltyTransaction.Type.EARN)
                .toList();
        if (earned.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (LoyaltyTransaction row : earned) {
            int clawBack = BigDecimal.valueOf(row.getPoints())
                    .multiply(share.min(BigDecimal.ONE))
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValue();
            if (clawBack <= 0) {
                continue;
            }
            LoyaltyAccount account = accountRepository.findByCustomerId(row.getCustomerId()).orElse(null);
            if (account == null) {
                continue;
            }
            account.setPointsBalance(account.getPointsBalance() - clawBack);
            account.setLifetimePoints(Math.max(0, account.getLifetimePoints() - clawBack));
            account.setTierId(tierFor(account.getLifetimePoints()));
            account.setUpdatedAt(now);
            accountRepository.save(account);

            transactionRepository.save(LoyaltyTransaction.builder()
                    .customerId(row.getCustomerId()).orderId(orderId)
                    .type(LoyaltyTransaction.Type.REVERSAL)
                    .points(-clawBack).balanceAfter(account.getPointsBalance())
                    .note("Goods returned").userId(userId).at(now).build());
        }
    }

    /** A manual correction — a goodwill award, or taking back points given in error. */
    @Transactional
    public LoyaltyAccountDto adjust(Long customerId, int points, String note, User user) {
        if (points == 0) {
            throw new BadRequestException("Enter a number of points to add or take away");
        }
        LoyaltyAccount account = accountRepository.findByCustomerId(customerId)
                .orElseGet(() -> LoyaltyAccount.builder()
                        .customerId(customerId).pointsBalance(0).lifetimePoints(0)
                        .updatedAt(LocalDateTime.now()).build());
        LocalDateTime now = LocalDateTime.now();
        account.setPointsBalance(account.getPointsBalance() + points);
        if (points > 0) {
            account.setLifetimePoints(account.getLifetimePoints() + points);
        }
        account.setTierId(tierFor(account.getLifetimePoints()));
        account.setUpdatedAt(now);
        accountRepository.save(account);
        transactionRepository.save(LoyaltyTransaction.builder()
                .customerId(customerId).type(LoyaltyTransaction.Type.ADJUST)
                .points(points).balanceAfter(account.getPointsBalance())
                .note(note).userId(user == null ? null : user.getId()).at(now).build());
        return account(customerId);
    }

    // ── maths ───────────────────────────────────────────────────────────────────────────

    private BigDecimal valueOf(int points, LoyaltySettings settings) {
        return BigDecimal.valueOf(Math.max(0, points))
                .multiply(settings.getCurrencyPerPoint())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private int pointsFor(double paidTotal, LoyaltyAccount account, LoyaltySettings settings) {
        if (paidTotal <= 0 || settings.getPointsPerCurrency().signum() <= 0) {
            return 0;
        }
        BigDecimal multiplier = BigDecimal.ONE;
        if (account.getTierId() != null) {
            multiplier = tierRepository.findById(account.getTierId())
                    .map(LoyaltyTier::getEarnMultiplier).orElse(BigDecimal.ONE);
        }
        BigDecimal raw = BigDecimal.valueOf(paidTotal)
                .multiply(settings.getPointsPerCurrency())
                .multiply(multiplier);
        return raw.setScale(0, settings.isRoundEarnedDown() ? RoundingMode.FLOOR : RoundingMode.HALF_UP).intValue();
    }

    /** The highest active tier this lifetime total has reached, or null when none has. */
    private Long tierFor(int lifetimePoints) {
        return tierRepository.findByActiveTrueOrderByMinLifetimePointsAsc().stream()
                .filter(tier -> lifetimePoints >= tier.getMinLifetimePoints())
                .max(Comparator.comparingInt(LoyaltyTier::getMinLifetimePoints))
                .map(LoyaltyTier::getId)
                .orElse(null);
    }
}
