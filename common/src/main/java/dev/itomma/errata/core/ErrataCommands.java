package dev.itomma.errata.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code /errata} command tree.
 *
 * <p>Brigadier is vanilla, so this lives in common and both loaders register the same tree.
 * NeoForge and Fabric differ only in how they hand over the dispatcher and build context.
 */
public final class ErrataCommands {

    /** How many trigger items {@code /errata why} lists before truncating. */
    private static final int MAX_TRIGGERS_SHOWN = 12;

    private ErrataCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ErrataCore.MOD_ID)
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("stats").executes(ctx -> {
            RecipeIndex index = UnlockHandler.index();
            if (index == null) {
                ctx.getSource().sendFailure(Component.literal("Recipe index has not been built yet."));
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "Errata: managing %d of %d recipes. "
                            + "%d already had an advancement, %d special/filtered, "
                            + "%d silent (book cannot show them), %d unreadable, "
                            + "%d have no usable ingredients. %d distinct trigger items, "
                            + "%d fall back to any ingredient.",
                    index.managedRecipeCount(), index.totalRecipes(), index.skippedHadAdvancement(),
                    index.skippedFiltered(), index.silentBookLess(), index.errored(),
                    index.ingredientless().size(), index.indexedItemCount(), index.fellBack())), false);
            return 1;
        }));

        root.then(Commands.literal("repair").executes(ctx -> {
            RepairService.Outcome outcome = RepairService.run(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(
                    () -> Component.literal(outcome.message()), outcome.repaired() > 0);
            return 1;
        }));

        root.then(Commands.literal("rebuild").executes(ctx -> {
            UnlockHandler.rebuild(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal("Recipe index rebuilt."), true);
            return 1;
        }));

        // "What does picking this up unlock?"
        root.then(Commands.literal("triggers")
                .then(Commands.argument("item", ItemArgument.item(buildContext))
                        .executes(ctx -> {
                            RecipeIndex index = UnlockHandler.index();
                            if (index == null) {
                                ctx.getSource().sendFailure(
                                        Component.literal("Recipe index has not been built yet."));
                                return 0;
                            }
                            Item item = ItemArgument.getItem(ctx, "item").getItem();
                            var managed = index.forItem(item);
                            if (managed.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        BuiltInRegistries.ITEM.getKey(item)
                                                + " unlocks nothing managed by this mod."), false);
                                return 0;
                            }
                            StringBuilder sb = new StringBuilder(
                                    BuiltInRegistries.ITEM.getKey(item) + " unlocks "
                                            + managed.size() + " recipe(s):");
                            for (RecipeIndex.Managed m : managed) {
                                sb.append("\n  ").append(m.holder().id());
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return managed.size();
                        })));

        // "Why did THIS unlock?" -- lists the items that would have triggered it.
        root.then(Commands.literal("why")
                .then(Commands.argument("item", ItemArgument.item(buildContext))
                        .executes(ctx -> {
                            RecipeIndex index = UnlockHandler.index();
                            if (index == null) {
                                ctx.getSource().sendFailure(
                                        Component.literal("Recipe index has not been built yet."));
                                return 0;
                            }
                            Item wanted = ItemArgument.getItem(ctx, "item").getItem();
                            var registries = ctx.getSource().getServer().registryAccess();
                            StringBuilder sb = new StringBuilder();
                            int found = 0;
                            for (RecipeIndex.Managed m : index.allManaged()) {
                                ItemStack result;
                                try {
                                    result = m.holder().value().getResultItem(registries);
                                } catch (Throwable t) {
                                    continue;
                                }
                                if (result.isEmpty() || result.getItem() != wanted) {
                                    continue;
                                }
                                found++;
                                sb.append("\n  ").append(m.holder().id())
                                        .append(m.distinctive()
                                                ? " unlocks when you obtain:"
                                                : " has no distinctive ingredient, so it unlocks on any of:");
                                Set<String> names = new TreeSet<>();
                                for (Item i : m.triggers()) {
                                    names.add(String.valueOf(BuiltInRegistries.ITEM.getKey(i)));
                                }
                                int shown = 0;
                                for (String n : names) {
                                    if (shown++ == MAX_TRIGGERS_SHOWN) {
                                        sb.append(" ... (+")
                                                .append(names.size() - MAX_TRIGGERS_SHOWN)
                                                .append(" more)");
                                        break;
                                    }
                                    sb.append(shown == 1 ? " " : ", ").append(n);
                                }
                            }
                            if (found == 0) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "No managed recipe produces "
                                                + BuiltInRegistries.ITEM.getKey(wanted) + "."), false);
                                return 0;
                            }
                            String head = found + " managed recipe(s) produce "
                                    + BuiltInRegistries.ITEM.getKey(wanted) + ":";
                            ctx.getSource().sendSuccess(() -> Component.literal(head + sb), false);
                            return found;
                        })));

        root.then(Commands.literal("unlockall").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            int count = UnlockHandler.grantEverything(player);
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Granted " + count + " managed recipes."), true);
            return count;
        }));

        dispatcher.register(root);
    }
}
