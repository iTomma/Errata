package dev.itomma.errata.core;

import java.util.List;

/**
 * Every setting the core needs, expressed without reference to any mod loader.
 *
 * <p>NeoForge supplies these from a {@code ModConfigSpec}; Fabric has no config API, so a Fabric
 * port would back the same interface with a hand-parsed JSON file. Nothing in {@code core}
 * knows or cares which.
 *
 * <p>Implementations are expected to read live values on every call rather than caching, since
 * config files can be reloaded underneath a running server.
 */
public interface ErrataConfig {

    boolean onlyRecipesWithoutAdvancements();

    boolean distinctiveTriggers();

    int commonItemThreshold();

    boolean requireAllIngredients();

    boolean manageBookLessRecipes();

    List<String> extraDisplayableTypes();

    boolean grantIngredientlessRecipes();

    int scanIntervalTicks();

    boolean logSummary();

    boolean logUnlocks();

    boolean repairBrokenAdvancements();

    List<String> namespaceAllowlist();

    List<String> namespaceDenylist();

    /** Allowlist first (empty means "everything"), then denylist. */
    default boolean namespaceAllowed(String namespace) {
        List<String> allow = namespaceAllowlist();
        if (!allow.isEmpty() && !allow.contains(namespace)) {
            return false;
        }
        return !namespaceDenylist().contains(namespace);
    }

    /**
     * Safe fallback used before the real config is bound, and by tests. Matches the defaults
     * declared in the NeoForge spec.
     */
    ErrataConfig DEFAULTS = new ErrataConfig() {
        @Override public boolean onlyRecipesWithoutAdvancements() { return true; }
        @Override public boolean distinctiveTriggers() { return true; }
        @Override public int commonItemThreshold() { return 20; }
        @Override public boolean requireAllIngredients() { return false; }
        @Override public boolean manageBookLessRecipes() { return true; }
        @Override public List<String> extraDisplayableTypes() { return List.of(); }
        @Override public boolean grantIngredientlessRecipes() { return true; }
        @Override public int scanIntervalTicks() { return 20; }
        @Override public boolean logSummary() { return true; }
        @Override public boolean logUnlocks() { return true; }
        @Override public boolean repairBrokenAdvancements() { return true; }
        @Override public List<String> namespaceAllowlist() { return List.of(); }
        @Override public List<String> namespaceDenylist() { return List.of(); }
    };
}
