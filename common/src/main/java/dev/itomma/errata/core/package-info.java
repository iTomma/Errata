/**
 * Loader-agnostic core. Everything Errata actually does lives here.
 *
 * <h2>The rule</h2>
 * No class in this package may import {@code net.neoforged.*}, {@code net.fabricmc.*} or any
 * other loader API. Permitted dependencies are vanilla Minecraft, SLF4J and GSON. If you find
 * yourself needing something loader-specific, add a method to {@link ErrataConfig} or a field to
 * {@link ErrataCore} instead, and let the entrypoint supply it.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ErrataCore} -- the seam. Holds the logger, the bound config, and the
 *       version-dependent {@code pack_format}. An entrypoint calls {@code bind} once at startup.</li>
 *   <li>{@link ErrataConfig} -- settings as a plain interface, so NeoForge's {@code ModConfigSpec}
 *       and a hand-rolled Fabric JSON config are interchangeable.</li>
 *   <li>{@link AdvancementRepair} -- rewrites pre-1.20.5 recipe advancements whose item predicates
 *       silently decay into "match any item". Pure GSON over datapack JSON; the most portable
 *       class in the mod.</li>
 *   <li>{@link RepairService} -- runs the repair and gets the generated datapack live.</li>
 *   <li>{@link RecipeIndex} -- decides which recipes are ours and which items should trigger them.</li>
 *   <li>{@link UnlockHandler} -- per-player bookkeeping and the actual awarding.</li>
 * </ul>
 *
 * <h2>Porting notes</h2>
 * A new <em>loader</em> needs a new entrypoint and a new {@link ErrataConfig} implementation;
 * nothing here changes.
 *
 * <p>A new <em>Minecraft version</em> is a different matter. 1.21.2 reworked the recipe APIs this
 * package depends on: {@code RecipeHolder.id()} returns a {@code ResourceKey},
 * {@code Ingredient.getItems()} is gone, and {@code ServerRecipeBook} was rebuilt around recipe
 * displays. {@code RecipeIndex.ingredientOptions} and {@link UnlockHandler}'s silent-award path are
 * the two places that will need real work; {@link AdvancementRepair} should survive nearly intact.
 */
package dev.itomma.errata.core;
