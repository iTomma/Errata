# Errata — Support Roadmap

A decision record for which Minecraft versions and platforms Errata targets, and why.
Kept in the repo so the reasoning survives longer than the conversation that produced it.

Last updated: 2026-08-22 · current release: `1.1.0+mc1.21.1`

All compatibility claims below are compiler output, not estimates. Method and commands are
in *How to reproduce these measurements* at the end.

---

## What Errata actually depends on

Worth stating plainly, because it explains every decision below. Errata is a **server-side**
mod that:

1. reads parsed recipes out of `RecipeManager` and decides which have no unlock advancement,
2. writes into the player's `ServerRecipeBook` when they obtain a trigger item,
3. rewrites datapack advancement JSON whose item predicates decay into "match any item".

Points 1 and 2 use internal Minecraft classes (`RecipeHolder`, `Ingredient`,
`ServerRecipeBook`). Point 3 is pure GSON and depends only on the *datapack format*.

That split is why `AdvancementRepair` survives version changes almost untouched while
`RecipeIndex` and `UnlockHandler` break, and why the Bukkit family is a rewrite rather than
a port.

---

## Verified version compatibility

These are **measured**, not estimated. Method: obtain a Mojang-mapped vanilla jar per
version, then compile `common/` against it with `javac` and count real errors.

Mapped jars came from NeoForm's `vanillaDeobfuscated` output for 1.20.2 and up. NeoForm does
not reach 1.20.1 (NeoForge forked from Forge at 1.20.2), so that one was produced by
remapping Mojang's own `client.jar` with their published proguard mappings via
AutoRenamingTool. 26.x required a JDK 25 toolchain — its class files are major version 69,
and `javac 21` cannot read them, so any error count produced with JDK 21 is meaningless.

| Minecraft | Core compiles | Repair correct | Notes |
|---|---|---|---|
| 1.20.1 | **19 errors** | **no** | no `RecipeHolder`, no `AdvancementHolder`; `pack_format` 15 |
| 1.20.2 | 1 error | no | `AdvancementRewards.recipes()` does not exist yet |
| 1.20.4 | clean | **no** | compiles, but repair corrupts valid advancements |
| 1.20.6 | clean | yes | needs `pack_format` change |
| 1.21 | clean | yes | shares `pack_format` 48 with 1.21.1 |
| 1.21.1 | clean | yes | **currently shipping** |
| 1.21.2+ | 8 errors | yes | recipe API rework |
| 26.1.2 / 26.2 | **18 errors** | yes | 10 are one rename; needs Java 25 toolchain |

### The repair core is portable across all of it

Compiling **only** `AdvancementRepair` + `RepairService` + their two dependencies:

| Target | Errors |
|---|---|
| 1.20.1 | **0** |
| 1.20.4 | **0** |
| 1.20.6 | **0** |
| 1.21.1 | **0** |
| 26.2 | 5 — 4 are the `ResourceLocation` rename, 1 is `WorldVersion.packVersion` |

One unmodified source file spans 2023 to 2026. This is the architecture split paying off:
the GSON-only class does not care what Minecraft does to its recipe API. Every version
break lives in `RecipeIndex` and `UnlockHandler`.

Note this is about *compiling*. Whether the repair is semantically **correct** on a given
version is a separate question, answered by the datapack format — see the 1.20.4 hazard.

### The 1.21.2 break

All eight errors land in two files. `AdvancementRepair` and `RepairService` produce zero.

```
RecipeIndex.java:97    ResourceKey<Recipe<?>> cannot be converted to ResourceLocation
RecipeIndex.java:263   cannot find symbol      (Recipe.getIngredients)
RecipeIndex.java:267   cannot find symbol      (Ingredient.getItems)
RecipeIndex.java:289   List<ResourceKey<Recipe<?>>> vs Collection<ResourceLocation>
UnlockHandler.java:238 RecipeHolder cannot be converted to ResourceKey<Recipe<?>>
```

`ServerRecipeBook` was also rebuilt around recipe displays, and `sendInitialRecipeBook` is
gone. The silent-award path in `UnlockHandler` needs re-deriving. Possible upside: the
1.21.2+ recipe-book-add packet appears to carry a per-entry notification flag, which may
let the whole coalescing mechanism collapse into "send the packet with the flag off".
**Unverified — confirm against source before relying on it.**

### The 26.x break

**18 errors**, measured under JDK 25. By cause:

| Cause | Count | Nature |
|---|---|---|
| `ResourceLocation` → `Identifier` | 10 | pure rename |
| `RecipeHolder` → `ResourceKey<Recipe<?>>` | 2 | carried over from 1.21.2 |
| `Recipe.getIngredients()` gone | 1 | carried over from 1.21.2 |
| `Ingredient.getItems()` gone | 1 | now `items()` → `Stream<Holder<Item>>` |
| `getResultItem(Frozen)` signature | 1 | carried over from 1.21.2 |
| `CommandSourceStack.hasPermission(int)` gone | 1 | **new in 26.x** |
| other | 2 | |

