package dev.itomma.errata.core;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A snapshot of every recipe this mod is responsible for unlocking, indexed by the items that
 * should trigger the unlock.
 *
 * <p>Instead of parsing recipe JSON and hand-resolving item tags, we read the already-parsed
 * {@link Recipe} objects out of the server's recipe manager. {@link Ingredient#getItems()}
 * resolves tags for us, and recipes registered in code are picked up just the same.
 */
public final class RecipeIndex {

    /**
     * A recipe we are responsible for.
     *
     * @param holder            the recipe itself
     * @param ingredientOptions one item set per ingredient slot, used by requireAllIngredients
     * @param triggers          the items that actually unlock it
     * @param distinctive       false when no ingredient was distinctive enough and we fell back
     *                          to triggering on any ingredient
     * @param announce          false for recipes the vanilla book cannot display; those are added
     *                          to the book without a toast, because there is no page to send the
     *                          player to
     */
    public record Managed(RecipeHolder<?> holder,
                          List<Set<Item>> ingredientOptions,
                          Set<Item> triggers,
                          boolean distinctive,
                          boolean announce) {
    }

    private final Map<Item, List<Managed>> byTriggerItem;
    private final Set<Managed> managed;
    private final List<RecipeHolder<?>> ingredientless;
    private final int totalRecipes;
    private final int skippedHadAdvancement;
    private final int skippedFiltered;
    private final int silentBookLess;
    private final int errored;
    private final int fellBack;

    private RecipeIndex(Map<Item, List<Managed>> byTriggerItem, Set<Managed> managed,
                        List<RecipeHolder<?>> ingredientless, int totalRecipes,
                        int skippedHadAdvancement, int skippedFiltered, int silentBookLess,
                        int errored, int fellBack) {
        this.byTriggerItem = byTriggerItem;
        this.managed = managed;
        this.ingredientless = ingredientless;
        this.totalRecipes = totalRecipes;
        this.skippedHadAdvancement = skippedHadAdvancement;
        this.skippedFiltered = skippedFiltered;
        this.silentBookLess = silentBookLess;
        this.errored = errored;
        this.fellBack = fellBack;
    }

    public static RecipeIndex build(MinecraftServer server) {
        Set<ResourceLocation> alreadyUnlockable = collectAdvancementGrantedRecipes(server);

        boolean manageBookLess = ErrataCore.config().manageBookLessRecipes();
        Set<String> extraTypes = new HashSet<>(ErrataCore.config().extraDisplayableTypes());

        List<RecipeHolder<?>> ingredientless = new ArrayList<>();
        // {RecipeHolder, List<Set<Item>>, Boolean announce}
        List<Object[]> candidates = new ArrayList<>();

        int total = 0;
        int hadAdvancement = 0;
        int filtered = 0;
        int silentCount = 0;
        int errored = 0;

        // ---- pass 1: which recipes are ours, and what does each ingredient slot resolve to ----
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            total++;
            ResourceLocation id = holder.id();

            if (!ErrataCore.config().namespaceAllowed(id.getNamespace())) {
                filtered++;
                continue;
            }

            Recipe<?> recipe = holder.value();

            if (recipe.isSpecial()) {
                filtered++;
                continue;
            }

            if (ErrataCore.config().onlyRecipesWithoutAdvancements() && alreadyUnlockable.contains(id)) {
                hadAdvancement++;
                continue;
            }

            boolean announce = isRecipeBookDisplayable(recipe, extraTypes);
            if (!announce) {
                if (!manageBookLess) {
                    filtered++;
                    continue;
                }
                silentCount++;
            }

            List<Set<Item>> options;
            try {
                options = ingredientOptions(recipe);
            } catch (Throwable t) {
                ErrataCore.LOGGER.debug("Could not read ingredients of {}: {}", id, t.toString());
                errored++;
                continue;
            }

            if (options.isEmpty()) {
                ingredientless.add(holder);
                continue;
            }

            candidates.add(new Object[]{holder, options, announce});
        }

