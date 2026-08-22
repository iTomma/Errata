package dev.itomma.errata.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the advancement repair, which is pure JSON in / JSON out and needs no running game.
 *
 * <p>The bug being fixed: in 1.20.5 the item-predicate keys {@code item} and {@code tag} were
 * replaced by a single {@code items} field. Mojang's codecs ignore unknown keys, so an
 * advancement written the old way decodes to an <em>empty</em> predicate -- which matches every
 * item. The recipe then unlocks on the player's next pickup, whatever it happens to be.
 */
class AdvancementRepairTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /** An advancement with one inventory_changed criterion carrying the given predicate JSON. */
    private static JsonObject advancementWith(String predicateJson) {
        return json("""
                {
                  "criteria": {
                    "has_item": {
                      "trigger": "minecraft:inventory_changed",
                      "conditions": { "items": [ %s ] }
                    }
                  },
                  "rewards": { "recipes": [ "examplemod:corrupted_shield" ] }
                }
                """.formatted(predicateJson));
    }

    private static String firstPredicate(JsonObject advancement) {
        return advancement
                .getAsJsonObject("criteria")
                .getAsJsonObject("has_item")
                .getAsJsonObject("conditions")
                .getAsJsonArray("items")
                .get(0).toString();
    }

    @Nested
    @DisplayName("repairs stale predicates")
    class Repairs {

        @Test
        @DisplayName("tag becomes an items reference with a # prefix")
        void tagBecomesHashItems() {
            JsonObject adv = advancementWith("{\"tag\": \"minecraft:soul_fire_base_blocks\"}");

            assertTrue(AdvancementRepair.repairCriteria(adv), "should report a change");
            assertEquals("{\"items\":\"#minecraft:soul_fire_base_blocks\"}", firstPredicate(adv));
        }

        @Test
        @DisplayName("item becomes items verbatim")
        void itemBecomesItems() {
            JsonObject adv = advancementWith("{\"item\": \"minecraft:diamond\"}");

            assertTrue(AdvancementRepair.repairCriteria(adv));
            assertEquals("{\"items\":\"minecraft:diamond\"}", firstPredicate(adv));
        }

        @Test
        @DisplayName("an array of items is carried across intact")
        void itemArrayIsPreserved() {
            JsonObject adv = advancementWith(
                    "{\"item\": [\"minecraft:diamond\", \"minecraft:emerald\"]}");

            assertTrue(AdvancementRepair.repairCriteria(adv));
            assertEquals("{\"items\":[\"minecraft:diamond\",\"minecraft:emerald\"]}",
                    firstPredicate(adv));
        }

        @Test
        @DisplayName("a predicate written as an object rather than an array is still repaired")
        void singleObjectPredicate() {
            JsonObject adv = json("""
                    {
                      "criteria": {
                        "has_item": {
                          "trigger": "minecraft:inventory_changed",
                          "conditions": { "items": { "tag": "c:ingots" } }
                        }
                      },
                      "rewards": { "recipes": [ "examplemod:thing" ] }
                    }
                    """);

            assertTrue(AdvancementRepair.repairCriteria(adv));
            assertEquals("{\"items\":\"#c:ingots\"}",
                    adv.getAsJsonObject("criteria").getAsJsonObject("has_item")
                            .getAsJsonObject("conditions").getAsJsonObject("items").toString());
        }
    }

    @Nested
    @DisplayName("leaves well-formed advancements alone")
    class LeavesAlone {

        @Test
        @DisplayName("a predicate already using items is untouched")
        void alreadyCurrent() {
            JsonObject adv = advancementWith("{\"items\": \"minecraft:diamond\"}");
            String before = adv.toString();

            assertFalse(AdvancementRepair.repairCriteria(adv), "should report no change");
            assertEquals(before, adv.toString());
        }

        @Test
        @DisplayName("a deliberately empty predicate is not second-guessed")
        void genuinelyEmptyPredicate() {
            JsonObject adv = advancementWith("{}");

            assertFalse(AdvancementRepair.repairCriteria(adv));
            assertEquals("{}", firstPredicate(adv));
        }

        @Test
        @DisplayName("a predicate carrying count is treated as already updated")
        void predicateWithCountIsModern() {
            JsonObject adv = advancementWith("{\"item\": \"minecraft:diamond\", \"count\": 3}");

            assertFalse(AdvancementRepair.repairCriteria(adv),
                    "count is a 1.20.5+ field, so the author already migrated this one");
        }

        @Test
        @DisplayName("triggers other than inventory_changed are ignored")
        void otherTriggersIgnored() {
            JsonObject adv = json("""
                    {
                      "criteria": {
                        "killed": {
                          "trigger": "minecraft:player_killed_entity",
                          "conditions": { "items": [ { "tag": "c:ingots" } ] }
                        }
                      },
                      "rewards": { "recipes": [ "examplemod:thing" ] }
                    }
                    """);

            assertFalse(AdvancementRepair.repairCriteria(adv));
        }

        @Test
        @DisplayName("an advancement with no criteria at all does not throw")
        void noCriteria() {
            assertFalse(AdvancementRepair.repairCriteria(json("{}")));
        }
    }

    @Nested
    @DisplayName("the pre-1.20.5 guard threshold")
    class GuardThreshold {

        /**
         * Measured data pack formats, read from each version's own {@code version.json}.
         * The item predicate format changed in 1.20.5, which lands exactly on the 26 -> 41
         * boundary -- so the pack format is a sound proxy for "is the repair meaningful here",
         * and unlike the version string it survives the 2026 move to year-based numbering.
         */
        @Test
        @DisplayName("sits between 1.20.4 (26) and 1.20.6 (41)")
        void thresholdMatchesMeasuredPackFormats() {
            int v1_20_1 = 15, v1_20_4 = 26, v1_20_6 = 41, v1_21_1 = 48;
            int threshold = ErrataCore.FIRST_MODERN_PREDICATE_PACK_FORMAT;

            assertTrue(v1_20_1 < threshold, "1.20.1 must not be repaired");
            assertTrue(v1_20_4 < threshold, "1.20.4 must not be repaired");
            assertTrue(v1_20_6 >= threshold, "1.20.6 must be repaired");
            assertTrue(v1_21_1 >= threshold, "1.21.1 must be repaired");
        }
    }

    @Nested
    @DisplayName("identifies which advancements grant recipes")
    class GrantedRecipes {

        @Test
        @DisplayName("reads every recipe id out of rewards")
        void readsRewards() {
            JsonObject adv = json("""
                    { "rewards": { "recipes": [ "a:one", "b:two" ] } }
                    """);

            assertEquals(List.of("a:one", "b:two"), AdvancementRepair.grantedRecipes(adv));
        }

        @Test
        @DisplayName("an advancement granting no recipe is out of scope")
        void noRecipeReward() {
            assertTrue(AdvancementRepair.grantedRecipes(
                    json("{ \"rewards\": { \"experience\": 10 } }")).isEmpty());
            assertTrue(AdvancementRepair.grantedRecipes(json("{}")).isEmpty());
        }
    }
}