Also renamed, found while implementing the repair guard:
`WorldVersion.getPackVersion(PackType)` is now `packVersion(PackType)` and returns a
`PackFormat` object rather than an `int`; `getName()` is now `name()`.

By file: `RecipeIndex` 8, `ErrataCommands` 4, `AdvancementRepair` 4, `UnlockHandler` 2.

Three distinct pieces of work, worth separating:

- **The rename.** `ResourceLocation` is now `net.minecraft.resources.Identifier`. Ten of
  the eighteen errors, and all four in `AdvancementRepair`. Broad but mechanical.
- **The 1.21.2 recipe rework**, inherited. `Ingredient.items()` returning a
  `Stream<Holder<Item>>` is a clean replacement for `getItems()` returning `ItemStack[]`.
- **A new permission system.** `CommandSourceStack.hasPermission(int)` is gone, replaced by
  a `PermissionSet` type: `permissions()`, `withPermission(PermissionSet)`. This affects the
  `requires(source -> source.hasPermission(2))` guard on the command root.

Plus the toolchain: **26.x is compiled for Java 25** (class file major version 69), so the
build's Java 21 pin has to move.

### The 1.20.4-and-below hazard

`ItemPredicate` changed shape twice, not once:

| Era | Fields |
|---|---|
| 1.20.1 | pre-codec entirely — hand-rolled `serializeToJson()`, old `item`/`tag` JSON |
| 1.20.2 – 1.20.4 | `tag` **and** `items` (a list codec) |
| 1.20.5+ | `items` (item-or-tag set), `count`, `components`, `subPredicates` |

On 1.20.5+, `{"tag": "x"}` is stale: the key is ignored, the predicate decodes empty, and
an empty predicate matches every item. Silent, which is the whole reason this mod exists.

On 1.20.4, `{"tag": "x"}` is **correct**. `repairPredicate` skips predicates carrying
`items`/`count`/`components`/`predicates`; a 1.20.4 predicate has none of those, so it
would be rewritten — breaking a working advancement.

**Required before any sub-1.20.5 build ships: a version guard that disables the repair
below 1.20.5.**

### 1.20.1 specifically

Measured against a Mojang-mapped 1.20.1 jar. **19 errors**, from exactly two missing
classes:

| Missing | Occurrences |
|---|---|
| `RecipeHolder` | 16 |
| `AdvancementHolder` | 3 |

All 19 sit in `RecipeIndex` (12) and `UnlockHandler` (7). Both classes arrived in 1.20.2;
in 1.20.1 recipes carry their own id via `Recipe.getId()` instead of being wrapped.

The recipe **book** is not the problem and never was — `ServerRecipeBook` exists in 1.20.1
exactly as it does today. What changed is the plumbing around it.

`pack_format` for 1.20.1 is **15**.

So 1.20.1 is a real port: an id-handling shim across two files, plus the repair guard,
since its predicate JSON is the oldest format of the three. Not a rewrite — `AdvancementRepair`
compiles against it untouched — but not free either.

**1.20.1 means Forge.** NeoForge does not exist below 1.20.2, and the large 1.20.1 modpacks
run Forge. Targeting this version adds a loader, not just a version.

A *reverse* repair (rewriting new-format predicates back for old versions) was considered
and rejected. Backporting is rarer than forward-porting, and a new-format predicate on an
old version fails to *parse* — Minecraft logs the error and skips the advancement. Loud
failures do not need this mod. The asymmetry that justifies Errata only runs one way.

---

## Version axis: a branch per version line

**Decision: one branch per Minecraft version line, keeping the current subproject layout on
each.** Loader subprojects stay as they are; the version axis is git.

```
main        1.21.x   (shipping)
mc/1.20.x            when built
mc/1.21.2            when built
mc/26.x              when built
```

### Why not Stonecutter

