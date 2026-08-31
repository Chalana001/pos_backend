package com.chala.posapp.module;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps an incoming request to the module that owns it.
 *
 * <p>The old {@code SubscriptionFilter} did this with {@code path.startsWith(prefix)} over two
 * hardcoded sets, which meant a prefix such as {@code /stock} also swallowed
 * {@code /stock-transfers} by accident and any newly added controller was silently ungated.
 * Matching is Ant-style here, and the most specific rule wins, so a child module's rule beats
 * its parent's catch-all.
 *
 * <p>A path that matches no rule is <strong>allowed</strong> and logged at debug. Failing closed
 * would break the app the moment anyone adds a controller, and role checks still apply. The
 * unmatched paths are surfaced to the super admin panel via
 * {@link com.chala.posapp.service.ModuleAccessService} so gaps in the catalog are visible rather
 * than silent.
 */
@Slf4j
@Component
public class ModuleRouteResolver {

    /** Cap on the unmapped-path sample so a fuzzer cannot grow it without bound. */
    private static final int UNMAPPED_SAMPLE_LIMIT = 200;

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<CompiledRule> rules;
    private final Set<String> unmappedPaths = ConcurrentHashMap.newKeySet();

    public ModuleRouteResolver() {
        List<CompiledRule> compiled = new ArrayList<>();
        for (ModuleDefinition definition : ModuleCatalog.all()) {
            for (ModuleRoute route : definition.routes()) {
                compiled.add(new CompiledRule(route, definition.key()));
            }
        }
        // Most specific first, so the first match is the right one.
        compiled.sort(Comparator.comparingInt((CompiledRule rule) -> rule.route().specificity()).reversed());
        this.rules = List.copyOf(compiled);
    }

    /**
     * Strips the {@code /api} prefix used by the newer controllers so a single catalog rule
     * covers both path conventions that coexist in this codebase.
     */
    public String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/api/") ? path.substring(4) : path;
        // Trailing slash would defeat an exact pattern such as "/orders".
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * True when the request skips the subscription check too — auth, health and the control
     * plane. Keep this list to routes that return no shop data: anything here stays reachable
     * for a blocked or expired shop.
     */
    public boolean isSubscriptionExempt(String normalizedPath, String method) {
        return matchesAny(ModuleCatalog.SUBSCRIPTION_EXEMPT, normalizedPath, method);
    }

    /**
     * True when the request skips only the module check. The subscription check still applies,
     * so an expired shop is still stopped — these are just the boot reads no module owns.
     */
    public boolean isModuleExempt(String normalizedPath, String method) {
        return matchesAny(ModuleCatalog.MODULE_EXEMPT, normalizedPath, method);
    }

    private boolean matchesAny(List<ModuleRoute> routes, String normalizedPath, String method) {
        for (ModuleRoute route : routes) {
            if (route.coversMethod(method) && matcher.match(route.pattern(), normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    /** The module key owning this request, or empty when the catalog has no rule for it. */
    public Optional<String> resolve(String normalizedPath, String method) {
        for (CompiledRule rule : rules) {
            if (rule.route().coversMethod(method) && matcher.match(rule.route().pattern(), normalizedPath)) {
                return Optional.of(rule.moduleKey());
            }
        }
        log.debug("No module owns {} {} — allowing. Add a route to ModuleCatalog to gate it.", method, normalizedPath);
        if (unmappedPaths.size() < UNMAPPED_SAMPLE_LIMIT) {
            unmappedPaths.add(method.toUpperCase(java.util.Locale.ROOT) + " " + normalizedPath);
        }
        return Optional.empty();
    }

    /**
     * Requests seen since boot that no module claims. Surfaced in the super admin panel's
     * system health page so a controller added without a catalog entry shows up as a gap
     * instead of quietly being ungated forever.
     */
    public List<String> unmappedPaths() {
        return unmappedPaths.stream().sorted().toList();
    }

    private record CompiledRule(ModuleRoute route, String moduleKey) {
    }
}
