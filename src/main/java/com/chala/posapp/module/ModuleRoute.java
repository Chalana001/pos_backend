package com.chala.posapp.module;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A request pattern owned by a module.
 *
 * <p>Patterns are Ant-style and matched against the <em>normalized</em> path — the leading
 * {@code /api} of the newer controllers is stripped first, so {@code /api/reports/v2/cash-flow}
 * is matched as {@code /reports/v2/cash-flow}. That keeps one rule working for both of the
 * path conventions that coexist in this codebase.
 *
 * <p>An empty {@code methods} set means the rule covers every HTTP method. Naming a subset is
 * how a module gates writes while leaving reads open — {@code SETTINGS_BRANCHES} owns
 * {@code POST/PUT/PATCH/DELETE /branches/**} but not {@code GET}, because every client needs
 * to read the branch list to boot.
 */
public record ModuleRoute(String pattern, Set<String> methods) {

    public ModuleRoute {
        methods = methods == null ? Set.of() : Set.copyOf(methods);
    }

    /** Matches the pattern for every HTTP method. */
    public static ModuleRoute any(String pattern) {
        return new ModuleRoute(pattern, Set.of());
    }

    /** Matches the pattern only for the named methods. */
    public static ModuleRoute of(String pattern, String... methods) {
        Set<String> upper = new LinkedHashSet<>();
        Arrays.stream(methods).map(m -> m.toUpperCase(Locale.ROOT)).forEach(upper::add);
        return new ModuleRoute(pattern, Collections.unmodifiableSet(upper));
    }

    /** Every write verb — the usual "reads stay open, writes are gated" shape. */
    public static ModuleRoute writes(String pattern) {
        return of(pattern, "POST", "PUT", "PATCH", "DELETE");
    }

    public boolean coversMethod(String method) {
        return methods.isEmpty() || methods.contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * How specific this pattern is. The resolver prefers the most specific match so a child
     * rule such as {@code /stock-transfers/**} wins over a parent's {@code /stock/**}.
     */
    public int specificity() {
        int wildcardPenalty = pattern.contains("**") ? 0 : 2;
        return pattern.length() * 10 + wildcardPenalty + (methods.isEmpty() ? 0 : 1);
    }
}