        // ---- pass 2: how many of our recipes does each item appear in? ----
        Map<Item, int[]> frequency = new IdentityHashMap<>();
        for (Object[] c : candidates) {
            @SuppressWarnings("unchecked")
            List<Set<Item>> options = (List<Set<Item>>) c[1];
            Set<Item> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Set<Item> option : options) {
                distinct.addAll(option);
            }
            for (Item item : distinct) {
                frequency.computeIfAbsent(item, k -> new int[1])[0]++;
            }
        }

        // ---- pass 3: pick the distinctive ingredient slot for each recipe ----
        boolean distinctiveMode = ErrataCore.config().distinctiveTriggers();
        int threshold = ErrataCore.config().commonItemThreshold();

        Map<Item, List<Managed>> byItem = new IdentityHashMap<>();
        Set<Managed> managedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        int fellBack = 0;

        for (Object[] c : candidates) {
            RecipeHolder<?> holder = (RecipeHolder<?>) c[0];
            @SuppressWarnings("unchecked")
            List<Set<Item>> options = (List<Set<Item>>) c[1];

            boolean announce = (Boolean) c[2];

            Set<Item> triggers;
            boolean distinctive;

            // Silent recipes cost nothing to over-trigger, so make them available as early as
            // possible: any ingredient will do.
            if (!announce || !distinctiveMode) {
                triggers = unionOf(options);
                distinctive = false;
            } else {
                // Score a slot by its *most common* member. A slot containing oak planks scores
                // badly even though it also contains some obscure modded plank, which is what we
                // want: #minecraft:planks should never be what announces a Corrupted Shield.
                Set<Item> best = null;
                int bestScore = Integer.MAX_VALUE;
                for (Set<Item> option : options) {
                    int score = 0;
                    for (Item item : option) {
                        int[] f = frequency.get(item);
                        int n = f == null ? 0 : f[0];
                        if (n > score) {
                            score = n;
                        }
                    }
                    if (score < bestScore || (score == bestScore && best != null && option.size() < best.size())) {
                        bestScore = score;
                        best = option;
                    }
                }

                if (best == null || bestScore > threshold) {
                    // Nothing here is distinctive -- every slot is a staple. Better a slightly
                    // noisy unlock than a recipe that can never be discovered.
                    triggers = unionOf(options);
                    distinctive = false;
                    fellBack++;
                } else {
                    triggers = best;
                    distinctive = true;
                }
            }

            Managed m = new Managed(holder, options, Set.copyOf(triggers), distinctive, announce);
            managedSet.add(m);
            for (Item item : triggers) {
                byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(m);
            }
        }

        Map<Item, List<Managed>> frozen = new IdentityHashMap<>(byItem.size());
        for (Map.Entry<Item, List<Managed>> e : byItem.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }

        return new RecipeIndex(Collections.unmodifiableMap(frozen),
                Collections.unmodifiableSet(managedSet), List.copyOf(ingredientless),
                total, hadAdvancement, filtered, silentCount, errored, fellBack);
    }

    private static Set<Item> unionOf(List<Set<Item>> options) {
        Set<Item> all = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Set<Item> option : options) {
            all.addAll(option);
        }
        return all;
    }

    /**
     * Whether the vanilla recipe book can actually put this recipe on a page.
     *
     * <p>Taken from {@code ClientRecipeBook.getCategory} plus
     * {@code RecipeBookCategories.getCategories}: only four book UIs exist in 1.21.1 (crafting
     * table, furnace, blast furnace, smoker). Campfire cooking, stonecutting and smithing get a
     * category assigned but that category belongs to no book, so they never render either.
     */
    private static boolean isRecipeBookDisplayable(Recipe<?> recipe, Set<String> extraTypes) {
        if (recipe instanceof CraftingRecipe) {
            return true;
        }
        RecipeType<?> type = recipe.getType();
        if (type == RecipeType.SMELTING || type == RecipeType.BLASTING || type == RecipeType.SMOKING) {
            return true;
        }
        if (extraTypes.isEmpty()) {
            return false;
        }
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return typeId != null && extraTypes.contains(typeId.toString());
    }

    /** One item set per ingredient slot. Empty slots are dropped. */
    private static List<Set<Item>> ingredientOptions(Recipe<?> recipe) {
        List<Set<Item>> options = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0) {
                continue;
            }
            Set<Item> items = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    items.add(stack.getItem());
                }
            }
            if (!items.isEmpty()) {
                options.add(items);
            }
        }
        return options;
    }

    /** Every recipe id that some advancement already hands out. */
    private static Set<ResourceLocation> collectAdvancementGrantedRecipes(MinecraftServer server) {
        Set<ResourceLocation> ids = new HashSet<>();
        Collection<AdvancementHolder> advancements = server.getAdvancements().getAllAdvancements();
        for (AdvancementHolder holder : advancements) {
            ids.addAll(holder.value().rewards().recipes());
        }
        return ids;
    }

    public List<Managed> forItem(Item item) {
        List<Managed> list = byTriggerItem.get(item);
        return list == null ? List.of() : list;
    }

    public Set<Managed> allManaged() {
        return managed;
    }

    public List<RecipeHolder<?>> ingredientless() {
        return ingredientless;
    }

    public int managedRecipeCount() {
        return managed.size();
    }

    public int totalRecipes() {
        return totalRecipes;
    }

    public int skippedHadAdvancement() {
        return skippedHadAdvancement;
    }

    public int skippedFiltered() {
        return skippedFiltered;
    }

    public int silentBookLess() {
        return silentBookLess;
    }

    public int errored() {
        return errored;
    }

    public int fellBack() {
        return fellBack;
    }

    public int indexedItemCount() {
        return byTriggerItem.size();
    }
}