[Stonecutter](https://stonecutter.kikugie.dev/) is the single-repo alternative, and it was
the plan until the deltas got measured. Two reasons it does not fit here.

**The version differences are structural, not cosmetic.** Stonecutter earns its keep when
versions differ by a handful of renames that inline cleanly:

```java
//? if >=1.21.2 {
/*ResourceKey<Recipe<?>> id = holder.id();
*///?} else {
ResourceLocation id = holder.id();
//?}
```

That is a good trade for one or two call sites. But `RecipeHolder` does not *exist* before
1.20.2 — 16 of the 19 errors on 1.20.1 are that single missing class, threaded through
`RecipeIndex` and `UnlockHandler`. Expressing "this type is absent and the id lives
somewhere else entirely" as inline conditionals across two files produces something nobody
can read. That is a different implementation, and it deserves to look like one.

**Its topology conflicts with the core boundary.** Stonecutter's idiomatic layout is a
single `src/main/java` with `platform/fabric/` and `platform/neoforge/` packages inside it,
plus per-loader buildscripts — no loader subprojects. Everything then compiles together per
loader, so nothing stops `core` importing NeoForge. The compile-time guarantee becomes a
naming convention. That guarantee is exactly what kept the Fabric port to two files, and it
is worth more here than single-branch convenience.

Branch-per-version is also the majority practice in the ecosystem — JEI, Jade and
jaredlll08's MultiLoader-Template all do it.

### What makes branching cheap here

The usual objection to branches is that shared fixes must be ported by hand. Measured, the
shared surface is large and the divergent surface is small:

- `AdvancementRepair` + `RepairService`: **0 errors** on 1.20.1, 1.20.4, 1.20.6 and 1.21.1.
  Identical file on every branch; a fix cherry-picks cleanly.
- `ErrataConfig`, `ErrataCore`, both loader entrypoints: unchanged across the 1.20–1.21
  range.
- Divergence is confined to `RecipeIndex` and `UnlockHandler`.

So a bug fix in the repair logic is one cherry-pick per branch with no conflicts. Only
recipe-indexing changes need per-branch thought — which is the honest cost, since those are
genuinely different code.

### Layout: one worktree per line, no switching

`git checkout` between version lines is the wrong workflow here — it churns the working
tree, invalidates editor and tooling state, and makes comparing two versions impossible.
Instead every line is checked out **simultaneously** as a
[git worktree](https://git-scm.com/docs/git-worktree):

```
Errata/                 core          shared trunk, buildable, tests green
  mc-1.21.1/            mc/1.21-1.21.1
  mc-1.21.11/           mc/1.21.2-1.21.11
  mc-1.20.1/            mc/1.20-1.20.1
  mc-26.2/              mc/26.1-26.2
```

One clone, one `.git`, every line on disk at once. Read 1.20.1's `RecipeIndex` next to
1.21.11's, grep across all lines, build any of them independently. Nothing is ever switched.

```bash
git worktree add mc-1.21.11 -b mc/1.21.2-1.21.11   # create
git worktree list                                   # inspect
git worktree remove mc-1.21.11                      # tear down
```

The `mc-` prefix earns its keep: `.gitignore` needs one pattern (`/mc-*/`) rather than a
line per version, and the root listing visually separates worktrees from real project
directories.

### Conventions

- Branch names: `mc/<supported range>`, e.g. `mc/1.21.2-1.21.11`. The name states exactly
  what the branch supports, which is also what goes in `supported_versions`.
- **`core` ships nothing.** It is the shared trunk: a full buildable tree pinned to a
  reference version so its tests actually run. Releases are tagged on version branches.
- **Merges flow `core` → version, never back.** Merging a version branch into `core` would
  drag version-specific code into the trunk and poison every other line.
- Shared fixes land on `core`, then `git merge core` in each worktree. The four invariant
  files auto-merge every time; `RecipeIndex` and `UnlockHandler` may conflict, which is git
  correctly pointing at the two files that need per-version thought. If that becomes
  tedious, `.gitattributes` with `merge=ours` on those paths stops core merges touching
  them.
- `ROADMAP.md` is shared — edit on `core` and merge outward.

---

## Platform decisions

### In scope

| Platform | Status | Cost |
|---|---|---|
| NeoForge | shipping | — |
| Fabric | shipping | — |
| **Forge** | **planned** | **required for 1.20.1 — NeoForge does not exist below 1.20.2** |
| Quilt | planned | near-zero; Quilt loads Fabric jars |

Forge is the consequential one. The large 1.20.1 modpacks run Forge, so targeting 1.20.1
means adding a loader, not just a version.

### Deferred — Bukkit family

Paper, Spigot, Purpur, Folia. Technically possible: Bukkit exposes
`Player.discoverRecipe()` and datapacks work. But:

- The `core` package does **not** carry over. It is built on `ServerRecipeBook`,
  `RecipeManager` and `RecipeHolder`, which Bukkit does not expose. Going through NMS
  instead trades that for per-Paper-build version locking, which fights the multi-version
  goal directly.
- The premise shifts. Errata fixes *mods* shipping recipes without unlock advancements.
  Paper runs plugins, not mods. Plugin-registered and datapack recipes have the same
  undiscovered-recipe problem, so the use case is real — but smaller and different.
- **Folia additionally needs thread-safety work.** It is region-threaded;
  `UnlockHandler`'s static `STATES` map behind `synchronized` blocks assumes a single main
  thread.

Revisit only on actual demand.

### Rejected — proxies

BungeeCord, Velocity, Waterfall. **Not deferred — rejected.**

A proxy has no world, no `RecipeManager`, no player inventories and no datapacks. There is
nothing for Errata to do there. Simple Voice Chat supports proxies because voice is a UDP
stream that benefits from routing through one public port — and its own docs confirm the
mod must still be installed on every backend server. That proxy plugin is a packet
forwarder, not a port of the mod. Errata has no analogous routing need.

---

## Known hazards

### Resolved

1. ~~**Declared version ranges are too loose.**~~ Bounded at 1.21.2, where the recipe API
   rework begins:
   ```
   minecraft_version_range=[1.21,1.21.2)
   neo_version_range=[21.0.0,21.2)
   fabric_minecraft_range=>=1.21 <1.21.2
   ```
   A player on 1.21.4 now gets a clean "incompatible version" message instead of a
   `NoSuchMethodError` on the first recipe scan.

2. ~~**`pack_format` is a per-version constant someone must remember to change.**~~ Now read
   from the running game via `SharedConstants.getCurrentVersion().getPackVersion(...)`,
   which has been stable from 1.20.1 through 26.2. One fewer number to get wrong per port.

3. ~~**Repair must be disabled below 1.20.5.**~~ `RepairService.run` now refuses when the
   data pack format is below 41, and says why. See the 1.20.4 hazard above for the
   reasoning.

### Open

4. **Loom and ModDevGradle disagree about Gradle.** Loom 1.17+ requires Gradle 9;
   ModDevGradle is a Gradle 8 plugin. `fabric/build.gradle` is pinned to Loom 1.9.2 for
   this reason. Do not bump it until ModDevGradle moves to Gradle 9.

5. **Only `AdvancementRepair` has tests.** `RecipeIndex`'s distinctive-trigger scoring is
   equally testable and currently uncovered. `UnlockHandler` would need GameTest.

6. **Run verification is partial.** `1.1.0+mc1.21.1-neoforge` has been observed working in a
   ~380 mod NeoForge pack on 1.21.1:

   ```
   Indexed 1225 of 7236 recipes for automatic unlocking
     (5344 already covered by an advancement, 597 special/filtered,
      403 unlocked silently, 0 unreadable, 70 ingredient-less,
      1385 trigger items, 3 fell back to any ingredient)
   Repaired 2 recipe advancement(s) that would have unlocked on any pickup:
     morevillagers:gardening_table, morevillagers:woodworking_table
   Enabling repair datapack and reloading.
   iTomma granted 70 ingredient-less recipe(s) silently on join.
   ```

   Confirmed working: config binding and load order, index build, advancement repair,
   datapack generation and hot reload, rebuild-after-reload idempotence (identical counts
   before and after), join-time silent grants, and the runtime-derived `pack_format`
   (the generated `pack.mcmeta` reads 48, taken from the game rather than a constant).
   No errors or warnings from the mod.

   **Still unobserved:** the announced-unlock path. No recipe was triggered by a pickup
   during that session, so the toast, `player.awardRecipes`, and the `flushBook` coalescing
   window have not been seen running. The `/errata` commands are also untested in-game.

---

## Open questions

Answered:

- ~~Does the core compile on 1.20.1, and does `RecipeHolder` exist there?~~ 19 errors;
  `RecipeHolder` and `AdvancementHolder` do not exist before 1.20.2. `ServerRecipeBook`
  does.
- ~~What is the real 26.x error count under a JDK 25 toolchain?~~ 18, of which 10 are the
  `ResourceLocation` → `Identifier` rename.

Still open:

- Does the 1.21.2+ recipe-book-add packet's notification flag do what it appears to? If so,
  `UnlockHandler`'s silent-award coalescing collapses to a single flag and the class gets
  simpler on newer versions, not harder.
- What replaces `hasPermission(int)` idiomatically in 26.x? `PermissionSet` exists; the
  right way to express "permission level 2" against it is not yet established here.
- Is there demand for the Paper family, or is that speculative?
- Does the Fabric jar actually load and behave on Quilt, or is that assumption untested?

---

## How to reproduce these measurements

Each row above came from the same three steps. Worth repeating whenever a new version drops,
before committing to support it.

1. **Get a mapped jar.** For 1.20.2+, NeoForm's runtime can emit one without the expensive
   decompile step:
   ```
   java -jar neoform-runtime-all.jar run --dist joined \
     --neoform net.neoforged:neoform:<version>@zip \
     --write-result=vanillaDeobfuscated:mc-<version>.jar
   ```
   For versions NeoForm does not cover, remap Mojang's `client.jar` with their published
   mappings using AutoRenamingTool and `--reverse`.
2. **Compile `common/` against it** with a JDK matching that version's class file level,
   and count errors by file and by missing symbol.
3. **Compile `AdvancementRepair` + `RepairService` alone** to separate "the repair still
   works" from "the recipe API moved". These fail for very different reasons and should be
   tracked separately.
