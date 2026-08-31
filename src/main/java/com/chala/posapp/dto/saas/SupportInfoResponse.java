package com.chala.posapp.dto.saas;

/**
 * How a shop reaches the platform operator.
 *
 * <p>Read from platform settings so the number lives in one place and can be changed from the
 * control panel. It replaces a hardcoded WhatsApp number that shipped inside the POS bundle,
 * where changing it meant a rebuild and a redeploy.
 *
 * <p>Served under {@code /api/saas}, which is subscription-exempt — a shop whose subscription
 * has lapsed is exactly the shop that most needs to see how to contact you.
 */
public record SupportInfoResponse(
        String platformName,
        String supportEmail,
        String supportPhone,
        String currencyPrefix
) {
}
