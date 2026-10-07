package com.chala.posapp.util;

import com.chala.posapp.exception.BadRequestException;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for payment-method spelling and the method sets used
 * by each flow. A sale can be paid with cash, card, bank or left as credit.
 * Later settlements are separate: they settle an existing balance and must not
 * inherit sale-only rules. Purchase cheques are unrelated and are validated by
 * their own flow.
 */
public final class PaymentMethodUtil {

    public static final String CASH = "CASH";
    public static final String CARD = "CARD";
    public static final String BANK = "BANK";
    public static final String CREDIT = "CREDIT";

    private static final Set<String> SALE_METHODS = Set.of(CASH, CARD, BANK, CREDIT);
    private static final Set<String> SETTLEMENT_METHODS = Set.of(CASH, CARD, BANK);

    private PaymentMethodUtil() {
    }

    /** Validates and normalizes a method used to pay a sale, including pure credit. */
    public static String requireSaleMethod(String paymentMethod, String fallback) {
        return requireMethod(paymentMethod, fallback, SALE_METHODS,
                "Allowed sale payment methods: CASH, CARD, BANK, CREDIT");
    }

    public static boolean isSaleMethod(String paymentMethod) {
        return SALE_METHODS.contains(normalize(paymentMethod));
    }

    /** Validates and normalizes a method used to settle an existing credit balance. */
    public static String requireSettlementMethod(String paymentMethod, String fallback) {
        return requireMethod(paymentMethod, fallback, SETTLEMENT_METHODS,
                "Allowed settlement payment methods: CASH, CARD, BANK");
    }

    public static boolean isSettlementMethod(String paymentMethod) {
        return SETTLEMENT_METHODS.contains(normalize(paymentMethod));
    }

    private static String requireMethod(String paymentMethod, String fallback, Set<String> allowed,
            String allowedMessage) {
        String normalized = normalize(paymentMethod, fallback);
        if (!allowed.contains(normalized)) {
            throw new BadRequestException("Payment method " + normalized
                    + " is not supported. " + allowedMessage);
        }
        return normalized;
    }

    private static String normalize(String paymentMethod) {
        return paymentMethod == null ? null : paymentMethod.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalize(String paymentMethod, String fallback) {
        return paymentMethod == null || paymentMethod.isBlank()
                ? fallback
                : paymentMethod.trim().toUpperCase(Locale.ROOT);
    }
}
