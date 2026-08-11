# Errata

*A published list of corrections for your recipe book.*

Minecraft 1.21.1 | NeoForge 21.1.248+ | MIT

An errata is the sheet of corrections a publisher issues for a book that shipped with mistakes.
That is this mod's whole job. Modded recipe books ship with two kinds of mistake, and Errata fixes
both.

**Recipes that never unlock.** Vanilla only puts a recipe in the book when a matching
`minecraft:recipes/...` advancement fires. Plenty of mods ship recipes and forget the advancement,
so those recipes are invisible forever. Errata unlocks them directly.

**Advancements that unlock everything.** In 1.20.5 the item-predicate fields `item` and `tag` were
replaced by a single `items` field. Mods still using the old keys produce an *empty* predicate,
which matches every item, so the recipe unlocks on the player's next pickup, whatever it is. Errata
rewrites those into correct syntax.

---

## How it decides

Errata reads the parsed `Recipe` objects out of the server's recipe manager rather than parsing
JSON. `Ingredient.getItems()` resolves tags for it, including nested ones, so every recipe type
(shaped, shapeless, cooking, stonecutting, smithing, and anything a mod invents) goes through the
same code path. Recipes registered in code, which have no JSON file at all, are covered too.

It leaves alone any recipe an advancement already unlocks, so mods with working progression are
untouched.

## Announced vs silent

Only four recipe book UIs exist in 1.21.1 (crafting table, furnace, blast furnace, smoker), so the
book can display any `CraftingRecipe` plus `smelting`, `blasting` and `smoking`. That's from
`ClientRecipeBook.getCategory` and `RecipeBookCategories.getCategories`. Everything else makes the
client log `Unknown recipe category` and drop it. Note this covers vanilla `campfire_cooking`,
`stonecutting` and `smithing` too: they get a category assigned, but that category belongs to no
book, so they never render.

Those recipes are still unlocked, just silently. Being marked known matters to stations and recipe
viewers that key off the recipe book; a toast pointing at a page that doesn't exist is only noise.

| | trigger | notification |
|---|---|---|
| book can show it | distinctive ingredient | toast |
| book cannot show it | any ingredient | none |
| no readable ingredients | granted on join | none |

Book-less recipes use any-ingredient on purpose. With no notification cost, the only thing worth
optimising is availability.

## The distinctive-ingredient rule

Unlocking on *any* ingredient turns staple items into firehoses. In a 230-mod pack, one stick
unlocked 162 recipes; oak planks unlocked a Corrupted Shield and an Archeology Table. The result has
nothing to do with what you picked up, so it reads as random.

So each ingredient slot is scored by **how many managed recipes its most common member appears in**,
and the rarest slot becomes the trigger. Scoring by the *most* common member is the important part:
`#minecraft:planks` contains obscure modded planks, but it also contains oak planks, so the whole
slot scores badly and never becomes a trigger.

```
endermanoverhaul:corrupted_shield   #minecraft:planks | iron_ingot | enderman_tooth
                                                                     ^ trigger
```

Measured on that pack at `commonItemThreshold = 20`:

```
                  any ingredient   distinctive
minecraft:stick              162             1
minecraft:sugar               60             2
minecraft:oak_planks           6             2
worst item in pack           162            14
```

One recipe in 868 had no distinctive ingredient at all; those fall back to any-ingredient rather
than becoming undiscoverable, and `/errata why` flags them.

The first scan after each login is silent regardless of type, because whatever you were already
carrying isn't a discovery.

## Repairing broken advancements

`ItemPredicate.matches` reads:

```java
if (this.items.isPresent() && !stack.is(this.items.get())) return false;
```

An absent `items` skips the item check entirely, so a predicate written the pre-1.20.5 way matches
everything. On world load Errata scans every advancement that grants a recipe, finds these, and
writes corrected copies into a generated datapack:

```
<world>/datapacks/errata_repairs/
    pack.mcmeta
    data/<namespace>/advancement/.../<file>.json
```

The rewrite is mechanical: `{"tag": "x"}` becomes `{"items": "#x"}`, `{"item": "x"}` becomes
`{"items": "x"}`. It only touches predicates carrying no field 1.21.1 recognises, so an updated mod
is left alone, and a genuinely empty `{}` is treated as a deliberate "any item" and not
second-guessed.

Mod jars are never modified. Deleting the generated folder undoes everything. The datapack is
enabled and a reload issued only on the load where contents actually change.

Once repaired, the recipe has a working advancement, so Errata goes back to ignoring it and the
mod's own intended trigger takes over.

## Config

`config/errata-common.toml`, generated on first run.

| Key | Default | Meaning |
|---|---|---|
| `onlyRecipesWithoutAdvancements` | `true` | Leave advancement-covered recipes alone |
| `manageBookLessRecipes` | `true` | Silently unlock recipes the book can't render |
| `extraDisplayableTypes` | `[]` | Extra recipe type ids to treat as displayable |
| `distinctiveTriggers` | `true` | Trigger on the rarest ingredient, not any ingredient |
| `commonItemThreshold` | `20` | Appearing in more than this many recipes makes an item a staple |
| `requireAllIngredients` | `false` | Require every ingredient instead of one |
| `grantIngredientlessRecipes` | `true` | Silently grant recipes with no readable ingredients on join |
| `scanIntervalTicks` | `20` | Inventory check frequency; 20 = once per second |
| `repairBrokenAdvancements` | `true` | Fix pre-1.20.5 advancements that fire on any pickup |
| `logUnlocks` | `true` | Log every unlock with the item that triggered it |
| `logSummary` | `true` | Log index stats on build |
| `namespaceAllowlist` | `[]` | If non-empty, only these namespaces are managed |
| `namespaceDenylist` | `[]` | Never manage these namespaces |

`requireAllIngredients` caveat: the set of items a player has been seen holding is tracked per
session, not saved to disk, so partial progress resets on logout and is re-derived from the
inventory at login. Recipes already unlocked stay unlocked.

## Commands

Permission level 2.

- `/errata stats`: how many recipes were adopted, silent, skipped, unreadable
- `/errata triggers <item>`: what picking up this item would unlock
- `/errata why <item>`: for recipes producing this item, which items unlock them
- `/errata repair`: rescan for and fix broken advancements now
- `/errata rebuild`: rebuild the index
- `/errata unlockall`: grant every managed recipe to yourself

If an unlock ever looks unexplained, check the log for a matching `unlocked N recipe(s)` line. It
names the exact trigger item. If there isn't one, it wasn't Errata.

## Performance

The index is built once at server start and once per `/reload`. Per player, a steady-state scan is
~41 identity-set lookups with no allocation; work only happens the first time an item enters an
inventory. Silent additions update the server-side book immediately and coalesce the client sync to
at most once every 60 ticks, because the only no-toast path is the INIT packet, which carries every
known recipe id.

## Building

Requires JDK 21.

```
gradle build
```

`build.gradle` uses ModDevGradle against NeoForge 21.1.248. No reobfuscation step is needed:
NeoForge 1.20.2+ runs on official Mojang mappings in production.
