package dev.itomma.errata.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.itomma.errata.core.ErrataConfig;
import dev.itomma.errata.core.ErrataCore;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fabric has no config API, so this is a hand-rolled JSON equivalent of the NeoForge
 * {@code ModConfigSpec}.
 *
 * <p>Behaviour is deliberately forgiving: an unreadable file, a missing key or a value of the
 * wrong type all fall back to the default rather than crashing the server. A malformed config
 * should never stop a world from loading. Anything missing is written back on save, so the file
 * heals itself and picks up newly added options on upgrade.
 */
public final class FabricConfig implements ErrataConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private volatile JsonObject root = new JsonObject();

    private FabricConfig(Path path) {
        this.path = path;
    }

    /** Load from disk, creating a fully commented default file if none exists. */
    public static FabricConfig load(Path path) {
        FabricConfig config = new FabricConfig(path);
        config.reload();
        return config;
    }

    public void reload() {
        JsonObject loaded = new JsonObject();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    loaded = parsed.getAsJsonObject();
                } else {
                    ErrataCore.LOGGER.warn("{} is not a JSON object; using defaults.", path);
                }
            } catch (Exception e) {
                ErrataCore.LOGGER.warn("Could not read {} ({}); using defaults.", path, e.toString());
            }
        }
        this.root = loaded;
        save();
    }

    /** Write the current values back, filling in anything absent. */
    public void save() {
        JsonObject out = new JsonObject();
        out.addProperty("_comment", "Errata configuration. Delete any key to restore its default.");

        out.addProperty("onlyRecipesWithoutAdvancements", onlyRecipesWithoutAdvancements());
        out.addProperty("distinctiveTriggers", distinctiveTriggers());
        out.addProperty("commonItemThreshold", commonItemThreshold());
        out.addProperty("requireAllIngredients", requireAllIngredients());
        out.addProperty("manageBookLessRecipes", manageBookLessRecipes());
        out.add("extraDisplayableTypes", toArray(extraDisplayableTypes()));
        out.addProperty("grantIngredientlessRecipes", grantIngredientlessRecipes());
        out.addProperty("scanIntervalTicks", scanIntervalTicks());
        out.addProperty("logSummary", logSummary());
        out.addProperty("logUnlocks", logUnlocks());
        out.addProperty("repairBrokenAdvancements", repairBrokenAdvancements());
        out.add("namespaceAllowlist", toArray(namespaceAllowlist()));
        out.add("namespaceDenylist", toArray(namespaceDenylist()));

        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            byte[] bytes = GSON.toJson(out).getBytes(StandardCharsets.UTF_8);
            if (Files.exists(path) && java.util.Arrays.equals(Files.readAllBytes(path), bytes)) {
                return;
            }
            Files.write(path, bytes);
        } catch (IOException e) {
            ErrataCore.LOGGER.warn("Could not write {}: {}", path, e.toString());
        }
        this.root = out;
    }

    private static com.google.gson.JsonArray toArray(List<String> values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        values.forEach(array::add);
        return array;
    }

    // ---- readers: every one falls back to the shared default on any problem ----

    private boolean bool(String key, boolean fallback) {
        JsonElement e = root.get(key);
        try {
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()
                    ? e.getAsBoolean() : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private int clampedInt(String key, int fallback, int min, int max) {
        JsonElement e = root.get(key);
        try {
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return fallback;
            }
            return Math.clamp(e.getAsLong(), min, max);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private List<String> strings(String key) {
        JsonElement e = root.get(key);
        if (e == null || !e.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement el : e.getAsJsonArray()) {
            if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public boolean onlyRecipesWithoutAdvancements() {
        return bool("onlyRecipesWithoutAdvancements", DEFAULTS.onlyRecipesWithoutAdvancements());
    }

    @Override
    public boolean distinctiveTriggers() {
        return bool("distinctiveTriggers", DEFAULTS.distinctiveTriggers());
    }

    @Override
    public int commonItemThreshold() {
        return clampedInt("commonItemThreshold", DEFAULTS.commonItemThreshold(), 1, 10000);
    }

    @Override
    public boolean requireAllIngredients() {
        return bool("requireAllIngredients", DEFAULTS.requireAllIngredients());
    }

    @Override
    public boolean manageBookLessRecipes() {
        return bool("manageBookLessRecipes", DEFAULTS.manageBookLessRecipes());
    }

    @Override
    public List<String> extraDisplayableTypes() {
        return strings("extraDisplayableTypes");
    }

    @Override
    public boolean grantIngredientlessRecipes() {
        return bool("grantIngredientlessRecipes", DEFAULTS.grantIngredientlessRecipes());
    }

    @Override
    public int scanIntervalTicks() {
        return clampedInt("scanIntervalTicks", DEFAULTS.scanIntervalTicks(), 1, 1200);
    }

    @Override
    public boolean logSummary() {
        return bool("logSummary", DEFAULTS.logSummary());
    }

    @Override
    public boolean logUnlocks() {
        return bool("logUnlocks", DEFAULTS.logUnlocks());
    }

    @Override
    public boolean repairBrokenAdvancements() {
        return bool("repairBrokenAdvancements", DEFAULTS.repairBrokenAdvancements());
    }

    @Override
    public List<String> namespaceAllowlist() {
        return strings("namespaceAllowlist");
    }

    @Override
    public List<String> namespaceDenylist() {
        return strings("namespaceDenylist");
    }
}
