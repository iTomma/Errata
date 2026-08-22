package dev.itomma.errata.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Repairs recipe-unlock advancements that were written for 1.20.4 or earlier and never updated.
 *
 * <p>In 1.20.5 the item predicate fields {@code item} and {@code tag} were replaced by a single
 * {@code items} field holding an item-or-tag set. The old keys no longer exist, and Mojang's
 * codecs ignore unknown keys, so a predicate written the old way decodes to an <em>empty</em>
 * predicate. An empty item predicate matches every item, which turns
 *
 * <pre>{@code "conditions": { "items": [ { "tag": "minecraft:soul_fire_base_blocks" } ] } }</pre>
 *
 * into "the player obtained anything at all". The recipe then unlocks on the player's very next
 * pickup, whatever it is.
 *
 * <p>The fix is mechanical: {@code {"tag": "x"}} becomes {@code {"items": "#x"}} and
 * {@code {"item": "x"}} becomes {@code {"items": "x"}}. We write corrected copies into a generated
 * datapack in the world folder, which overrides the mod's own broken file. Nothing is patched in
 * memory and the original jar is untouched.
 */
public final class AdvancementRepair {

    public static final String PACK_DIR = "errata_repairs";
    // The pack_format comes from ErrataCore, so porting to a new Minecraft version
    // changes one number in one place rather than editing this file.

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Result(int repaired, int alreadyCurrent, List<String> recipes, boolean changed) {
    }

    private AdvancementRepair() {
    }

    public static Result run(MinecraftServer server) {
        ResourceManager resources = server.getResourceManager();
        Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_DIR);

        List<String> repairedRecipes = new ArrayList<>();
        boolean changed = false;
        int alreadyCurrent = 0;

        for (String folder : new String[]{"advancement", "advancements"}) {
            Map<ResourceLocation, Resource> found =
                    resources.listResources(folder, loc -> loc.getPath().endsWith(".json"));

            for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
                ResourceLocation id = entry.getKey();
                JsonObject root;
                try (BufferedReader reader = entry.getValue().openAsReader()) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (!parsed.isJsonObject()) {
                        continue;
                    }
                    root = parsed.getAsJsonObject();
                } catch (Exception e) {
                    continue;
                }

                // Stay in our lane: only advancements that hand out a recipe.
                if (!grantsARecipe(root)) {
                    continue;
                }

                if (!repairCriteria(root)) {
                    continue;
                }

                List<String> granted = grantedRecipes(root);
                repairedRecipes.addAll(granted);

                Path out = packRoot.resolve("data")
                        .resolve(id.getNamespace())
                        .resolve(id.getPath());
                String json = GSON.toJson(root);

                try {
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    if (Files.exists(out) && java.util.Arrays.equals(Files.readAllBytes(out), bytes)) {
                        alreadyCurrent++;
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    Files.write(out, bytes);
                    changed = true;
                } catch (IOException e) {
                    ErrataCore.LOGGER.warn("Could not write repaired advancement {}: {}", id, e.toString());
                }
            }
        }

        if (!repairedRecipes.isEmpty()) {
            try {
                writePackMeta(packRoot);
            } catch (IOException e) {
                ErrataCore.LOGGER.warn("Could not write pack.mcmeta for the repair pack: {}", e.toString());
            }
        }

        repairedRecipes.sort(Comparator.naturalOrder());
        return new Result(repairedRecipes.size(), alreadyCurrent, repairedRecipes, changed);
    }

    private static void writePackMeta(Path packRoot) throws IOException {
        Files.createDirectories(packRoot);
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", ErrataCore.packFormat());
        pack.addProperty("description",
                "Errata - repaired recipe advancements (generated, safe to delete)");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        Files.write(packRoot.resolve("pack.mcmeta"),
                GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean grantsARecipe(JsonObject root) {
        return !grantedRecipes(root).isEmpty();
    }

    /** Package-private rather than private so {@code AdvancementRepairTest} can reach it. */
    static List<String> grantedRecipes(JsonObject root) {
        List<String> out = new ArrayList<>();
        JsonElement rewards = root.get("rewards");
        if (rewards != null && rewards.isJsonObject()) {
            JsonElement recipes = rewards.getAsJsonObject().get("recipes");
            if (recipes != null && recipes.isJsonArray()) {
                for (JsonElement e : recipes.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) {
                        out.add(e.getAsString());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Rewrite every stale item predicate in this advancement, in place.
     *
     * <p>This is the whole repair, and it is pure JSON in / JSON out -- no server, no registries.
     * Package-private rather than private so {@code AdvancementRepairTest} can exercise it
     * directly; it is the piece most likely to break silently on a Minecraft version bump.
     *
     * @return true if anything was actually rewritten.
     */
    static boolean repairCriteria(JsonObject root) {
        JsonElement criteria = root.get("criteria");
        if (criteria == null || !criteria.isJsonObject()) {
            return false;
        }
        boolean touched = false;
        for (Map.Entry<String, JsonElement> e : criteria.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject criterion = e.getValue().getAsJsonObject();
            JsonElement trigger = criterion.get("trigger");
            if (trigger == null || !trigger.isJsonPrimitive()
                    || !"minecraft:inventory_changed".equals(trigger.getAsString())) {
                continue;
            }
            JsonElement conditions = criterion.get("conditions");
            if (conditions == null || !conditions.isJsonObject()) {
                continue;
            }
            JsonElement items = conditions.getAsJsonObject().get("items");
            if (items == null) {
                continue;
            }
            if (items.isJsonObject()) {
                touched |= repairPredicate(items.getAsJsonObject());
            } else if (items.isJsonArray()) {
                for (JsonElement el : items.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        touched |= repairPredicate(el.getAsJsonObject());
                    }
                }
            }
        }
        return touched;
    }

    /**
     * Rewrite one item predicate in place. Only touches predicates that carry no field 1.21.1
     * recognises -- if it already has {@code items}, the mod author updated it and we leave it be.
     */
    private static boolean repairPredicate(JsonObject predicate) {
        if (predicate.has("items") || predicate.has("count")
                || predicate.has("components") || predicate.has("predicates")) {
            return false;
        }
        if (predicate.has("tag")) {
            JsonElement tag = predicate.remove("tag");
            if (tag.isJsonPrimitive()) {
                predicate.addProperty("items", "#" + tag.getAsString());
                return true;
            }
            return false;
        }
        if (predicate.has("item")) {
            JsonElement item = predicate.remove("item");
            if (item.isJsonPrimitive()) {
                predicate.addProperty("items", item.getAsString());
                return true;
            }
            if (item.isJsonArray()) {
                JsonArray copy = item.getAsJsonArray();
                predicate.add("items", copy);
                return true;
            }
        }
        // A genuinely empty predicate is a deliberate "any item" -- not ours to second-guess.
        return false;
    }
}
