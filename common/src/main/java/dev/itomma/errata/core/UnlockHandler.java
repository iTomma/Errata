package dev.itomma.errata.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the current {@link RecipeIndex} and the per-player bookkeeping that decides when to
 * hand a recipe over.
 */
public final class UnlockHandler {

    private static volatile RecipeIndex index;
    private static final Map<UUID, PlayerState> STATES = new HashMap<>();
    /** Coalescing window for the client-side recipe book sync. */
    private static final int BOOK_SYNC_INTERVAL_TICKS = 60;

    private UnlockHandler() {
    }

    private static final class PlayerState {
        /** Items this player has been seen holding since they logged in. */
        final Set<Item> seenItems = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Recipes already handed to this player, so we never re-check them. */
        final Set<RecipeIndex.Managed> awarded = Collections.newSetFromMap(new IdentityHashMap<>());
        /**
         * The first scan after login covers whatever the player was already carrying. Those are
         * not discoveries, so they are granted without a notification. Everything after that is
         * a genuine "you just picked this up" and does toast.
         */
        boolean initialScanDone = false;
        /** Silent additions are in the server-side book but not yet sent to the client. */
        boolean bookDirty = false;
        int lastBookSyncTick = Integer.MIN_VALUE;
    }

    public static void rebuild(MinecraftServer server) {
        RecipeIndex built = RecipeIndex.build(server);
        index = built;
        synchronized (STATES) {
            STATES.clear();
        }

        if (ErrataCore.config().logSummary()) {
            ErrataCore.LOGGER.info(
                    "Indexed {} of {} recipes for automatic unlocking "
                            + "({} already covered by an advancement, {} special/filtered, "
                            + "{} unlocked silently because the book cannot show them, {} unreadable, "
                            + "{} ingredient-less and granted on join, {} trigger items, "
                            + "{} had no distinctive ingredient and fall back to any).",
                    built.managedRecipeCount(), built.totalRecipes(), built.skippedHadAdvancement(),
                    built.skippedFiltered(), built.silentBookLess(), built.errored(),
                    built.ingredientless().size(), built.indexedItemCount(), built.fellBack());
        }
    }

    public static void clear() {
        index = null;
        synchronized (STATES) {
            STATES.clear();
        }
    }

    public static RecipeIndex index() {
        return index;
    }

    public static void onPlayerJoin(ServerPlayer player) {
        RecipeIndex current = index;
        if (current == null) {
            return;
        }
        // Fresh state every login: the seen-item set is re-derived from the inventory below.
        PlayerState state = new PlayerState();
        synchronized (STATES) {
            STATES.put(player.getUUID(), state);
        }

        if (ErrataCore.config().grantIngredientlessRecipes() && !current.ingredientless().isEmpty()) {
            if (awardSilently(player, current.ingredientless())) {
                state.bookDirty = true;
            }
            if (ErrataCore.config().logUnlocks()) {
                ErrataCore.LOGGER.info("{} granted {} ingredient-less recipe(s) silently on join.",
                        player.getGameProfile().getName(), current.ingredientless().size());
            }
        }
        scan(player);
        flushBook(player, state, true);
    }

    public static void onPlayerLeave(ServerPlayer player) {
        synchronized (STATES) {
            STATES.remove(player.getUUID());
        }
    }

    /**
     * Look at everything the player is currently carrying and grant any recipe that has
     * become reachable. Cheap in the common case: items already seen are skipped outright,
     * so a steady-state tick does a handful of identity-set lookups and nothing else.
     */
    public static void scan(ServerPlayer player) {
        RecipeIndex current = index;
        if (current == null) {
            return;
        }

        PlayerState state;
        synchronized (STATES) {
            state = STATES.computeIfAbsent(player.getUUID(), k -> new PlayerState());
        }

        List<Item> newItems = null;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (state.seenItems.add(item)) {
                if (newItems == null) {
                    newItems = new ArrayList<>(4);
                }
                newItems.add(item);
            }
        }

        // Set this before any early return. Otherwise a player who joins with an empty
        // inventory never completes an "initial" scan, and their first real pickup is
        // silently swallowed instead of announced.
        boolean initial = !state.initialScanDone;
        state.initialScanDone = true;

        if (newItems == null) {
            return;
        }

        boolean requireAll = ErrataCore.config().requireAllIngredients();
        boolean logUnlocks = ErrataCore.config().logUnlocks();
        List<RecipeHolder<?>> announced = null;
        List<RecipeHolder<?>> silent = null;
        List<String> logLines = null;
        Set<RecipeIndex.Managed> considered = null;

