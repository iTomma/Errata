package dev.itomma.errata;

import dev.itomma.errata.core.ErrataConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * The NeoForge-backed config. This is loader-specific by nature: {@code ModConfigSpec} does not
 * exist on Fabric. The core reads settings through {@link ErrataConfig} instead, and {@link #VALUES}
 * is the adapter that binds the two together.
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ONLY_RECIPES_WITHOUT_ADVANCEMENTS;
    public static final ModConfigSpec.BooleanValue GRANT_INGREDIENTLESS_RECIPES;
    public static final ModConfigSpec.BooleanValue MANAGE_BOOKLESS_RECIPES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_DISPLAYABLE_TYPES;
    public static final ModConfigSpec.BooleanValue REQUIRE_ALL_INGREDIENTS;
    public static final ModConfigSpec.BooleanValue DISTINCTIVE_TRIGGERS;
    public static final ModConfigSpec.IntValue COMMON_ITEM_THRESHOLD;
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue LOG_SUMMARY;
    public static final ModConfigSpec.BooleanValue LOG_UNLOCKS;
    public static final ModConfigSpec.BooleanValue REPAIR_BROKEN_ADVANCEMENTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NAMESPACE_ALLOWLIST;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NAMESPACE_DENYLIST;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");

        ONLY_RECIPES_WITHOUT_ADVANCEMENTS = b
                .comment(
                        "If true, only recipes that no advancement can already unlock are managed by this mod.",
                        "This leaves well-behaved mods' intended progression completely untouched.",
                        "If false, every recipe is indexed regardless of existing advancements.")
                .define("onlyRecipesWithoutAdvancements", true);

        DISTINCTIVE_TRIGGERS = b
                .comment(
                        "Unlock a recipe from its DISTINCTIVE ingredient rather than any ingredient.",
                        "For each recipe every ingredient slot is scored by how many managed recipes its",
                        "most common member appears in; the rarest slot becomes the trigger. So an",
                        "enderman tooth announces the Corrupted Shield and oak planks does not, even",
                        "though both are in the recipe.",
                        "Set to false to go back to triggering on any ingredient.")
                .define("distinctiveTriggers", true);

        COMMON_ITEM_THRESHOLD = b
                .comment(
                        "An item appearing in more than this many managed recipes counts as a staple and",
                        "will not be used as a trigger. If every slot of a recipe is a staple, that recipe",
                        "falls back to triggering on any ingredient so it stays discoverable.",
                        "Lower = quieter but more fallbacks. Only used when distinctiveTriggers is true.")
                .defineInRange("commonItemThreshold", 20, 1, 10000);

        REQUIRE_ALL_INGREDIENTS = b
                .comment(
                        "false (default): a recipe unlocks as soon as the player obtains ANY one of its ingredients.",
                        "true: the player must have obtained EVERY ingredient at least once.")
                .define("requireAllIngredients", false);

        MANAGE_BOOKLESS_RECIPES = b
                .comment(
                        "Recipes the vanilla book cannot display (Farmer's Delight cooking/cutting,",
                        "archeology tables, machine recipes, and also vanilla campfire/stonecutting/",
                        "smithing) are unlocked SILENTLY -- added to the recipe book with no toast.",
                        "They cannot be announced usefully, since there is no book page to send you to,",
                        "but marking them known still matters for stations and recipe viewers that key",
                        "off the recipe book.",
                        "Because there is no notification cost, these unlock on ANY ingredient rather",
                        "than waiting for a distinctive one.",
                        "Set to false to ignore them entirely.")
                .define("manageBookLessRecipes", true);

        EXTRA_DISPLAYABLE_TYPES = b
                .comment(
                        "Additional recipe type ids to treat as book-displayable, for mods that add real",
                        "recipe book support for their own types. Example: [\"somemod:alloying\"]")
                .defineListAllowEmpty("extraDisplayableTypes", List.of(), () -> "", o -> o instanceof String);

        GRANT_INGREDIENTLESS_RECIPES = b
                .comment(
                        "Some modded recipe types expose no ingredients to the game (custom machine recipes,",
                        "and vanilla smithing and brewing). Those can never be triggered by holding an item.",
                        "They never show in the book either, so they are granted silently when a player",
                        "joins -- available, but never announced.")
                .define("grantIngredientlessRecipes", true);

        SCAN_INTERVAL_TICKS = b
                .comment("How often each player's inventory is checked, in ticks. 20 = once per second.")
                .defineInRange("scanIntervalTicks", 20, 1, 1200);

        LOG_SUMMARY = b
                .comment("Log a summary of how many recipes were adopted when the index is built.")
                .define("logSummary", true);

        LOG_UNLOCKS = b
                .comment(
                        "Log every individual unlock: which item triggered it, which recipe it unlocked,",
                        "and whether it was announced with a toast or added silently. Useful when an unlock",
                        "looks unrelated to what you just picked up.")
                .define("logUnlocks", true);

        REPAIR_BROKEN_ADVANCEMENTS = b
                .comment(
                        "Repair recipe advancements written for 1.20.4 or earlier.",
                        "Those use the removed 'item'/'tag' item-predicate keys, which 1.21.1 ignores,",
                        "leaving an empty predicate that matches ANY item -- so the recipe unlocks on the",
                        "player's next pickup, whatever it is.",
                        "Corrected copies are written to a generated datapack in the world folder",
                        "(datapacks/errata_repairs). Mod jars are never modified, and deleting",
                        "that folder undoes everything.")
                .define("repairBrokenAdvancements", true);

        b.pop();
        b.push("filters");

        NAMESPACE_ALLOWLIST = b
                .comment(
                        "If non-empty, ONLY recipes from these namespaces are managed. Example: [\"farmersdelight\", \"create\"]")
                .defineListAllowEmpty("namespaceAllowlist", List.of(), () -> "", o -> o instanceof String);

        NAMESPACE_DENYLIST = b
                .comment("Recipes from these namespaces are never managed. Applied after the allowlist.")
                .defineListAllowEmpty("namespaceDenylist", List.of(), () -> "", o -> o instanceof String);

        b.pop();

        SPEC = b.build();
    }

    /**
     * Live view of this spec as the loader-agnostic {@link ErrataConfig}. Every call reads through
     * to the spec, so a config reload takes effect without rebinding.
     */
    public static final ErrataConfig VALUES = new ErrataConfig() {
        @Override public boolean onlyRecipesWithoutAdvancements() { return ONLY_RECIPES_WITHOUT_ADVANCEMENTS.get(); }
        @Override public boolean distinctiveTriggers() { return DISTINCTIVE_TRIGGERS.get(); }
        @Override public int commonItemThreshold() { return COMMON_ITEM_THRESHOLD.get(); }
        @Override public boolean requireAllIngredients() { return REQUIRE_ALL_INGREDIENTS.get(); }
        @Override public boolean manageBookLessRecipes() { return MANAGE_BOOKLESS_RECIPES.get(); }
        @Override public List<String> extraDisplayableTypes() { return copy(EXTRA_DISPLAYABLE_TYPES.get()); }
        @Override public boolean grantIngredientlessRecipes() { return GRANT_INGREDIENTLESS_RECIPES.get(); }
        @Override public int scanIntervalTicks() { return SCAN_INTERVAL_TICKS.get(); }
        @Override public boolean logSummary() { return LOG_SUMMARY.get(); }
        @Override public boolean logUnlocks() { return LOG_UNLOCKS.get(); }
        @Override public boolean repairBrokenAdvancements() { return REPAIR_BROKEN_ADVANCEMENTS.get(); }
        @Override public List<String> namespaceAllowlist() { return copy(NAMESPACE_ALLOWLIST.get()); }
        @Override public List<String> namespaceDenylist() { return copy(NAMESPACE_DENYLIST.get()); }
    };

    /** {@code ConfigValue<List<? extends String>>} does not widen to {@code List<String>}. */
    private static List<String> copy(List<? extends String> in) {
        return List.copyOf(in);
    }

    private Config() {
    }
}
