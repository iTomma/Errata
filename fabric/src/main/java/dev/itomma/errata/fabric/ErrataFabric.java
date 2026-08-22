package dev.itomma.errata.fabric;

import dev.itomma.errata.core.ErrataCommands;
import dev.itomma.errata.core.ErrataCore;
import dev.itomma.errata.core.RepairService;
import dev.itomma.errata.core.UnlockHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric entrypoint. The mirror of {@code dev.itomma.errata.Errata} on NeoForge: it owns the
 * lifecycle, translates Fabric events into calls on the core, and nothing else.
 */
public final class ErrataFabric implements ModInitializer {

    /** Set once per server start so a repair can never bounce the reload in a loop. */
    private static boolean repairAttempted = false;

    @Override
    public void onInitialize() {
        FabricConfig config = FabricConfig.load(
                FabricLoader.getInstance().getConfigDir().resolve(ErrataCore.MOD_ID + ".json"));
        ErrataCore.bind(config);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            UnlockHandler.rebuild(server);
            if (!repairAttempted && ErrataCore.config().repairBrokenAdvancements()) {
                repairAttempted = true;
                RepairService.run(server);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            repairAttempted = false;
            UnlockHandler.clear();
        });

        // Fires after every datapack reload, which is exactly when the recipe list may have
        // changed underneath us. Equivalent to NeoForge's OnDatapackSyncEvent with a null player.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
                (server, resourceManager, success) -> {
                    if (success) {
                        UnlockHandler.rebuild(server);
                    }
                });

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> UnlockHandler.onPlayerJoin(handler.player));

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> UnlockHandler.onPlayerLeave(handler.player));

        // NeoForge gives us a per-player tick event; Fabric ticks the server, so we walk the
        // player list ourselves. The modulo is on each player's own tickCount, so the staggering
        // across players matches the NeoForge build exactly.
        ServerTickEvents.END_SERVER_TICK.register(ErrataFabric::tickPlayers);

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        ErrataCommands.register(dispatcher, registryAccess));

        ErrataCore.LOGGER.info("Errata ready (Fabric).");
    }

    private static void tickPlayers(MinecraftServer server) {
        int interval = ErrataCore.config().scanIntervalTicks();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.tickCount % interval != 0) {
                continue;
            }
            UnlockHandler.scan(player);
            UnlockHandler.tickSync(player);
        }
    }
}