        for (Item item : newItems) {
            for (RecipeIndex.Managed managed : current.forItem(item)) {
                if (state.awarded.contains(managed)) {
                    continue;
                }
                if (considered == null) {
                    considered = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                if (!considered.add(managed)) {
                    continue;
                }
                if (requireAll && !hasEveryIngredient(managed, state.seenItems)) {
                    continue;
                }
                state.awarded.add(managed);

                // Announce only recipes that have somewhere to be seen. Book-less ones are added
                // quietly -- there is no page to send the player to, so a toast is just noise.
                boolean announce = managed.announce() && !initial;
                if (announce) {
                    if (announced == null) {
                        announced = new ArrayList<>();
                    }
                    announced.add(managed.holder());
                } else {
                    if (silent == null) {
                        silent = new ArrayList<>();
                    }
                    silent.add(managed.holder());
                }

                if (logUnlocks && announce) {
                    if (logLines == null) {
                        logLines = new ArrayList<>();
                    }
                    logLines.add(BuiltInRegistries.ITEM.getKey(item) + " -> " + managed.holder().id());
                }
            }
        }

        if (silent != null && awardSilently(player, silent)) {
            state.bookDirty = true;
        }
        if (announced != null) {
            player.awardRecipes(announced);
        }

        if (logUnlocks && (announced != null || silent != null)) {
            int a = announced == null ? 0 : announced.size();
            int q = silent == null ? 0 : silent.size();
            if (a > 0) {
                ErrataCore.LOGGER.info("{} unlocked {} recipe(s) [announced, newly obtained]: {}"
                                + (q > 0 ? " (+" + q + " added silently)" : ""),
                        player.getGameProfile().getName(), a, String.join(", ", logLines));
            } else {
                ErrataCore.LOGGER.info("{} silently gained {} recipe(s) [{}].",
                        player.getGameProfile().getName(), q,
                        initial ? "already in inventory at login" : "not shown in the recipe book");
            }
        }
    }

    /**
     * Add recipes to the player's book without the "Recipe Unlocked" toast.
     *
     * <p>{@link net.minecraft.server.level.ServerPlayer#awardRecipes} sends an ADD packet, which
     * the client turns into a toast and a green "new" highlight. Writing straight into the book
     * and then re-sending the same INIT packet the player already got at login has the recipes
     * simply be there, with no announcement.
     */
    private static boolean awardSilently(ServerPlayer player, List<RecipeHolder<?>> recipes) {
        if (recipes.isEmpty()) {
            return false;
        }
        ServerRecipeBook book = player.getRecipeBook();
        int added = 0;
        for (RecipeHolder<?> holder : recipes) {
            if (!book.contains(holder)) {
                book.add(holder);
                added++;
            }
        }
        return added > 0;
    }

    /**
     * Push silent additions to the client.
     *
     * <p>The only way to add a recipe without a toast is to write it into the book and re-send the
     * INIT packet, which carries every known recipe id. That is fine once at login but far too
     * heavy to do whenever a book-less recipe unlocks, which with any-ingredient triggers can be
     * most seconds. So the server-side book is updated immediately -- the recipe is genuinely known
     * straight away -- and the client sync is coalesced to at most once every few seconds.
     */
    private static void flushBook(ServerPlayer player, PlayerState state, boolean force) {
        if (!state.bookDirty) {
            return;
        }
        int now = player.tickCount;
        if (!force && now - state.lastBookSyncTick < BOOK_SYNC_INTERVAL_TICKS) {
            return;
        }
        state.lastBookSyncTick = now;
        state.bookDirty = false;
        player.getRecipeBook().sendInitialRecipeBook(player);
    }

    /** Called every tick pass so a pending sync cannot sit unsent after the last pickup. */
    public static void tickSync(ServerPlayer player) {
        PlayerState state;
        synchronized (STATES) {
            state = STATES.get(player.getUUID());
        }
        if (state != null) {
            flushBook(player, state, false);
        }
    }

    private static boolean hasEveryIngredient(RecipeIndex.Managed managed, Set<Item> seen) {
        for (Set<Item> option : managed.ingredientOptions()) {
            boolean satisfied = false;
            for (Item candidate : option) {
                if (seen.contains(candidate)) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    /** Used by the debug command: hand over everything this mod manages, immediately. */
    public static int grantEverything(ServerPlayer player) {
        RecipeIndex current = index;
        if (current == null) {
            return 0;
        }
        Set<RecipeHolder<?>> all = Collections.newSetFromMap(new IdentityHashMap<>());
        all.addAll(current.ingredientless());
        for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            for (RecipeIndex.Managed managed : current.forItem(item)) {
                all.add(managed.holder());
            }
        }
        List<RecipeHolder<?>> list = new ArrayList<>(all);
        player.awardRecipes(list);
        synchronized (STATES) {
            STATES.remove(player.getUUID());
        }
        return list.size();
    }
}
