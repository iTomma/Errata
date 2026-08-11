package dev.itomma.errata;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Errata.MOD_ID)
public class Errata {

    public static final String MOD_ID = "errata";
    public static final Logger LOGGER = LoggerFactory.getLogger("Errata");

    public Errata(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    @EventBusSubscriber(modid = MOD_ID)
    public static final class GameEvents {

        /** Set once per server start so a repair can never bounce the reload in a loop. */
        private static boolean repairAttempted = false;

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            MinecraftServer server = event.getServer();
            UnlockHandler.rebuild(server);

            if (!repairAttempted && Config.REPAIR_BROKEN_ADVANCEMENTS.get()) {
                repairAttempted = true;
                applyRepair(server, null);
            }
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent stopped) {
            repairAttempted = false;
        }

        /**
         * Rewrite broken advancements into the generated datapack, and reload only if the
         * contents actually changed. The reload fires OnDatapackSyncEvent, which rebuilds the
         * index against the now-correct advancements.
         */
        private static void applyRepair(MinecraftServer server, CommandSourceStack feedback) {
            AdvancementRepair.Result result;
            try {
                result = AdvancementRepair.run(server);
            } catch (Throwable t) {
                LOGGER.warn("Advancement repair failed: {}", t.toString());
                return;
            }

            if (result.repaired() == 0) {
                LOGGER.info("No broken recipe advancements found.");
                if (feedback != null) {
                    feedback.sendSuccess(() -> Component.literal("No broken recipe advancements found."), false);
                }
                return;
            }

            LOGGER.info("Repaired {} recipe advancement(s) that would have unlocked on any pickup: {}",
                    result.repaired(), String.join(", ", result.recipes()));

            if (feedback != null) {
                feedback.sendSuccess(() -> Component.literal(
                        "Repaired " + result.repaired() + " advancement(s): "
                                + String.join(", ", result.recipes())), true);
            }

            if (!result.changed()) {
                return;  // already written on a previous run; the pack is live
            }

            try {
                PackRepository repo = server.getPackRepository();
                repo.reload();
                List<String> selected = new ArrayList<>(repo.getSelectedIds());
                String id = "file/" + AdvancementRepair.PACK_DIR;
                if (repo.getAvailableIds().contains(id) && !selected.contains(id)) {
                    selected.add(id);
                }
                LOGGER.info("Enabling repair datapack and reloading.");
                server.reloadResources(selected);
            } catch (Throwable t) {
                LOGGER.warn("Wrote the repairs but could not reload automatically ({}). "
                        + "Run /reload to apply them.", t.toString());
            }
        }

        @SubscribeEvent
        public static void onServerShutdown(ServerStoppedEvent event) {
            UnlockHandler.clear();
        }

        /**
         * Fires once with a null player after every datapack reload, which is exactly when
         * the recipe list may have changed underneath us.
         */
        @SubscribeEvent
        public static void onDatapackSync(OnDatapackSyncEvent event) {
            if (event.getPlayer() == null) {
                UnlockHandler.rebuild(event.getPlayerList().getServer());
            }
        }

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                UnlockHandler.onPlayerJoin(player);
            }
        }

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                UnlockHandler.onPlayerLeave(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            int interval = Config.SCAN_INTERVAL_TICKS.get();
            if (player.tickCount % interval != 0) {
                return;
            }
            UnlockHandler.scan(player);
            UnlockHandler.tickSync(player);
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(MOD_ID)
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
                applyRepair(ctx.getSource().getServer(), ctx.getSource());
                return 1;
            }));

            root.then(Commands.literal("rebuild").executes(ctx -> {
                UnlockHandler.rebuild(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal("Recipe index rebuilt."), true);
                return 1;
            }));

            // "What does picking this up unlock?"
            root.then(Commands.literal("triggers")
                    .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
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
                    .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
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
                                    java.util.Set<String> names = new java.util.TreeSet<>();
                                    for (Item i : m.triggers()) {
                                        names.add(String.valueOf(BuiltInRegistries.ITEM.getKey(i)));
                                    }
                                    int shown = 0;
                                    for (String n : names) {
                                        if (shown++ == 12) {
                                            sb.append(" ... (+").append(names.size() - 12).append(" more)");
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

            event.getDispatcher().register(root);
        }
    }
}
