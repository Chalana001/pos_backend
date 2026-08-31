package com.chala.posapp.service;

import com.chala.posapp.entity.PlatformSetting;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.PlatformSettingRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide settings, as a small typed façade over a key/value table.
 *
 * <p>The catalog below is the only definition of what a valid key is — an unknown key is
 * rejected rather than silently stored, so a typo in the panel cannot create a setting that
 * nothing ever reads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    public enum Kind { TEXT, NUMBER, BOOLEAN }

    /**
     * @param key     stable identifier, stored in the database
     * @param group   how the panel groups it on screen
     */
    public record SettingDefinition(
            String key,
            String label,
            String description,
            Kind kind,
            String defaultValue,
            String group
    ) {
    }

    public static final List<SettingDefinition> CATALOG = List.of(
            new SettingDefinition("platform.name", "Platform name",
                    "Shown on invoices and in the POS app's about screen.",
                    Kind.TEXT, "POS Platform", "Branding"),
            new SettingDefinition("platform.support_email", "Support email",
                    "Where shops are told to write when something is wrong.",
                    Kind.TEXT, "", "Branding"),
            new SettingDefinition("platform.support_phone", "Support phone",
                    "Shown to a shop whose subscription has lapsed.",
                    Kind.TEXT, "", "Branding"),
            new SettingDefinition("platform.currency_prefix", "Currency prefix",
                    "Used on invoices and in the panel's money figures.",
                    Kind.TEXT, "Rs.", "Billing"),
            new SettingDefinition("billing.default_grace_days", "Default grace days",
                    "Days a newly onboarded shop keeps working after expiry. Per-shop values override this.",
                    Kind.NUMBER, "0", "Billing"),
            new SettingDefinition("billing.default_trial_days", "Default trial length",
                    "Offered when a plan does not set its own trial length.",
                    Kind.NUMBER, "14", "Billing"),
            new SettingDefinition("billing.tax_percent", "Tax percentage",
                    "Added to subscription invoices. 0 disables the tax line entirely.",
                    Kind.NUMBER, "0", "Billing"),
            new SettingDefinition("onboarding.default_plan_id", "Default plan",
                    "Pre-selected on the onboarding form.",
                    Kind.TEXT, "", "Onboarding"),
            new SettingDefinition("onboarding.require_contact", "Require contact details",
                    "Refuse to onboard a shop without a phone number or email.",
                    Kind.BOOLEAN, "false", "Onboarding"),
            new SettingDefinition("support.max_session_minutes", "Maximum support session",
                    "Upper bound an operator can request, capped by the server at 120.",
                    Kind.NUMBER, "60", "Support"),
            new SettingDefinition("support.require_reason", "Require a reason",
                    "Always on — a support session without a stated reason cannot be reviewed later.",
                    Kind.BOOLEAN, "true", "Support")
    );

    private static final Map<String, SettingDefinition> BY_KEY =
            CATALOG.stream().collect(java.util.stream.Collectors.toMap(
                    SettingDefinition::key, definition -> definition, (a, b) -> a, LinkedHashMap::new));

    private final PlatformSettingRepository settingRepository;
    private final SuperAdminAuditService auditService;
    private final PlatformTransactionManager transactionManager;

    /**
     * Reads settings from the control plane regardless of the caller's tenant context.
     *
     * <p>A shop user asking for the support number arrives with their own tenant bound, and
     * {@code platform_settings} exists only in the master catalog — querying it from a shop
     * database throws. Everything tenant-facing must come through here.
     */
    public String getFromMaster(String key) {
        SettingDefinition definition = BY_KEY.get(key);
        if (definition == null) {
            throw new BadRequestException("Unknown setting: " + key);
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);

        return TenantContext.callWith("MASTER", () -> tx.execute(status ->
                settingRepository.findByKey(key)
                        .map(PlatformSetting::getValue)
                        .filter(value -> value != null && !value.isBlank())
                        .orElse(definition.defaultValue())));
    }

    /** Every known setting with its stored or default value. */
    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> stored = new LinkedHashMap<>();
        settingRepository.findAll().forEach(setting -> stored.put(setting.getKey(), setting.getValue()));

        Map<String, String> result = new LinkedHashMap<>();
        for (SettingDefinition definition : CATALOG) {
            result.put(definition.key(), stored.getOrDefault(definition.key(), definition.defaultValue()));
        }
        return result;
    }

    public List<SettingDefinition> catalog() {
        return CATALOG;
    }

    @Transactional(readOnly = true)
    public String get(String key) {
        SettingDefinition definition = BY_KEY.get(key);
        if (definition == null) {
            throw new BadRequestException("Unknown setting: " + key);
        }
        return settingRepository.findByKey(key)
                .map(PlatformSetting::getValue)
                .orElse(definition.defaultValue());
    }

    public int getInt(String key, int fallback) {
        try {
            String value = get(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        try {
            String value = get(key);
            return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    /** Saves a batch. Unknown keys are refused, not quietly written. */
    @Transactional
    public Map<String, String> update(Map<String, String> values) {
        String actor = currentActor();
        List<String> changed = new java.util.ArrayList<>();

        values.forEach((key, value) -> {
            SettingDefinition definition = BY_KEY.get(key);
            if (definition == null) {
                throw new BadRequestException("Unknown setting: " + key);
            }
            coerce(definition, value);

            PlatformSetting setting = settingRepository.findByKey(key)
                    .orElseGet(() -> PlatformSetting.builder().key(key).build());
            String previous = setting.getValue();
            if (java.util.Objects.equals(previous, value)) {
                return;
            }
            setting.setValue(value);
            setting.setUpdatedBy(actor);
            settingRepository.save(setting);
            changed.add(definition.label() + ": " + display(previous, definition) + " → " + display(value, definition));
        });

        if (!changed.isEmpty()) {
            auditService.record(actor, "PLATFORM_SETTINGS_UPDATED", SuperAdminAuditService.TARGET_SYSTEM,
                    null, String.join("; ", changed));
        }
        return all();
    }

    /** Validates the value against the setting's declared kind. */
    private void coerce(SettingDefinition definition, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        switch (definition.kind()) {
            case NUMBER -> {
                try {
                    Double.parseDouble(value.trim());
                } catch (NumberFormatException exception) {
                    throw new BadRequestException(definition.label() + " must be a number.");
                }
            }
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())) {
                    throw new BadRequestException(definition.label() + " must be true or false.");
                }
            }
            case TEXT -> {
                // Anything goes.
            }
        }
    }

    private String display(String value, SettingDefinition definition) {
        if (value == null || value.isBlank()) {
            return "(" + (definition.defaultValue().isBlank() ? "empty" : definition.defaultValue()) + ")";
        }
        return value;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}
