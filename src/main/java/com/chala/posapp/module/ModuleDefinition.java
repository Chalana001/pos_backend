package com.chala.posapp.module;

import java.util.List;

/**
 * One entry in the canonical module catalog.
 *
 * <p>The catalog is defined in code ({@link ModuleCatalog}) and mirrored into the
 * {@code modules} table on boot so the super admin panel can join against it. Code is
 * authoritative for structure (key/parent/name/routes); the database only ever stores
 * <em>enabled-ness</em>, in {@code plan_modules} and {@code tenant_modules}.
 *
 * @param key           stable identifier, never renamed once shipped — plan_modules and
 *                      tenant_modules rows reference it by string
 * @param parentKey     {@code null} for a top-level module, otherwise the parent's key.
 *                      A disabled parent disables every child regardless of the child's
 *                      own stored value.
 * @param name          human label shown in the panel
 * @param description   one line explaining what the shop loses if it is switched off
 * @param category      presentation grouping
 * @param icon          lucide-react icon name the panel renders
 * @param locked        the module is load-bearing for the app to boot at all and can
 *                      never be switched off (its children still can be)
 * @param routes        request patterns this module owns, used by {@link ModuleRouteResolver}
 * @param uiPaths       POS frontend routes this module owns, used by the frontend gate
 */
public record ModuleDefinition(
        String key,
        String parentKey,
        String name,
        String description,
        ModuleCategory category,
        String icon,
        boolean locked,
        List<ModuleRoute> routes,
        List<String> uiPaths
) {

    public boolean isTopLevel() {
        return parentKey == null;
    }

    static ModuleDefinition top(String key, String name, String description,
                                ModuleCategory category, String icon,
                                List<ModuleRoute> routes, List<String> uiPaths) {
        return new ModuleDefinition(key, null, name, description, category, icon, false, routes, uiPaths);
    }

    static ModuleDefinition locked(String key, String name, String description,
                                   ModuleCategory category, String icon,
                                   List<ModuleRoute> routes, List<String> uiPaths) {
        return new ModuleDefinition(key, null, name, description, category, icon, true, routes, uiPaths);
    }

    static ModuleDefinition child(String key, String parentKey, String name, String description,
                                  ModuleCategory category, String icon,
                                  List<ModuleRoute> routes, List<String> uiPaths) {
        return new ModuleDefinition(key, parentKey, name, description, category, icon, false, routes, uiPaths);
    }
}
